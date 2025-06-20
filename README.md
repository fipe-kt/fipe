# Fipe

Fipe is a lightweight Kotlin Multiplatform library for building asynchronous pipelines with Kotlin `Flow`. 
It lets you chain together small processing steps to create complex flows.

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
