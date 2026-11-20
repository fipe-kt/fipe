<img src="logo.png" alt="Fipe" width="240">

# Fipe

Fipe는 앱의 상태와 비동기 작업을 관리하는 **Fipe State**와 Kotlin `Flow`로 데이터 파이프라인을 만드는 **Fipe Flow**를 제공합니다.

## Fipe State

화면에서 시작한 요청의 수명과 단계 전환을 관리합니다. 예를 들어 이메일 인증 중에 뒤로 갔다가 다시 진입하면, 첫 방문의 인증 응답은 새 화면을 전환할 수 없습니다.

- `task`는 실행이 겹칠 때 중복 입력을 무시하거나 이전 작업을 교체합니다.
- `stage`는 작업을 소유하고, 단계에서 나갈 때 그 작업의 늦은 결과가 반영되지 않도록 합니다.
- `workflow`는 현재 단계에 이벤트를 전달하고 상태 갱신과 단계 전환을 처리합니다.

| 플랫폼 | `1.0.0-beta.1` 설치 |
| --- | --- |
| Kotlin · JVM, Android | GitHub 릴리스의 Maven ZIP을 풀고 `io.github.fipe-kt:fipe-state:1.0.0-beta.1`을 추가합니다. |
| React Native · TypeScript | GitHub 릴리스의 tarball을 받아 `npm install ./fipe-state-1.0.0-beta.1.tgz`를 실행합니다. |
| Swift · iOS, macOS | SwiftPM에서 이 저장소의 `1.0.0-beta.1` 태그와 `FipeState` 제품을 선택합니다. |

[베타 다운로드](https://github.com/fipe-kt/fipe/releases/tag/1.0.0-beta.1) · [플랫폼별 설치 가이드](https://github.com/fipe-kt/fipe/blob/1.0.0-beta.1/docs/fipe-state.md)

소스 코드 · [Kotlin](fipe-state-kotlin/src/main/kotlin/kipe/state/) · [React Native](packages/fipe-state/src/) · [Swift](Sources/FipeState/)

## Fipe Flow

Kotlin `Flow`의 매핑, 버퍼링, 병렬 변환 등을 `then`으로 연결합니다. Android와 iOS용 Kotlin Multiplatform 패키지를 Maven Central에서 받을 수 있습니다.

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

아래 예제는 각 값을 두 배로 만든 뒤 1을 더합니다. 두 변환 사이의 버퍼를 통해 앞 단계는 뒤 단계가 소비하는 동안 다음 값을 처리할 수 있습니다.

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
<summary>버퍼 적용 전후 측정 예시</summary>

값 30개와 항목당 30ms의 입력 지연을 사용한 실험입니다. 첫 두 변환에 각각 30ms와 60ms의 지연을 넣고, 세 번째 변환은 지연 없이 실행했습니다.

| 실행 방식 | 변환 단계 기준 이론 최소 | 측정값 |
| --- | ---: | ---: |
| 순차 처리 | 2.7초 | 3.958783583초 |
| 버퍼를 사용한 파이프라인 | 1.77초 | 2.050898667초 |

[버퍼 동작 테스트](fipe-core/src/commonTest/kotlin/fipe/BasicExample.kt)

</details>

## 라이선스

[Apache License 2.0](LICENSE)
