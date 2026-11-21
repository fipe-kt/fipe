import FipeState
import Foundation

private struct CheckFailure: Error, CustomStringConvertible {
    let line: UInt
    var description: String { "Check failed at FipeChecks.swift:\(line)" }
}

private enum FakeError: Error { case rejected }

@MainActor
private func expect(_ condition: @autoclosure () throws -> Bool, line: UInt = #line) throws {
    if try !condition() { throw CheckFailure(line: line) }
}

private func required<Value>(_ value: Value?, line: UInt = #line) throws -> Value {
    guard let value else { throw CheckFailure(line: line) }
    return value
}

@MainActor
private final class Controlled<Input: Sendable, Output: Sendable> {
    struct Call {
        let input: Input
        let result: CheckedContinuation<Output, any Error>
        func complete(_ output: Output) { result.resume(returning: output) }
        func fail(_ error: any Error) { result.resume(throwing: error) }
    }

    private var calls: [Call] = []
    private var consumer: CheckedContinuation<Call, Never>?
    private(set) var count = 0

    func run(_ input: Input) async throws -> Output {
        try await withCheckedThrowingContinuation { result in
            count += 1
            let call = Call(input: input, result: result)
            if let waiting = consumer {
                consumer = nil
                waiting.resume(returning: call)
            } else {
                calls.append(call)
            }
        }
    }

    func take() async -> Call {
        if !calls.isEmpty { return calls.removeFirst() }
        return await withCheckedContinuation { consumer = $0 }
    }
}

@MainActor
private final class Signal {
    private var fired = false
    private var consumer: CheckedContinuation<Void, Never>?

    func send() {
        fired = true
        consumer?.resume()
        consumer = nil
    }

    func wait() async {
        if fired { return }
        await withCheckedContinuation { consumer = $0 }
    }
}

@MainActor
func verifyFipe(report: (String) -> Void) async throws {
    try await checkTask(report)
    try await checkStage(report)
    try await checkSignup(report)
    try await checkEditor(report)
    try await checkPolicies(report)
    try await checkSecurity(report)
}

@MainActor
private func checkTask(_ report: (String) -> Void) async throws {
    let host = Lifetime()
    defer { host.close() }
    let search = Controlled<String, String>()
    let task = FipeTask(owner: host, work: search.run)
    try expect(task.state == .idle && search.count == 0)
    let first = try required(task.start("A"))
    let oldA = await search.take()
    try expect(oldA.input == "A" && task.start("A") == nil && search.count == 1)
    let middle = try required(task.start("B", policy: .replacePrevious))
    let b = await search.take()
    let last = try required(task.start("A", policy: .replacePrevious))
    let newA = await search.take()
    try expect(b.input == "B" && newA.input == "A")
    oldA.complete("old A")
    await first.join()
    try expect(task.state == .running)
    newA.complete("new A")
    await last.join()
    b.fail(FakeError.rejected)
    await middle.join()
    try expect(task.state == .succeeded("new A"))
    report("PASS task: replace A → B → A and ignore late success and failure")

    let pending = try required(task.start("C"))
    let cancelled = await search.take()
    cancelled.fail(CancellationError())
    await pending.join()
    try expect(task.state == .idle)
    let retry = try required(task.start("C"))
    let next = await search.take()
    next.complete("retry")
    await retry.join()
    try expect(task.state == .succeeded("retry"))
    report("PASS task: cancellation releases the slot")

    let children = Controlled<Void, Void>()
    let bodyReturned = Signal()
    let structured = FipeTask<Void, String>(owner: host) { _ in
        await withTaskGroup(of: Void.self) { group in
            group.addTask { _ = try? await children.run(()) }
            bodyReturned.send()
            return "parent"
        }
    }
    let parent = try required(structured.start(()))
    let child = await children.take()
    await bodyReturned.wait()
    try expect(structured.start(()) == nil && structured.state == .running)
    child.complete(())
    await parent.join()
    try expect(structured.state == .succeeded("parent"))
    report("PASS task: completion waits for structured child work")
}

