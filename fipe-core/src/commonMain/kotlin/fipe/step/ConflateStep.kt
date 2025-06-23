package fipe.step

import fipe.Fipe
import fipe.Step
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate

interface ConflateStep<T> : Step<T, T>

fun <T> ConflateStep(name: String = "ConflateStep"): ConflateStep<T> = ConflateStepImpl(name)

fun <T> Fipe<T, T>.conflate(): Fipe<T, T> = this.then(ConflateStep())

private class ConflateStepImpl<T>(
    override val name: String,
) : ConflateStep<T> {
    override fun process(flow: Flow<T>): Flow<T> = flow.conflate()
}
