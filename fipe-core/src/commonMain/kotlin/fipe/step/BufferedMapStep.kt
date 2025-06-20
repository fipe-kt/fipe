package fipe.step

import fipe.Step
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map

interface BufferedMapStep<In, Out> : Step<In, Out>, MapStep<In, Out> {
    val capacity: Int
    val onBufferOverflow: BufferOverflow
}

inline fun <reified In, reified Out> BufferedMapStep(
    capacity: Int = BUFFERED,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    noinline mapper: suspend (In) -> Out,
): BufferedMapStep<In, Out> {
    return BufferedMapStep(
        name = "BufferedMapStep ${In::class.simpleName} - ${Out::class.simpleName}",
        capacity = capacity,
        onBufferOverflow = onBufferOverflow,
        mapper = mapper
    )
}

fun <In, Out> BufferedMapStep(
    name: String,
    capacity: Int = BUFFERED,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    mapper: suspend (In) -> Out,
): BufferedMapStep<In, Out> {
    return BufferedMapStepImpl(name, capacity, onBufferOverflow, mapper)
}

private class BufferedMapStepImpl<In, Out>(
    override val name: String,
    override val capacity: Int,
    override val onBufferOverflow: BufferOverflow,
    override val mapper: suspend (In) -> Out,
) : BufferedMapStep<In, Out> {
    override fun process(flow: Flow<In>): Flow<Out> {
        return flow.buffer(capacity, onBufferOverflow).map(mapper)
    }
}