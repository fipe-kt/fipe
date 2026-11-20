package fipe.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive

enum class WhileRunning {
    IgnoreRepeated,
    ReplacePrevious,
}

sealed interface TaskState<out O> {
    data object Idle : TaskState<Nothing>
    data object Running : TaskState<Nothing>
    data class Succeeded<O>(val value: O) : TaskState<O>
    data object Failed : TaskState<Nothing>
    data object Closed : TaskState<Nothing>
}

internal class Admission(
    val job: Deferred<Unit>,
    private val displaced: Job?,
) {
    fun begin(): Deferred<Unit> {
        displaced?.cancel()
        job.start()
        return job
    }
}

class Task<I, O> internal constructor(
    private val scope: CoroutineScope,
    private val lock: Any,
    private val run: suspend (I) -> O,
    private val onSettledLocked: (Result<O>) -> List<Job>,
) : AutoCloseable {
    constructor(scope: CoroutineScope, run: suspend (I) -> O) :
        this(scope, Any(), run, { emptyList() })

    private var currentState: TaskState<O> = TaskState.Idle
    private var active: Deferred<Unit>? = null

    val state: TaskState<O>
        get() = synchronized(lock) { currentState }

    fun start(input: I, whileRunning: WhileRunning = WhileRunning.IgnoreRepeated): Deferred<Unit>? =
        synchronized(lock) { startLocked(input, whileRunning) }?.begin()

    internal fun startLocked(input: I, whileRunning: WhileRunning): Admission? {
        if (currentState == TaskState.Closed || !scope.isActive) return null
        val previous = active
        if (whileRunning == WhileRunning.IgnoreRepeated && previous != null && !previous.isCancelled) {
            return null
        }

        lateinit var execution: Deferred<Unit>
        execution = scope.async(start = CoroutineStart.LAZY) {
            val outcome = try {
                Result.success(coroutineScope { run(input) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Result.failure(failure)
            }

            val displaced = synchronized(lock) {
                if (active !== execution || execution.isCancelled || !scope.isActive) return@async
                active = null
                currentState = outcome.fold(
                    onSuccess = { TaskState.Succeeded(it) },
                    onFailure = { TaskState.Failed },
                )
                onSettledLocked(outcome)
            }
            displaced.forEach { it.cancel() }
        }
        active = execution
        currentState = TaskState.Running
        execution.invokeOnCompletion {
            synchronized(lock) {
                if (active === execution) {
                    active = null
                    currentState = TaskState.Idle
                }
            }
        }
        return Admission(execution, previous)
    }

    override fun close() {
        synchronized(lock) { closeLocked() }?.cancel()
    }

    internal fun closeLocked(): Job? {
        val execution = active
        active = null
        currentState = TaskState.Closed
        return execution
    }
}
