# Fipe

Fipe is a lightweight Kotlin Multiplatform library for building asynchronous pipelines with Kotlin `Flow`. It lets you chain together small processing steps to create complex flows.

## Features

- **Chain** interface for composing steps.
- **MapStep** and **BufferedStep** implementations for transforming and buffering elements.
- Supports Android and iOS through Kotlin Multiplatform.

## Usage

```kotlin
import kipe.chain.chain
import kipe.step.MapStep
import kipe.step.BufferedStep
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.flowOf

val pipeline = chain<Int>()
    .then(MapStep { it * 2 })
    .then(BufferedStep(capacity = 16, onBufferOverflow = BufferOverflow.SUSPEND) { it + 1 })

val output = pipeline.toFlow(flowOf(1, 2, 3))
```

## Building

Run Gradle to build the project or execute tests:

```bash
./gradlew build
```

## License

This project is licensed under the Apache License 2.0.

---

# 한국어 안내

Fipe는 Kotlin `Flow` 기반 파이프라인을 간단히 구성할 수 있는 가벼운 Kotlin 멀티플랫폼 라이브러리입니다. 여러 단계를 체인으로 연결하여 비동기 작업을 작성할 수 있습니다.

## 특징

- 단계들을 연결하는 `Chain` 인터페이스
- 변환용 `MapStep`, 버퍼링을 제공하는 `BufferedStep`
- Android와 iOS 등 멀티플랫폼 지원

## 사용 예시

```kotlin
val pipeline = chain<Int>()
    .then(MapStep { it * 2 })
    .then(BufferedStep(capacity = 16, onBufferOverflow = BufferOverflow.SUSPEND) { it + 1 })
```

## 빌드

```bash
./gradlew build
```
