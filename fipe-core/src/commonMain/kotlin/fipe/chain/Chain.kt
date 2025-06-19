package fipe.chain

import fipe.step.Step
import kotlinx.coroutines.flow.Flow

interface Chain<In, Out> {
    val builder: (Flow<In>) -> Flow<Out>

    fun <Next> then(step: Step<Out, Next>): Chain<In, Next>

    fun toFlow(input: Flow<In>): Flow<Out>
}

fun <In, Out> Chain(
    builder: (Flow<In>) -> Flow<Out>,
): Chain<In, Out> = ChainImpl(builder)

fun <T> chain(): Chain<T, T> = ChainImpl { it }

private class ChainImpl<In, Out>(
    override val builder: (Flow<In>) -> Flow<Out>,
) : Chain<In, Out> {
    override fun <Next> then(step: Step<Out, Next>): Chain<In, Next> =
        ChainImpl { input -> step.process(builder(input)) }

    override fun toFlow(input: Flow<In>): Flow<Out> {
        return builder(input)
    }
}