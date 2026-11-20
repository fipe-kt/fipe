package fipe.state.checks

import fipe.state.Change
import fipe.state.FailureKind
import fipe.state.WhileRunning
import fipe.state.workflow
import kotlinx.coroutines.cancel
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

private enum class Screen { Email, Verify, Terms }
private enum class Event { SendCode, Confirm, Resend, Back }
private data class Counter(val value: Int = 0)
private enum class CounterEvent { Increment, Load, Reenter }

fun main() = runTest {
    val auth = Controlled<Event, Screen>()
    workflow(Screen.Email, { it }, { event: Event -> event }) {
        stage(Screen.Email) { on(Event.SendCode) { _, _ -> Change.Enter { Screen.Verify } } }
        stage(Screen.Verify) {
            on(Event.Back) { _, _ -> Change.Enter { Screen.Email } }
            task(
                mapOf(Event.Confirm to WhileRunning.IgnoreRepeated, Event.Resend to WhileRunning.ReplacePrevious),
            ) { _, event ->
                val next = auth.run(event)
                Change.Enter { next }
            }
        }
        stage(Screen.Terms) {}
    }.startIn(this).use { signup ->
        check(signup.send(Event.Confirm) == null && !auth.hasPendingCall())
        signup.send(Event.SendCode)
        val first = signup.visit
        check(signup.state == Screen.Verify && !auth.hasPendingCall())

        val checking = checkNotNull(signup.send(first, Event.Confirm))
        val confirmation = auth.take()
        check(confirmation.input == Event.Confirm)
        check(signup.send(first, Event.Confirm) == null && !auth.hasPendingCall())
        val resending = checkNotNull(signup.send(first, Event.Resend))
        val resend = auth.take()
        check(resend.input == Event.Resend)
        val failure = IllegalStateException("resend failed")
        resend.fail(failure)
        resending.await()
        confirmation.complete(Screen.Terms)
        checking.join()
        check(signup.visit === first && signup.failure?.kind == FailureKind.OperationFailed)
        println("PASS workflow: a failed resend survives a late successful confirmation")

        val old = checkNotNull(signup.send(first, Event.Confirm))
        val oldConfirmation = auth.take()
        signup.send(first, Event.Back)
        check(signup.state == Screen.Email)
        signup.send(Event.SendCode)
        val second = signup.visit
        check(second !== first && signup.snapshot.state == Screen.Verify)
        check(signup.send(first, Event.Confirm) == null && !auth.hasPendingCall())
        signup.send(first, Event.Back)
        check(signup.visit === second)
        oldConfirmation.complete(Screen.Terms)
        old.join()
        check(signup.visit === second && signup.failure == null)
        val current = checkNotNull(signup.send(second, Event.Confirm))
        auth.take().complete(Screen.Terms)
        current.await()
        check(signup.state == Screen.Terms)
        println("PASS workflow: re-entry rejects events and results from the first visit")
    }
    checkReusableDefinition()
    checkReplacementDeadline()
}

