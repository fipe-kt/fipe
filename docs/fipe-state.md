# Fipe State 1.0.0-beta.1

Fipe State coordinates local application state and asynchronous work for Kotlin, React Native, and Swift. A task controls overlapping runs. A stage owns its tasks. A workflow routes events and distinguishes in-place updates from entry into a new visit.

## Install

The GitHub prerelease distributes a Maven repository archive and an npm tarball. Swift Package Manager resolves the same version from the Git tag.

### Kotlin on JVM and Android

Extract `fipe-state-maven-1.0.0-beta.1.zip` and add its root as a Maven repository. Keep Maven Central for Kotlin and coroutines dependencies.

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("path/to/extracted/fipe-state-maven") }
        mavenCentral()
    }
}
```

```kotlin
dependencies {
    implementation("io.github.fipe-kt:fipe-state:1.0.0-beta.1")
}
```

Import runtime types from `kipe.state`. Supply the coroutine scope that owns the work. On Android, a ViewModel can pass its own scope and expose state through its existing UI state adapter. The beta provides a synchronous workflow snapshot.

### React Native

Install the downloaded tarball.

```sh
npm install ./fipe-state-1.0.0-beta.1.tgz
```

Import the runtime from `fipe-state`. Import the React hook from `fipe-state/react` when a component needs to observe the current workflow snapshot. Pass the current visit to screen-bound events so unmounted or replaced screens cannot dispatch into a later visit.

### Swift

Add the repository in Xcode and choose the exact version `1.0.0-beta.1`. Select the `FipeState` library product. For a Swift package, declare the dependency and add the product to your target.

```swift
.package(url: "https://github.com/fipe-kt/fipe.git", exact: "1.0.0-beta.1")
```

```swift
.product(name: "FipeState", package: "fipe")
```

Import `FipeState`. Access its runtime on the main actor. Read the synchronous snapshot from your view model and publish changes through your own observation adapter. Close your view or session owner at the corresponding lifecycle boundary.

## Runtime rules

- Start work only when the task accepts the event. Use replacement for superseded requests and ignore repeated inputs when one execution is already active.
- Apply an in-place change to the latest state. Choose a new visit when old inputs and results must lose authority, including entry to the same stage key.
- Keep session refresh with its login owner. Two views can wait for one refresh, and closing a view ends its wait. Retiring the login revokes local credential installation.
- Treat workflow snapshots as display data. A failed task exposes a label and failure kind. The authentication adapter owns credentials and provider-specific error handling.

The shared [behavior contract](../state/conformance/state-contract.json) lists the cancellation, transition, editing, and session cases.

Closing a visit revokes its authority to update state and requests cancellation. Replacement can start while previous work is still cleaning up. An exclusive resource such as a camera needs an adapter that requests release on the old session and suspends acquisition until the platform reports availability. A server operation can continue after its caller stops waiting. Its transaction, idempotency key, and result lookup belong to the service that owns the effect.

The shared beta API covers local execution and transition rules. Long-lived shared work such as session refresh has a separate owner. Application-specific policies for durable jobs, physical resource handoff, and task collections can live in adapters.

## Release files

| File | Purpose |
| --- | --- |
| `fipe-state-maven-1.0.0-beta.1.zip` | Install the Kotlin library from a local Maven repository. |
| `fipe-state-1.0.0-beta.1.tgz` | Install the TypeScript runtime and declarations with npm. |
| `fipe-state-source-1.0.0-beta.1.zip` | Inspect the three implementations or consume the Swift package by local path. |
| `SHA256SUMS` | Verify the downloaded files. |

Authenticate and authorize against your own services. The beta checks cover local session lifecycle and checkout protocol behavior with synthetic provider inputs.

## Verify the packages

From the repository root, build the Maven repository and run the separately installed Kotlin consumer. Set `JAVA_HOME` to a JDK supported by the Gradle wrapper.

```sh
./gradlew -p state/kotlin publishAllPublicationsToStagingRepository
./gradlew -p state/kotlin/consumer verifyInstalled
```

Run the TypeScript package tests from its directory. They pack the package and run the common cases against an external installation.

```sh
cd state/react-native
npm ci
npm test
```

Run the Swift contract from the repository root with a Swift 6 toolchain.

```sh
swift test -Xswiftc -warnings-as-errors
```
