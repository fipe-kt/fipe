package fipe

import fipe.step.BufferedMapStep
import fipe.step.MapStep
import fipe.step.ParallelOrderedStep
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BasicExample {
    @Test
    fun `basic example`() = runTest {
        val pipeline = fipe<Int>()
            .then(MapStep { it * 2 })
            .then(BufferedMapStep(capacity = 10) { it + 1 })
            .then(ParallelOrderedStep { listOf(it, it + 1) })

        val result = pipeline
            .toFlow(flowOf(1, 2, 3, 4, 5))
            .toList()

        val expected = listOf(
            listOf(3, 4),
            listOf(5, 6),
            listOf(7, 8),
            listOf(9, 10),
            listOf(11, 12)
        )
        assertEquals(expected, result)
    }
}