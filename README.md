![Fipe](logo.png)

Fipe는 Kotlin `Flow`를 사용하여 비동기적이고 조립 가능한 데이터 파이프라인을 구축하기 위한 경량 Kotlin Multiplatform 라이브러리입니다.  
작고 집중된 처리 단계를 연결하여(예: 매핑, 버퍼링, 병렬 변환) 복잡하고 효율적인 데이터 흐름을 구성할 수 있습니다.

## 특징

- **조립 가능한 파이프라인**: 여러 변환 단계를 (`then`) 연결하여 데이터 스트림을 처리합니다.
- **유연한 단계**: 매핑, 버퍼링, 공유, 상태 처리, 병렬 순서 처리 기능을 기본 제공하여 다양한 처리 방식을 지원합니다.
- **Kotlin Multiplatform 지원**: JVM, Android, iOS 등 다양한 플랫폼에서 실행 가능합니다.
- **Coroutine 및 Flow 기반**: Coroutine 및 Flow를 기반으로 작성되었습니다. 

## 빠른 예제

```kotlin
 // 간단한 파이프라인 예제
 fun main() = runBlocking {
    val bufferSize = 30
    val doublingStep = MapStep<Int, Int> { delay(30); it * 2 }
    val toDoubleStep = MapStep<Int, Double> { delay(60); it.toDouble() }
    val incrementStep = MapStep<Double, Double> { it + 1 }

    fun getPipeline(useBuffer: Boolean): Fipe<Int, Double> = fipe<Int>()
        .then(doublingStep)
        .let { if (useBuffer) it.thenBuffer(bufferSize) else it }
        .then(toDoubleStep)
        .let { if (useBuffer) it.thenBuffer(bufferSize) else it }
        .then(incrementStep)

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
    val t1 = 30L
    val t2 = 60L
    val t3 = 0L

    val expectedSequential = ((t1 + t2 + t3) * n).milliseconds
    val expectedPipeline = (t1 + (n - 1) * maxOf(t1, t2, t3)).milliseconds

    val pipelineWithoutBuffer = getPipeline(false)
    val pipelineWithBuffer = getPipeline(true)

    val timeWithoutBuffer = measureTime { pipelineWithoutBuffer.pipelineResult() }
    val timeWithBuffer = measureTime { pipelineWithBuffer.pipelineResult() }

    println("이론상 최소 시간 (순차 처리): $expectedSequential")
    println("이론상 최소 시간 (파이프라인 처리): $expectedPipeline")
    println("실제 순차 처리 시간: $timeWithoutBuffer")
    println("실제 파이프라인 처리 시간: $timeWithBuffer")
}
```

> 이론상 최소 시간 (순차 처리): 2.7s
> 
> 이론상 최소 시간 (파이프라인 처리): 1.77s
> 
> 실제 순차 처리 시간: 3.958783583s
> 
> 실제 파이프라인 처리 시간: 2.050898667s 

파이프라인은 `.then(...)`을 사용하여 단계를 연결하며 구성됩니다.  
각 단계는 흐름 내에서 요소를 변환하거나, 버퍼링하거나, 병렬 처리하는 등 다양한 방식으로 데이터를 처리할 수 있습니다.  
이처럼 작은 처리 단계를 변수화하고 조립할 수 있기 때문에 복잡한 데이터 처리 로직을 효율적이고 유연하게 구현할 수 있습니다.

## 라이선스

이 프로젝트는 Apache License 2.0 하에 라이선스가 부여되었습니다.