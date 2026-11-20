# Fipe Flow

Use `then` to chain mapping, buffering, and parallel transformations on a Kotlin `Flow`. The Kotlin Multiplatform package for Android and iOS is available on Maven Central.

## Install

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.fipe-kt:fipe-core:1.0.0-alpha.1")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
        }
    }
}
```

## Build a pipeline

This example doubles each value and then adds one. The buffer lets the first step process the next value while the second step consumes the previous one.

```kotlin
import fipe.fipe
import fipe.step.BufferStep
import fipe.step.MapStep
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList

suspend fun main() {
    val pipeline = fipe<Int>()
        .then(MapStep { it * 2 })
        .then(BufferStep(capacity = 8))
        .then(MapStep { it + 1 })

    println(pipeline.toFlow(flowOf(1, 2, 3)).toList()) // [3, 5, 7]
}
```

<details>
<summary>Example timings with and without a buffer</summary>

The experiment ran with 30 values and a 30 ms delay per input. The first two transformations added delays of 30 ms and 60 ms, respectively. The third transformation ran without an added delay.

| Execution | Theoretical minimum for the transformation steps | Measured time |
| --- | ---: | ---: |
| Sequential | 2.7 s | 3.958783583 s |
| Buffered pipeline | 1.77 s | 2.050898667 s |

[Buffer behavior test](src/commonTest/kotlin/fipe/BasicExample.kt)

</details>
