package fipe.state.checks

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import fipe.state.Task
import fipe.state.TaskState
import fipe.state.WhileRunning

internal class Call<I, O>(val input: I, private val result: Continuation<O>) {
    fun complete(value: O) = result.resume(value)
    fun fail(error: Exception) = result.resumeWithException(error)
}

internal class Controlled<I, O> {
    private val calls = Channel<Call<I, O>>(Channel.UNLIMITED)
    suspend fun run(input: I): O = suspendCoroutine { result ->
        check(calls.trySend(Call(input, result)).isSuccess)
    }
    suspend fun take(): Call<I, O> = withTimeout(5_000) { calls.receive() }
    fun hasPendingCall() = calls.tryReceive().isSuccess
}

fun main() = runBlocking {
    val host = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
        withTimeout(10_000) {
            val search = Controlled<String, String>()
            Task(host, search::run).use { task ->
                check(task.state == TaskState.Idle && !search.hasPendingCall())
                val first = checkNotNull(task.start("A"))
                val oldA = search.take()
                check(oldA.input == "A")
                check(task.start("A") == null && !search.hasPendingCall())

                val middle = checkNotNull(task.start("B", WhileRunning.ReplacePrevious))
                val b = search.take()
                check(b.input == "B")
                val last = checkNotNull(task.start("A", WhileRunning.ReplacePrevious))
                val newA = search.take()
                check(newA.input == "A")
                oldA.complete("old A")
                first.join()
                check(task.state == TaskState.Running)
                newA.complete("new A")
                last.await()
                b.fail(IllegalArgumentException("late B failure"))
                middle.join()
                check(task.state == TaskState.Succeeded("new A"))
                println("PASS task: replace A → B → A and ignore late success and failure")

                val pending = checkNotNull(task.start("C"))
                search.take().fail(CancellationException("callback cancelled"))
                pending.join()
                check(task.state == TaskState.Idle)
                val retry = checkNotNull(task.start("C"))
                search.take().complete("retry")
                retry.await()
                check(task.state == TaskState.Succeeded("retry"))
                println("PASS task: cancellation releases the slot")
            }

            val children = Controlled<Unit, Unit>()
            val bodyReturned = CompletableDeferred<Unit>()
            Task<Unit, String>(host) {
                CoroutineScope(currentCoroutineContext()).launch { children.run(Unit) }
                bodyReturned.complete(Unit)
                "parent"
            }.use { parent ->
                val work = checkNotNull(parent.start(Unit))
                val child = children.take()
                bodyReturned.await()
                check(parent.start(Unit) == null)
                check(parent.state == TaskState.Running)
                child.complete(Unit)
                work.await()
                check(parent.state == TaskState.Succeeded("parent"))
                println("PASS task: completion waits for structured child work")
            }
        }
    } finally {
        host.cancel()
    }
}
