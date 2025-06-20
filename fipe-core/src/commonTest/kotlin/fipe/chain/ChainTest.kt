package fipe.chain

import fipe.step.BufferedMapStep
import fipe.step.MapStep
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ChainTest {
    @Test
    fun `chain maps and buffers in order`() = runTest {
        val pipeline = chain<Int>()
            .then(MapStep { it * 2 })
            .then(BufferedMapStep(capacity = 8, onBufferOverflow = BufferOverflow.SUSPEND) { it + 1 })
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
}