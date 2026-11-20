package fipe

import kotlinx.coroutines.flow.Flow

fun <T> fipe(): Fipe<T, T> = Fipe { it }

fun <In, Out> ((Flow<In>) -> Flow<Out>).asFipe(): Fipe<In, Out> = Fipe(this)

fun <In, Out> Fipe(
    builder: (Flow<In>) -> Flow<Out>,
): Fipe<In, Out> = FipeImpl(builder)

private class FipeImpl<In, Out>(
    override val builder: (Flow<In>) -> Flow<Out>,
) : Fipe<In, Out> {
    override fun <Next> then(step: Step<Out, Next>): Fipe<In, Next> =
        FipeImpl { input -> step.process(builder(input)) }

    override fun toFlow(input: Flow<In>): Flow<Out> {
        return builder(input)
    }
}