# Fipe State for Swift

Use Fipe State from Swift 6 on iOS 16 or later and macOS 13 or later.

## Install with Swift Package Manager

Add the repository in Xcode and choose the exact version `1.0.0-beta.2`. Select the `FipeState` library product. For a Swift package, declare the dependency and add the product to your target.

```swift
.package(url: "https://github.com/fipe-kt/fipe.git", exact: "1.0.0-beta.2")
```

```swift
.product(name: "FipeState", package: "fipe")
```

Import `FipeState`. Access its runtime on the main actor. Read the synchronous snapshot from your view model and publish changes through your own observation adapter.

## Own a workflow in a SwiftUI screen

Add the `FipeStateSwiftUI` product to your app target and import it. Use `WorkflowScreen` where a SwiftUI view owns an independent execution.

```swift
import FipeStateSwiftUI

WorkflowScreen { owner in
    EditorModel(documentID: documentID, owner: owner)
} content: { model in
    EditorView(model: model)
}
.id(documentID)
```

Pass the supplied `Lifetime` to your model's Fipe workflow. `WorkflowScreen` creates a model on appearance and closes its lifetime on disappearance. If it appears again, it creates a fresh model. The `id` modifier also gives each document its own execution.

Keep shared work, such as credential refresh, in a separate session owner and close that owner at logout. Each screen can await the session's work through its own workflow.

## Verify

From the repository root, run the workflow and session tests.

```sh
swift test -Xswiftc -warnings-as-errors
```
