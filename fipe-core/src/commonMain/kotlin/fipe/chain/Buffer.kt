package fipe.chain

import fipe.step.BufferStep
import kotlinx.coroutines.channels.BufferOverflow

fun <T> Chain<T, T>.buffer(
    capacity: Int,
    onBufferOverflow: BufferOverflow = BufferOverflow.SUSPEND,
): Chain<T, T> = this.then(BufferStep(capacity, onBufferOverflow))