# Fipe State for Kotlin

Fipe State is a Kotlin/JVM library for local asynchronous work. A task owns its active execution. A stage closes its tasks when a visit ends. A workflow applies results to the current visit or starts a fresh visit.

## Install

Download and extract the [beta.2 Maven repository ZIP](https://github.com/fipe-kt/fipe/releases/download/1.0.0-beta.2/fipe-state-maven-1.0.0-beta.2.zip). Add its extracted directory alongside Maven Central. Use Kotlin 2.2 or later. The library emits Java 8 bytecode.

For the previous beta and its scope-taking factory, see the [beta.1 Kotlin guide](https://github.com/fipe-kt/fipe/blob/1.0.0-beta.1/fipe-state-kotlin/README.md).

```kotlin
repositories {
    maven { url = uri("/path/to/extracted/maven-repository") }
    mavenCentral()
}

dependencies {
    implementation("io.github.fipe-kt:fipe-state:1.0.0-beta.2")
}
```

## Define and start a workflow

```kotlin
import fipe.state.Change
import fipe.state.workflow

enum class Page { Form, Done }
enum class Event { Save, Back }

fun editor(save: suspend () -> Unit) =
    workflow(Page.Form, { it }, { event: Event -> event }) {
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

To migrate from beta.1, update Kotlin imports from `kipe.state` to `fipe.state` (including `fipe.state.auth`), and move the scope argument from `workflow(scope, …)` to `.startIn(scope)` at the execution site.

The declaration evaluates the DSL and validates the initial stage once. Call `startIn` to create an execution for an owner. In a ViewModel, keep the running workflow as a property.

```kotlin
private val form = editor(repository::save).startIn(viewModelScope)
```

Each start gets fresh visits, task slots, and failures. Starts share the initial value and handler captures, so keep state immutable and choose shared dependencies deliberately. Capture `form.visit` when displaying a screen and pass that visit to `form.send(visit, Event.Save)` when the user taps Save.

When the ViewModel is cleared, its scope cancels the workflow's work and the workflow rejects further input. Call `close()` when an individual execution must end before its owner, for example when closing one editor tab in a shared ViewModel. A definition can start again under another owner.

For authentication, `fipe.state.auth.SessionOwner` owns one login instance. Mount workflows on its `scope`. Concurrent calls to `refresh()` await the same in-flight renewal. Call `close()` on logout and create a new owner for the next login. Keep the credential returned by `currentCredential()` within the authentication adapter.

## Build and verify

Use JDK 17 or 21 to run the root Gradle wrapper. From the repository root, publish the Java library and source jar to the staging directory. Run the consumer to resolve the staged Maven publication and exercise the shared contract and the Kotlin definition lifecycle checks.

```sh
./gradlew -p state/kotlin publishAllPublicationsToStagingRepository
./gradlew -p state/kotlin/consumer verifyInstalled
```

The Maven repository defaults to `state/kotlin/build/maven-repository`. Set `-PfipeMavenRepository=/absolute/path` on both commands to use another directory.
