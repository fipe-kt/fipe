package fipe.step

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map

interface BufferedStep<In, Out> : Step<In, Out>, MapStep<In, Out> {
    val capacity: Int
    val onBufferOverflow: BufferOverflow
}

inline fun <reified In, reified Out> BufferedStep(
    capacity: Int,
    onBufferOverflow: BufferOverflow,
    noinline mapper: suspend (In) -> Out,
): BufferedStep<In, Out> {
    return BufferedStep(
        name = "BufferedStep ${In::class.simpleName} - ${Out::class.simpleName}",
        capacity = capacity,
        onBufferOverflow = onBufferOverflow,
        mapper = mapper
    )
}

fun <In, Out> BufferedStep(
    name: String,
    capacity: Int,
    onBufferOverflow: BufferOverflow,
    mapper: suspend (In) -> Out,
): BufferedStep<In, Out> {
    return BufferedStepImpl(name, capacity, onBufferOverflow, mapper)
}

private class BufferedStepImpl<In, Out>(
    override val name: String,
    override val capacity: Int,
    override val onBufferOverflow: BufferOverflow,
    override val mapper: suspend (In) -> Out,
) : BufferedStep<In, Out> {
    override fun process(flow: Flow<In>): Flow<Out> {
        return flow.buffer(capacity, onBufferOverflow).map(mapper)
    }
}