# Fipe State

When a user leaves an editor and opens it again, the two visits can look identical. Fipe State tracks which visit started each operation so a late preview from the first visit cannot replace the one on screen.

A **task** decides what to do when another input arrives during a run. A **stage** owns work for one visit. A **workflow** routes events to the active stage and applies changes to its state. A task or stage can also run on its own.

## Install and use

Choose the guide for your platform. The Kotlin and React Native packages are available as [beta release downloads](https://github.com/fipe-kt/fipe/releases/tag/1.0.0-beta.1). Swift Package Manager resolves the same Git tag.

| Platform | Guide |
| --- | --- |
| Kotlin on JVM or Android | [Install the Maven artifact and define a workflow](kotlin/README.md) |
| React Native with TypeScript | [Install the npm tarball and observe a workflow in React](react-native/README.md) |
| Swift on iOS or macOS | [Add the SwiftPM product and read workflow state](swift/README.md) |

## Runtime rules

- Start work only when the task accepts the event. Use replacement for superseded requests and ignore repeated inputs when one execution is already active.
- Apply an in-place change to the latest state. Choose a new visit when old inputs and results must lose authority, including entry to the same stage key.
- Keep session refresh with its login owner. Two views can wait for one refresh, and closing a view ends its wait. Retiring the login revokes local credential installation.
- Treat workflow snapshots as display data. A failed task exposes a label and failure kind. The authentication adapter owns credentials and provider-specific error handling.

The shared [behavior contract](conformance/state-contract.json) lists the cancellation, transition, editing, and session cases.

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
