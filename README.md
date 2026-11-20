<img src="logo.png" alt="Fipe" width="240">

# Fipe

**Fipe State** manages application state and asynchronous work. **Fipe Flow** builds data pipelines with Kotlin `Flow`.

## Fipe State

Fipe State manages the lifetime of asynchronous requests and the transitions between stages. If a user leaves email verification and returns, a response from the earlier visit cannot advance the new one.

- A `task` handles overlapping runs by ignoring repeated input or replacing the current run.
- A `stage` owns its tasks. Leaving the stage prevents their late results from updating state.
- A `workflow` routes events to the current stage and handles state updates and transitions.

| Platform | Install `1.0.0-beta.1` |
| --- | --- |
| Kotlin · JVM, Android | Extract the Maven ZIP from the GitHub release and add `io.github.fipe-kt:fipe-state:1.0.0-beta.1` as a dependency. |
| React Native · TypeScript | Download the tarball from the GitHub release and run `npm install ./fipe-state-1.0.0-beta.1.tgz`. |
| Swift · iOS, macOS | In SwiftPM, select this repository's `1.0.0-beta.1` tag and the `FipeState` product. |

[Download the beta](https://github.com/fipe-kt/fipe/releases/tag/1.0.0-beta.1) · [Installation guide](docs/fipe-state.md)

Source code · [Kotlin](state/kotlin/src/main/kotlin/kipe/state/) · [React Native](state/react-native/src/) · [Swift](state/swift/Sources/FipeState/)

## Fipe Flow

Use `then` to chain mapping, buffering, and parallel transformations on a Kotlin `Flow`. The Kotlin Multiplatform package for Android and iOS is available on Maven Central.

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

[Buffer behavior test](flow/src/commonTest/kotlin/fipe/BasicExample.kt)

</details>

## License

[Apache License 2.0](LICENSE)
