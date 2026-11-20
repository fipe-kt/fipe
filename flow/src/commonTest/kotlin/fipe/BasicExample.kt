package fipe

import fipe.step.BufferedMapStep
import fipe.step.MapStep
import fipe.step.ParallelOrderedStep
import fipe.step.thenBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.invoke
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.math.pow
import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

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

    fun Double.roundTo(n: Int): Double {
        val p = 10.0.pow(n)
        return round(this * p) / p
    }

    @Test
    fun `buffer step을 사용하면 파이프라인 처리 속도가 이론적 한계에 근접하게 빨라진다`() = runTest {
        // given
        val bufferSize = 10

        fun getPipeline(useBuffer: Boolean): Fipe<Int, Double> {
            val pipeline = fipe<Int>()
                .then(MapStep { delay(30); it * 2 })   // Step 1: 30ms
                .let { if (useBuffer) it.thenBuffer(bufferSize) else it }
                .then(MapStep { delay(60); it * 1.0 }) // Step 2: 60ms (가장 오래 걸림)
                .then(MapStep { it + 1 })              // Step 3: 0ms

            return pipeline
        }

        suspend fun Fipe<Int, Double>.pipelineResult(): List<Double> {
            return Dispatchers.Default {
                toFlow(
                    flow {
                        repeat(bufferSize) {
                            delay(30)
                            emit(it)
                        }
                    }
                ).take(bufferSize).toList()
            }
        }

        val n = bufferSize
        val t1 = 30L // ms, step 1
        val t2 = 60L // ms, step 2
        val t3 = 0L  // ms, step 3

        // 순차 처리 시간 = (t1 + t2 + t3) * n
        val expectedSequential = (t1 + t2 + t3) * n
        // 파이프라이닝(이론상) = t1 + (n-1) * max(t1, t2, t3)
        val expectedPipeline = t1 + (n - 1) * maxOf(t1, t2, t3)

        // when
        val pipelineWithoutBuffer = getPipeline(false)
        val pipelineWithBuffer = getPipeline(true)
        val timeWithoutBuffer = measureTime { pipelineWithoutBuffer.pipelineResult() }
        val timeWithBuffer = measureTime { pipelineWithBuffer.pipelineResult() }

        val timeWithoutBufferMs = timeWithoutBuffer.inWholeMilliseconds
        val timeWithBufferMs = timeWithBuffer.inWholeMilliseconds

        val theoreticalBestSpeedup = expectedSequential.toDouble() / expectedPipeline
        val actualSpeedup = timeWithoutBufferMs.toDouble() / timeWithBufferMs
        val expectedMinSpeedup = theoreticalBestSpeedup * 0.9 // 90% 이상만 통과

        fun pct(x: Double): Double = (x * 100).roundTo(1)
        fun dbl(x: Double): Double = x.roundTo(2)

        println("순차처리 이론상 최소시간: $expectedSequential ms")
        println("파이프라이닝 이론상 최소시간(최적화 한계): $expectedPipeline ms")
        println("실제 순차 처리시간: $timeWithoutBufferMs ms")
        println("실제 파이프라인 처리시간: $timeWithBufferMs ms")
        println("이론상 최대 가속배수: ${dbl(theoreticalBestSpeedup)}배")
        println("실제 가속배수: ${dbl(actualSpeedup)}배")
        println("실제/이론 speedup 비율: ${pct(actualSpeedup / theoreticalBestSpeedup)}%")

        // 이론상 최대 개선비의 90% 이상을 달성해야 한다
        assertTrue(
            actualSpeedup >= expectedMinSpeedup,
            """
        파이프라이닝의 이론적 최대 개선(=${dbl(theoreticalBestSpeedup)}배)의 90% 이상 달성 필요.
        실제 개선: ${dbl(actualSpeedup)}배
        (순차: ${timeWithoutBufferMs}ms, 파이프라인: ${timeWithBufferMs}ms, 
        이론최소: ${expectedPipeline}ms, 이론최대개선: ${dbl(theoreticalBestSpeedup)}배)
        """.trimIndent()
        )
    }
}