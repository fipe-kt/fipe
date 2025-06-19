package fipe.step

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StepTest {

    @Test
    fun `MapStep 기본 이름에 타입이 포함되는지 테스트`() {
        // Int를 String으로 변환하는 MapStep 생성
        val step = MapStep<Int, String> { it.toString() }
        // MapStep의 이름이 "MapStep Int - String" 이어야 한다.
        assertEquals("MapStep Int - String", step.name)
    }

    @Test
    fun `MapStep에 커스텀 이름을 주입할 수 있는지 테스트`() {
        // 이름을 "custom"으로 지정한 MapStep 생성
        val step = MapStep("custom", Int::toString)
        // step.name이 지정한 이름과 같은지 확인
        assertEquals("custom", step.name)
    }

    @Test
    fun `BufferedStep이 요소를 올바르게 변환하는지 테스트`() = runTest {
        // 버퍼 크기 2, overflow 시 SUSPEND, 입력값을 2배로 만드는 BufferedStep
        val step = BufferedStep<Int, Int>(
            capacity = 2,
            onBufferOverflow = BufferOverflow.SUSPEND
        ) { it * 2 }

        // 1, 2, 3을 입력했을 때 2, 4, 6으로 나오는지 확인
        val result = step.process(flowOf(1, 2, 3)).toList()
        assertEquals(listOf(2, 4, 6), result)
    }

    @Test
    fun `BufferedStep이 overflow 발생 시 가장 오래된 요소를 버리는지 테스트`() = runTest {
        // 버퍼 크기 1, overflow 발생 시 가장 오래된 요소(DROP_OLDEST)를 버리는 BufferedStep
        val step = BufferedStep<Int, Int>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        ) { it }

        // 값을 비동기로 전달하기 위한 Channel
        val inputChannel = Channel<Int>(Channel.UNLIMITED)
        val outputList = mutableListOf<Int>()
        // Step 처리 결과를 outputList에 모음
        val collectJob = launch {
            step.process(inputChannel.consumeAsFlow()).toList(outputList)
        }

        // 1, 2, 3을 순서대로 보냄
        inputChannel.send(1)
        inputChannel.send(2) // 1이 버퍼에 있는데 2를 넣으면 1이 버려짐
        inputChannel.send(3) // 2가 버퍼에 있는데 3을 넣으면 2가 버려짐
        inputChannel.close()
        collectJob.join()

        // 결과적으로 1(첫번째), 3(마지막)만 남는다.
        assertEquals(listOf(1, 3), outputList)
    }
}