package kipe.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

class Stage internal constructor(
    private val scope: CoroutineScope,
    private val lock: Any,
) : AutoCloseable {
    constructor(scope: CoroutineScope) : this(scope, Any())

    private val tasks = mutableListOf<Task<*, *>>()
    private var closed = false

    fun <I, O> task(run: suspend (I) -> O): Task<I, O> =
        synchronized(lock) { taskLocked(run) { emptyList() } }

    internal fun <I, O> taskLocked(
        run: suspend (I) -> O,
        onSettledLocked: (Result<O>) -> List<Job>,
    ): Task<I, O> {
        check(!closed) { "Stage is closed" }
        return Task(scope, lock, run, onSettledLocked).also(tasks::add)
    }

    override fun close() {
        synchronized(lock) { closeLocked() }.forEach { it.cancel() }
    }

    internal fun closeLocked(): List<Job> {
        closed = true
        return tasks.mapNotNull { it.closeLocked() }.also { tasks.clear() }
    }
}
