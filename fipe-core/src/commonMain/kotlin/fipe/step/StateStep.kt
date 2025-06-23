package fipe.step

import fipe.Fipe
import fipe.Step
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

interface StateStep<T> : Step<T, T> {
    val scope: CoroutineScope
    val started: SharingStarted
    val initialValue: T
}

fun <T> StateStep(
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.Eagerly,
    initialValue: T,
    name: String = "StateStep",
): StateStep<T> = StateStepImpl(scope, started, initialValue, name)

fun <T> Fipe<T, T>.state(
    scope: CoroutineScope,
    started: SharingStarted = SharingStarted.Eagerly,
    initialValue: T,
): Fipe<T, T> = this.then(StateStep(scope, started, initialValue))

private class StateStepImpl<T>(
    override val scope: CoroutineScope,
    override val started: SharingStarted,
    override val initialValue: T,
    override val name: String,
) : StateStep<T> {
    override fun process(flow: Flow<T>): Flow<T> = flow.stateIn(scope, started, initialValue)
}
