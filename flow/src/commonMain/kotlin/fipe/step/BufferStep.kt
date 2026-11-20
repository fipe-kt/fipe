package fipe.step

import fipe.Fipe
import fipe.Step
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer


interface BufferStep<T> : Step<T, T> {
    val capacity: Int
    val onBufferOverflow: BufferOverflow
}

fun <T> BufferStep(
    capacity: Int,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    name: String = "BufferStep",
): BufferStep<T> = BufferStepImpl(capacity, onBufferOverflow, name)

fun <In, Out> Fipe<In, Out>.thenBuffer(
    capacity: Int = BUFFERED,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
): Fipe<In, Out> = this.then(BufferStep(capacity, onBufferOverflow))

private class BufferStepImpl<T>(
    override val capacity: Int,
    override val onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
    override val name: String = "BufferStep",
) : BufferStep<T> {
    override fun process(flow: Flow<T>): Flow<T> = flow.buffer(capacity, onBufferOverflow)
}