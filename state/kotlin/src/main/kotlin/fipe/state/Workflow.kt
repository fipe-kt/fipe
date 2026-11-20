package fipe.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive

sealed interface Change<S> {
    val apply: (S) -> S

    class Update<S>(override val apply: (S) -> S) : Change<S>
    class Enter<S>(override val apply: (S) -> S) : Change<S>
}

class Visit internal constructor()

enum class FailureKind {
    OperationFailed,
    InvalidChange,
}

data class TaskFailure(val operation: String?, val kind: FailureKind)

data class WorkflowSnapshot<S : Any>(
    val visit: Visit,
    val state: S,
    val failures: List<TaskFailure>,
) {
    val failure: TaskFailure?
        get() = failures.lastOrNull()
}

internal class TaskGroup<S : Any, E : Any>(
    val operation: String?,
    val run: suspend (S, E) -> Change<S>,
)

internal sealed interface StageAction<S : Any, E : Any> {
    class Reduce<S : Any, E : Any>(val reduce: (S, E) -> Change<S>) : StageAction<S, E>

    class Run<S : Any, E : Any>(
        val group: TaskGroup<S, E>,
        val policy: WhileRunning,
    ) : StageAction<S, E>
}

internal class StageSpec<S : Any, E : Any>(
    val actionFor: (E) -> StageAction<S, E>?,
    val groups: List<TaskGroup<S, E>>,
)

class StageDefinition<S : Any, E : Any, R : Any> internal constructor() {
    private val actions = mutableMapOf<R, StageAction<S, E>>()

    fun on(event: R, reduce: (S, E) -> Change<S>) {
        requireNewEvent(event)
        actions[event] = StageAction.Reduce(reduce)
    }

    fun task(
        event: R,
        whileRunning: WhileRunning = WhileRunning.IgnoreRepeated,
        failureLabel: Enum<*>? = null,
        run: suspend (S, E) -> Change<S>,
    ) {
        task(mapOf(event to whileRunning), failureLabel, run)
    }

    fun task(
        policies: Map<R, WhileRunning>,
        failureLabel: Enum<*>? = null,
        run: suspend (S, E) -> Change<S>,
    ) {
        require(policies.isNotEmpty()) { "A task must accept at least one event" }
        policies.keys.forEach(::requireNewEvent)
        val group = TaskGroup(failureLabel?.name, run)
        for ((event, policy) in policies) {
            actions[event] = StageAction.Run(group, policy)
        }
    }

    private fun requireNewEvent(event: R) {
        require(event !in actions) { "Event already registered: $event" }
    }

    internal fun snapshot(eventOf: (E) -> R): StageSpec<S, E> {
        val routes = actions.toMap()
        val groups = routes.values.mapNotNull { (it as? StageAction.Run)?.group }.distinct()
        return StageSpec({ event -> routes[eventOf(event)] }, groups)
    }
}

class WorkflowBuilder<S : Any, E : Any, K : Any, R : Any> internal constructor(
    private val eventOf: (E) -> R,
) {
    private val stages = mutableMapOf<K, StageSpec<S, E>>()

    fun stage(key: K, define: StageDefinition<S, E, R>.() -> Unit) {
        require(key !in stages) { "Stage already registered: $key" }
        stages[key] = StageDefinition<S, E, R>().apply(define).snapshot(eventOf)
    }

    internal fun snapshot(): Map<K, StageSpec<S, E>> = stages.toMap()
}

fun <S : Any, E : Any, K : Any, R : Any> workflow(
    initial: S,
    stageOf: (S) -> K,
    eventOf: (E) -> R,
    define: WorkflowBuilder<S, E, K, R>.() -> Unit,
): WorkflowDefinition<S, E> {
    val stages = WorkflowBuilder<S, E, K, R>(eventOf).apply(define).snapshot()
    return WorkflowDefinition(initial) { state ->
        val key = stageOf(state)
        key to requireNotNull(stages[key]) { "Undefined stage: $key" }
    }
}

