# Fipe State for Kotlin

Fipe State is a Kotlin/JVM library for local asynchronous work. A task owns its active execution. A stage closes its tasks when a visit ends. A workflow applies results to the current visit or starts a fresh visit.

## Install

Use Kotlin 2.2 or a compatible later compiler. The library emits Java 8 bytecode and uses Kotlin coroutines. Add the extracted Maven release repository and Maven Central.

```kotlin
repositories {
    maven { url = uri("/path/to/fipe-state-maven") }
    mavenCentral()
}

dependencies {
    implementation("io.github.fipe-kt:fipe-state:1.0.0-beta.1")
}
```

## Start a workflow

```kotlin
import kipe.state.Change
import kipe.state.workflow
import kotlinx.coroutines.CoroutineScope

enum class Page { Form, Done }
enum class Event { Save, Back }

fun editor(scope: CoroutineScope, save: suspend () -> Unit) =
    workflow(scope, Page.Form, { it }, { event: Event -> event }) {
        stage(Page.Form) {
            task(Event.Save) { _, _ ->
                save()
                Change.Enter { Page.Done }
            }
        }
        stage(Page.Done) {
            on(Event.Back) { _, _ -> Change.Enter { Page.Form } }
        }
    }
```

Call `send(workflow.visit, Event.Save)` to bind an event to the current visit. Close the workflow when its owner leaves. Read `snapshot` to obtain state and structured task failures together. Keep state immutable and reducers non-suspending.

For authentication, `kipe.state.auth.SessionOwner` owns one login instance. Mount workflows on its `scope`. Concurrent calls to `refresh()` await the same in-flight renewal. Call `close()` on logout and create a new owner for the next login. Keep the credential returned by `currentCredential()` within the authentication adapter.

## Build and verify

Use JDK 17 or 21 to run the root Gradle wrapper. From the repository root, publish the Java library and source jar to the staging directory. Run the consumer to resolve the staged Maven publication and exercise all 15 Kotlin contract cases.

```sh
./gradlew -p state/kotlin publishAllPublicationsToStagingRepository
./gradlew -p state/kotlin/consumer verifyInstalled
```

The Maven repository defaults to `state/kotlin/build/maven-repository`. Set `-PfipeMavenRepository=/absolute/path` on both commands to use another directory.
