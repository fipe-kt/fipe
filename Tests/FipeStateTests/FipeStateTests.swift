import FipeState
import Testing

@MainActor
@Test func sharedRuntimeContract() async throws {
    try await verifyFipe { print($0) }
}

@MainActor
@Test func closingSessionLifetimeReleasesRefreshWaiters() async throws {
    try await verifySessionLifetimeClose()
}