private suspend fun TestScope.checkReusableDefinition() {
    val calls = Controlled<Int, Int>()
    var definitions = 0
    var stages = 0
    val definition = workflow(Counter(), { _: Counter -> Unit }, { event: CounterEvent -> event }) {
        definitions++
        stage(Unit) {
            stages++
            on(CounterEvent.Increment) { _, _ -> Change.Update { it.copy(value = it.value + 1) } }
            on(CounterEvent.Reenter) { _, _ -> Change.Enter { it } }
            task(CounterEvent.Load) { state, _ ->
                val value = calls.run(state.value)
                Change.Update { it.copy(value = value) }
            }
        }
    }
    check(definitions == 1 && stages == 1 && !calls.hasPendingCall())
    val leftOwner = backgroundScope.childOwner()
    val rightOwner = backgroundScope.childOwner()
    try {
        definition.startIn(leftOwner).use { left ->
            definition.startIn(rightOwner).use { right ->
                val leftVisit = left.visit
                val rightVisit = right.visit
                check(leftVisit !== rightVisit && left.state == Counter() && right.state == Counter())
                left.send(rightVisit, CounterEvent.Increment)
                check(left.state == Counter())
                left.send(CounterEvent.Increment)
                check(left.state == Counter(1) && right.state == Counter())

                val leftJob = checkNotNull(left.send(CounterEvent.Load))
                val leftCall = calls.take()
                val rightJob = checkNotNull(right.send(CounterEvent.Load))
                val rightCall = calls.take()
                check(leftCall.input == 1 && rightCall.input == 0)
                check(left.send(CounterEvent.Load) == null && !calls.hasPendingCall())
                leftCall.complete(10)
                leftJob.await()
                check(left.state == Counter(10) && !rightJob.isCompleted)
                rightCall.fail(IllegalStateException("right load failed"))
                rightJob.await()
                check(left.failure == null && right.failure?.kind == FailureKind.OperationFailed)

                left.send(CounterEvent.Reenter)
                check(left.visit !== leftVisit && right.visit === rightVisit)
                check(right.failure?.kind == FailureKind.OperationFailed)
                val cancelled = checkNotNull(left.send(CounterEvent.Load))
                val late = calls.take()
                val surviving = checkNotNull(right.send(CounterEvent.Load))
                val active = calls.take()
                leftOwner.cancel()
                check(cancelled.isCancelled && !surviving.isCancelled)
                left.send(CounterEvent.Increment)
                check(left.send(CounterEvent.Load) == null && !calls.hasPendingCall())
                late.complete(100)
                cancelled.join()
                check(left.state == Counter(10) && left.failure == null)
                active.complete(20)
                surviving.await()
                check(right.state == Counter(20) && right.failure == null)

                definition.startIn(rightOwner).use { fresh ->
                    check(fresh.state == Counter() && fresh.failure == null)
                    check(fresh.visit !== left.visit && fresh.visit !== right.visit)
                    val closing = checkNotNull(right.send(CounterEvent.Load))
                    val closedCall = calls.take()
                    right.close()
                    check(closing.isCancelled && right.send(CounterEvent.Increment) == null)
                    closedCall.complete(200)
                    closing.join()
                    check(right.state == Counter(20) && fresh.state == Counter())
                    val freshJob = checkNotNull(fresh.send(CounterEvent.Load))
                    calls.take().complete(30)
                    freshJob.await()
                    check(fresh.state == Counter(30))
                    val retiring = checkNotNull(fresh.send(CounterEvent.Load))
                    val retiredCall = calls.take()
                    rightOwner.cancel()
                    check(retiring.isCancelled && fresh.send(CounterEvent.Increment) == null)
                    retiredCall.fail(IllegalStateException("late owner failure"))
                    retiring.join()
                    check(fresh.state == Counter(30) && fresh.failure == null)
                }
                check(definitions == 1 && stages == 1)
            }
        }
    } finally {
        leftOwner.cancel()
        rightOwner.cancel()
    }
    println("PASS workflow definition: one declaration starts independent visits, state, tasks, and owners")
}

private fun TestScope.checkReplacementDeadline() {
    val started = mutableListOf<String>()
    val search = workflow("", { _: String -> Unit }, { _: String -> Unit }) {
        stage(Unit) {
            task(Unit, WhileRunning.ReplacePrevious) { _, query ->
                started.add(query)
                delay(1.seconds)
                Change.Update { query }
            }
        }
    }
    search.startIn(this).use { runner ->
        val first = checkNotNull(runner.send("old"))
        testScheduler.runCurrent()
        check(started == listOf("old"))
        testScheduler.advanceTimeBy(500.milliseconds)
        val second = checkNotNull(runner.send("new"))
        testScheduler.runCurrent()
        check(first.isCancelled && started == listOf("old", "new"))

        testScheduler.advanceTimeBy(500.milliseconds)
        testScheduler.runCurrent()
        check(runner.state.isEmpty() && second.isActive)
        testScheduler.advanceTimeBy(499.milliseconds)
        testScheduler.runCurrent()
        check(runner.state.isEmpty() && second.isActive)
        testScheduler.advanceTimeBy(1.milliseconds)
        testScheduler.runCurrent()
        check(second.isCompleted && runner.state == "new")
    }
    println("PASS workflow: replacement commits only at its own virtual deadline")
}