@MainActor
private func checkStage(_ report: (String) -> Void) async throws {
    let host = Lifetime()
    defer { host.close() }
    let upload = Controlled<String, String>()
    let orders = Controlled<Void, Int>()
    let order = FipeTask(owner: host, work: orders.run)
    let tracking = try required(order.start(()))
    let update = await orders.take()
    let stage = Stage(parent: host)
    let photoA = stage.task(upload.run)
    let photoB = stage.task(upload.run)
    let a = try required(photoA.start("A"))
    let oldA = await upload.take()
    let b = try required(photoB.start("B"))
    let oldB = await upload.take()
    photoA.close()
    try expect(photoA.state == .closed && photoB.state == .running)
    stage.close()
    try expect(photoB.state == .closed && photoB.start("B") == nil && order.state == .running)
    let next = Stage(parent: host)
    let current = next.task(upload.run)
    let new = try required(current.start("A"))
    let newA = await upload.take()
    oldA.complete("old A")
    oldB.complete("old B")
    await a.join()
    await b.join()
    try expect(current.state == .running)
    newA.complete("new A")
    await new.join()
    try expect(current.state == .succeeded("new A"))
    update.complete(42)
    await tracking.join()
    try expect(order.state == .succeeded(42))
    report("PASS stage: close returns before cleanup and leaves independent work running")
}

private enum Screen: Sendable { case email, verify, terms }
private enum SignupEvent: Sendable { case sendCode, confirm, resend, back }

@MainActor
private func checkSignup(_ report: (String) -> Void) async throws {
    let host = Lifetime()
    defer { host.close() }
    let auth = Controlled<SignupEvent, Screen>()
    let check = WorkGroup<Screen, SignupEvent> { _, event in
        let next = try await auth.run(event)
        return .enter { _ in next }
    }
    let signup = try Workflow(owner: host, initial: Screen.email, key: { $0 }, route: { $0 }, stages: [
        .email: [.sendCode: .reduce { _, _ in .enter { _ in .verify } }],
        .verify: [
            .back: .reduce { _, _ in .enter { _ in .email } },
            .confirm: .work(check, .ignoreRepeated),
            .resend: .work(check, .replacePrevious),
        ],
        .terms: [:],
    ])
    let initial = signup.snapshot
    try expect(initial.state == .email && initial.visit === signup.visit && initial.failures.isEmpty)
    report("PASS workflow: the first snapshot exposes the initial state, current visit and no failures")
    try expect(try signup.send(.confirm) == nil && auth.count == 0)
    try signup.send(.sendCode)
    let first = signup.visit
    try expect(signup.state == .verify && auth.count == 0)

    let checking = try required(signup.send(.confirm, visit: first))
    let confirmation = await auth.take()
    try expect(confirmation.input == .confirm)
    try expect(try signup.send(.confirm, visit: first) == nil)
    let resending = try required(signup.send(.resend, visit: first))
    let resend = await auth.take()
    try expect(resend.input == .resend && auth.count == 2)
    resend.fail(FakeError.rejected)
    await resending.join()
    confirmation.complete(.terms)
    await checking.join()
    try expect(signup.visit === first && signup.snapshot.failure?.kind == .operationFailed)
    report("PASS workflow: a failed resend survives a late successful confirmation")

    let old = try required(signup.send(.confirm, visit: first))
    let oldConfirmation = await auth.take()
    try signup.send(.back, visit: first)
    try expect(signup.state == .email)
    try signup.send(.sendCode)
    let second = signup.visit
    try expect(second !== first && signup.state == .verify)
    try expect(try signup.send(.confirm, visit: first) == nil && auth.count == 3)
    try signup.send(.back, visit: first)
    oldConfirmation.complete(.terms)
    await old.join()
    try expect(signup.visit === second && signup.snapshot.failure == nil)
    let current = try required(signup.send(.confirm, visit: second))
    let currentConfirmation = await auth.take()
    currentConfirmation.complete(.terms)
    await current.join()
    try expect(signup.state == .terms)
    report("PASS workflow: re-entry rejects events and results from the first visit")
}