class WorkflowDefinition<S : Any, E : Any> internal constructor(
    internal val initial: S,
    internal val stageFor: (S) -> Pair<Any, StageSpec<S, E>>,
) {
    internal val initialStage = stageFor(initial)

    fun startIn(scope: CoroutineScope): Workflow<S, E> = Workflow(this, scope)
}

class Workflow<S : Any, E : Any> internal constructor(
    private val definition: WorkflowDefinition<S, E>,
    private val scope: CoroutineScope,
) : AutoCloseable {
    private data class Input<S, E>(val acceptedState: S, val event: E)

    private class StageVisit<S : Any, E : Any>(
        val key: Any,
        val token: Visit,
        val owner: Stage,
        val spec: StageSpec<S, E>,
        val tasks: Map<TaskGroup<S, E>, Task<Input<S, E>, Change<S>>>,
    )

    private val lock = Any()
    private var current = newVisit(definition.initialStage)
    private var currentState = definition.initial
    private val failures = linkedMapOf<TaskGroup<S, E>, TaskFailure>()
    private var closed = false

    val snapshot: WorkflowSnapshot<S>
        get() = synchronized(lock) { WorkflowSnapshot(current.token, currentState, failures.values.toList()) }

    val visit: Visit
        get() = synchronized(lock) { current.token }

    val state: S
        get() = synchronized(lock) { currentState }

    val failure: TaskFailure?
        get() = synchronized(lock) { failures.values.lastOrNull() }

    fun send(event: E): Deferred<Unit>? = dispatch(null, event)

    fun send(visit: Visit, event: E): Deferred<Unit>? = dispatch(visit, event)

    private fun dispatch(expected: Visit?, event: E): Deferred<Unit>? {
        var retired = emptyList<Job>()
        val admission = synchronized(lock) {
            if (closed || !scope.isActive) return null
            if (expected != null && current.token !== expected) return null
            when (val action = current.spec.actionFor(event)) {
                is StageAction.Reduce -> {
                    retired = applyLocked(action.reduce(currentState, event))
                    null
                }
                is StageAction.Run -> {
                    val task = current.tasks.getValue(action.group)
                    task.startLocked(Input(currentState, event), action.policy)?.also {
                        failures.remove(action.group)
                    }
                }
                null -> return null
            }
        }
        retired.forEach { it.cancel() }
        return admission?.begin()
    }

    private fun newVisit(stage: Pair<Any, StageSpec<S, E>>): StageVisit<S, E> {
        val (key, spec) = stage
        val owner = Stage(scope, lock)
        val tasks = spec.groups.associateWith { group ->
            owner.taskLocked(
                run = { input: Input<S, E> -> group.run(input.acceptedState, input.event) },
                onSettledLocked = { result -> settleLocked(group, result) },
            )
        }
        return StageVisit(key, Visit(), owner, spec, tasks)
    }

    private fun applyLocked(change: Change<S>): List<Job> {
        val next = change.apply(currentState)
        val stage = definition.stageFor(next)
        val retired = when (change) {
            is Change.Update -> {
                require(stage.first == current.key) {
                    "Update cannot change the stage: ${current.key} to ${stage.first}"
                }
                emptyList()
            }
            is Change.Enter -> {
                val nextVisit = newVisit(stage)
                val oldJobs = current.owner.closeLocked()
                current = nextVisit
                failures.clear()
                oldJobs
            }
        }
        currentState = next
        return retired
    }

    private fun settleLocked(
        group: TaskGroup<S, E>,
        result: Result<Change<S>>,
    ): List<Job> {
        val change = result.getOrNull()
        if (change == null) {
            failures[group] = TaskFailure(group.operation, FailureKind.OperationFailed)
            return emptyList()
        }
        return try {
            applyLocked(change)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failures[group] = TaskFailure(group.operation, FailureKind.InvalidChange)
            emptyList()
        }
    }

    override fun close() {
        val retired = synchronized(lock) {
            if (closed) return
            closed = true
            current.owner.closeLocked()
        }
        retired.forEach { it.cancel() }
    }
}
