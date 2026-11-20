# Fipe State for Swift

Use Fipe State from Swift 6 on iOS 16 or later and macOS 13 or later.

## Install with Swift Package Manager

Add the repository in Xcode and choose the exact version `1.0.0-beta.1`. Select the `FipeState` library product. For a Swift package, declare the dependency and add the product to your target.

```swift
.package(url: "https://github.com/fipe-kt/fipe.git", exact: "1.0.0-beta.1")
```

```swift
.product(name: "FipeState", package: "fipe")
```

Import `FipeState`. Access its runtime on the main actor. Read the synchronous snapshot from your view model and publish changes through your own observation adapter. Close your view or session owner at the corresponding lifecycle boundary.

## Verify

From the repository root, run the workflow and session tests.

```sh
swift test -Xswiftc -warnings-as-errors
```
