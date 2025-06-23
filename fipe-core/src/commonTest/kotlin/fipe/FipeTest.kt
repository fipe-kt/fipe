package fipe

import fipe.step.BufferedMapStep
import fipe.step.MapStep
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

class FipeTest {
    @Test
    fun `fipe maps and buffers in order`() = runTest {
        val pipeline = fipe<Int>()
            .then(MapStep { it * 2 })
            .then(
                BufferedMapStep(
                    capacity = 8,
                    onBufferOverflow = BufferOverflow.SUSPEND
                ) { it + 1 })
            .toFlow(flowOf(1, 2, 3))

        val result = pipeline.toList()
        assertEquals(listOf(3, 5, 7), result)
    }

    @Test
    fun `map step transforms elements`() = runTest {
        val step = MapStep<Int, Int> { it * it }
        val result = step.process(flowOf(1, 2, 3)).toList()
        assertEquals(listOf(1, 4, 9), result)
    }

    @Test
    fun `share step shares upstream in pipeline`() = runTest {
        var count = 0
        val pipeline = fipe<Int>()
            .share(this, started = SharingStarted.Lazily)
        val input = flow {
            count++
            emit(1)
        }
        val shared = pipeline.toFlow(input)
        shared.first()
        shared.first()
        assertEquals(1, count)
    }

    @Test
    fun `state step exposes latest value`() = runTest {
        val pipeline = fipe<Int>()
            .state(this, started = SharingStarted.Eagerly, initialValue = 0)
        val state = pipeline.toFlow(flowOf(1, 2, 3)) as StateFlow<Int>
        val values = state.take(4).toList()
        assertEquals(listOf(0, 1, 2, 3), values)
        assertEquals(3, state.value)
    }

    @Test
    fun `conflate step drops intermediate values`() = runTest {
        val pipeline = fipe<Int>().conflate()
        val result = mutableListOf<Int>()
        pipeline.toFlow(
            flow {
                emit(1)
                emit(2)
                emit(3)
            }
        ).collect {
            result.add(it)
            if (it == 1) delay(10)
        }
        assertEquals(listOf(1, 3), result)
    }
}