private struct Editor: Sendable, Equatable {
    enum Page: Sendable { case editing, closed }
    var page: Page = .editing
    var color = "blue"
    var opacity = 100
    var name = "Draft"
}

private enum EditorEvent: Sendable {
    enum Route: Sendable { case color, opacity, name, refresh, close, invalid }
    case color(String), opacity(Int), name(String), refresh, close, invalid

    var route: Route {
        switch self {
        case .color: .color
        case .opacity: .opacity
        case .name: .name
        case .refresh: .refresh
        case .close: .close
        case .invalid: .invalid
        }
    }
}

@MainActor
private func checkEditor(_ report: (String) -> Void) async throws {
    let host = Lifetime()
    defer { host.close() }
    let colors = Controlled<String, String>()
    let opacity = Controlled<Int, Int>()
    let setColor = WorkGroup<Editor, EditorEvent>(label: "SetColor") { _, event in
        guard case let .color(value) = event else { throw FakeError.rejected }
        let result = try await colors.run(value)
        return .update { state in var next = state; next.color = result; return next }
    }
    let setOpacity = WorkGroup<Editor, EditorEvent> { _, event in
        guard case let .opacity(value) = event else { throw FakeError.rejected }
        let result = try await opacity.run(value)
        return .update { state in var next = state; next.opacity = result; return next }
    }
    let editor = try Workflow(owner: host, initial: Editor(), key: \.page, route: \.route, stages: [
        .editing: [
            .color: .work(setColor, .ignoreRepeated),
            .opacity: .work(setOpacity, .ignoreRepeated),
            .name: .reduce { _, event in
                guard case let .name(value) = event else { return .update { $0 } }
                return .update { state in var next = state; next.name = value; return next }
            },
            .refresh: .reduce { _, _ in .enter { $0 } },
            .close: .reduce { _, _ in .enter { state in var next = state; next.page = .closed; return next } },
            .invalid: .reduce { _, _ in .update { state in var next = state; next.page = .closed; return next } },
        ],
        .closed: [:],
    ])
    let visit = editor.visit
    let color = try required(editor.send(.color("red"), visit: visit))
    let colorCall = await colors.take()
    let transparent = try required(editor.send(.opacity(20), visit: visit))
    let opacityCall = await opacity.take()
    try expect(colorCall.input == "red" && opacityCall.input == 20)
    try editor.send(.name("Updated"), visit: visit)
    colorCall.complete("red")
    await color.join()
    try expect(editor.visit === visit && !transparent.completed)
    opacityCall.complete(20)
    await transparent.join()
    try expect(editor.visit === visit && editor.state == Editor(color: "red", opacity: 20, name: "Updated"))
    report("PASS editor: independent completions and typed input preserve one visit")

    let failing = try required(editor.send(.color("green"), visit: visit))
    let failedColor = await colors.take()
    let independent = try required(editor.send(.opacity(40), visit: visit))
    let validOpacity = await opacity.take()
    failedColor.fail(FakeError.rejected)
    await failing.join()
    validOpacity.complete(40)
    await independent.join()
    try expect(editor.state.opacity == 40)
    try expect(editor.snapshot.failures == [TaskFailure(operation: "SetColor", kind: .operationFailed)])
    report("PASS editor: an unrelated successful task preserves the color error")

    let beforeInvalid = editor.snapshot
    do {
        try editor.send(.invalid, visit: visit)
        throw CheckFailure(line: #line)
    } catch WorkflowError.updateChangesStage {}
    try expect(editor.visit === beforeInvalid.visit && editor.state == beforeInvalid.state)
    try expect(editor.snapshot.failures == beforeInvalid.failures)
    let old = try required(editor.send(.color("yellow"), visit: visit))
    let late = await colors.take()
    try editor.send(.refresh, visit: visit)
    try expect(editor.visit !== visit && editor.state == beforeInvalid.state && editor.snapshot.failures.isEmpty)
    late.complete("yellow")
    await old.join()
    try expect(old.cancelled && editor.state == beforeInvalid.state)
    try editor.send(.close, visit: visit)
    try expect(editor.state.page == .editing)
    try editor.send(.close, visit: editor.visit)
    try expect(editor.state.page == .closed)
    report("PASS editor: explicit re-entry revokes the old visit even at the same stage")
}

@MainActor
private func checkPolicies(_ report: (String) -> Void) async throws {
    let host = Lifetime()
    defer { host.close() }
    let ingest = Controlled<String, Void>()
    let task = FipeTask(owner: host, work: ingest.run)
    let first = try required(task.start("first"))
    let active = await ingest.take()
    try expect(task.start("second") == nil && ingest.count == 1)
    let second = try required(task.start("second", policy: .replacePrevious))
    let overlap = await ingest.take()
    try expect(active.input == "first" && overlap.input == "second" && first.cancelled && !first.completed)
    active.complete(())
    overlap.complete(())
    await first.join()
    await second.join()
    report("PASS scheduling boundary: ignore discards work and replacement allows physical overlap")
}

private struct Credential: Sendable, Equatable {
    let account: String
    let version: Int
    func next(_ version: Int) -> Credential { Credential(account: account, version: version) }
}

private enum AuthPage: Sendable { case waiting, ready, retry }
private enum AuthEvent: Sendable { case refresh }

@MainActor
private func authView(_ session: SessionOwner<Credential>, started: Signal? = nil) throws
    -> Workflow<AuthPage, AuthEvent, AuthPage, AuthEvent> {
    let refresh = WorkGroup<AuthPage, AuthEvent> { _, _ in
        started?.send()
        switch try await session.refresh() {
        case .updated: return .enter { _ in .ready }
        case .failed: return .enter { _ in .retry }
        case .sessionClosed: return .enter { _ in .waiting }
        }
    }
    return try Workflow(owner: session.lifetime, initial: AuthPage.waiting, key: { $0 }, route: { $0 }, stages: [
        .waiting: [.refresh: .work(refresh, .ignoreRepeated)],
        .ready: [:],
        .retry: [:],
    ])
}

@MainActor
private func checkSecurity(_ report: (String) -> Void) async throws {
    let host = Lifetime()
    defer { host.close() }
    let initial = Credential(account: "same-account", version: 1)
    let provider = Controlled<Credential, Credential>()
    let session = SessionOwner(parent: host, initial: initial, renew: provider.run)
    let left = try authView(session)
    let secondStarted = Signal()
    let right = try authView(session, started: secondStarted)
    let first = try required(left.send(.refresh))
    let call = await provider.take()
    let second = try required(right.send(.refresh))
    await secondStarted.wait()
    left.close()
    await first.join()
    try expect(first.cancelled && !second.completed && call.input == initial)
    call.complete(initial.next(2))
    await second.join()
    try expect(left.state == .waiting && right.state == .ready)
    try expect(session.currentCredential?.version == 2 && provider.count == 1)
    report("PASS security: two workflows share one refresh and closing one cancels only its wait")

    let oldProvider = Controlled<Credential, Credential>()
    let newProvider = Controlled<Credential, Credential>()
    let retired = SessionOwner(parent: host, initial: initial, renew: oldProvider.run)
    let oldView = try authView(retired)
    let oldVisit = oldView.visit
    let oldJob = try required(oldView.send(.refresh))
    let oldCall = await oldProvider.take()
    let outsideStarted = Signal()
    let outside = Task { @MainActor in
        outsideStarted.send()
        return try await retired.refresh()
    }
    await outsideStarted.wait()
    retired.close()
    let retiredResult = try await outside.value
    await oldJob.join()
    try expect(retiredResult == .sessionClosed && retired.currentCredential == nil && oldProvider.count == 1)
    try expect(oldJob.cancelled && oldView.visit === oldVisit && oldView.state == .waiting)
    try expect(try oldView.send(.refresh) == nil)
    let fresh = initial.next(10)
    let current = SessionOwner(parent: host, initial: fresh, renew: newProvider.run)
    let newView = try authView(current)
    let newJob = try required(newView.send(.refresh))
    let newCall = await newProvider.take()
    try expect(newCall.input == fresh)
    oldCall.complete(initial.next(2))
    try expect(retired.currentCredential == nil && current.currentCredential == fresh && newView.state == .waiting)
    newCall.complete(fresh.next(11))
    await newJob.join()
    try expect(newView.state == .ready && current.currentCredential?.version == 11 && retired.currentCredential == nil)
    report("PASS security: late refresh after logout cannot populate a new login for the same account")

    let stalledProvider = Controlled<Credential, Credential>()
    let parent = Lifetime()
    let stopped = SessionOwner(parent: parent, initial: initial, renew: stalledProvider.run)
    let pending = Task { @MainActor in try await stopped.refresh() }
    let stall = await stalledProvider.take()
    parent.close()
    let parentOutcome = try await pending.value
    try expect(parentOutcome == .sessionClosed && stopped.currentCredential == nil)
    stall.complete(initial.next(2))
    report("PASS security: host cancellation releases external waiters before the provider returns")

    let recoveryProvider = Controlled<Credential, Credential>()
    let recovery = SessionOwner(parent: host, initial: initial, renew: recoveryProvider.run)
    let fail = Task { @MainActor in try await recovery.refresh() }
    let rejected = await recoveryProvider.take()
    rejected.fail(FakeError.rejected)
    let outcome = try await fail.value
    try expect(outcome == .failed && recovery.currentCredential == initial)
    let retry = Task { @MainActor in try await recovery.refresh() }
    let retried = await recoveryProvider.take()
    retried.complete(initial.next(2))
    let retriedOutcome = try await retry.value
    try expect(retriedOutcome == .updated && recovery.currentCredential?.version == 2)
    report("PASS security: provider failure returns a safe outcome and permits a fresh attempt")

    let payload = "synthetic-payload-marker"
    let detail = "synthetic-sdk-marker"
    let cause = "synthetic-nested-marker"
    let providerError = NSError(domain: detail, code: 1, userInfo: [NSUnderlyingErrorKey: NSError(domain: cause, code: 2)])
    let validation = WorkGroup<AuthPage, String>(label: "Validate") { _, received in
        try expect(received == payload)
        throw providerError
    }
    let diagnostic = try Workflow(owner: host, initial: AuthPage.waiting, key: { $0 }, route: { _ in 0 }, stages: [
        .waiting: [0: .work(validation, .ignoreRepeated)],
    ])
    let work = try required(diagnostic.send(payload))
    await work.join()
    let standalone = FipeTask<Void, Void>(owner: host) { _ in throw providerError }
    let independent = try required(standalone.start(()))
    await independent.join()
    try expect(diagnostic.snapshot.failures == [TaskFailure(operation: "Validate", kind: .operationFailed)])
    let snapshot = String(reflecting: diagnostic.snapshot)
    guard case .failed = standalone.state else { throw CheckFailure(line: #line) }
    let taskState = String(reflecting: standalone.state)
    try expect([payload, detail, cause].allSatisfy { !snapshot.contains($0) && !taskState.contains($0) })
    report("PASS security: snapshots expose failure labels and kinds without payload or exception text")
}

@MainActor
func verifySessionLifetimeClose() async throws {
    let host = Lifetime()
    defer { host.close() }
    let provider = Controlled<Credential, Credential>()
    let initial = Credential(account: "account", version: 1)
    let session = SessionOwner(parent: host, initial: initial, renew: provider.run)

    let waiter = Task { @MainActor in try await session.refresh() }
    let stalled = await provider.take()
    session.lifetime.close()
    let outcome = try await waiter.value
    try expect(outcome == .sessionClosed && session.currentCredential == nil)
    stalled.complete(initial.next(2))
    let afterClose = try await session.refresh()
    try expect(afterClose == .sessionClosed)
    try expect(session.currentCredential == nil && provider.count == 1)
}
