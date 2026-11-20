package kipe.state.checks

import kipe.state.Change
import kipe.state.FailureKind
import kipe.state.WhileRunning
import kipe.state.workflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private enum class Screen { Email, Verify, Terms }
private enum class Event { SendCode, Confirm, Resend, Back }

fun main() = runBlocking {
    val host = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
        withTimeout(10_000) {
            val auth = Controlled<Event, Screen>()
            workflow(host, Screen.Email, { it }, { event: Event -> event }) {
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
            }.use { signup ->
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
        }
    } finally {
        host.cancel()
    }
}
