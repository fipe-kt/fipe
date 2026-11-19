import Foundation

@MainActor
public final class Lifetime {
    private var handlers: [UUID: @MainActor () -> Void] = [:]
    public private(set) var closed = false

    public init() {}

    func onClose(_ action: @escaping @MainActor () -> Void) -> UUID? {
        guard !closed else { return nil }
        let id = UUID()
        handlers[id] = action
        return id
    }

    func remove(_ id: UUID?) {
        if let id { handlers[id] = nil }
    }

    public func close() {
        guard !closed else { return }
        closed = true
        let actions = handlers.values
        handlers.removeAll()
        for action in actions { action() }
    }
}

public enum AdmissionPolicy: Sendable { case ignoreRepeated, replacePrevious }

public enum TaskState<Output: Sendable>: Sendable {
    case idle, running, succeeded(Output), failed, closed
}

extension TaskState: Equatable where Output: Equatable {}

@MainActor
public final class Execution {
    fileprivate var worker: Task<Void, Never>?
    public fileprivate(set) var cancelled = false
    public fileprivate(set) var completed = false

    fileprivate func cancel() {
        cancelled = true
        worker?.cancel()
    }

    public func join() async {
        await worker?.value
    }
}

@MainActor
public final class FipeTask<Input: Sendable, Output: Sendable> {
    public typealias Work = @MainActor (Input) async throws -> Output
    typealias Settle = @MainActor (Result<Output, any Error>) -> Void

    private let owner: Lifetime
    private let work: Work
    private let settle: Settle
    private var onClose: UUID?
    private var active: Execution?
    public private(set) var state: TaskState<Output> = .idle

    public convenience init(owner: Lifetime, work: @escaping Work) {
        self.init(owner: owner, work: work, settle: { _ in })
    }

    init(owner: Lifetime, work: @escaping Work, settle: @escaping Settle) {
        self.owner = owner
        self.work = work
        self.settle = settle
        onClose = owner.onClose { [weak self] in self?.close() }
        if owner.closed { state = .closed }
    }

    public func start(_ input: Input, policy: AdmissionPolicy = .ignoreRepeated) -> Execution? {
        guard !owner.closed else { return nil }
        if case .closed = state { return nil }
        if active != nil && policy == .ignoreRepeated { return nil }
        let replaced = active
        let execution = Execution()
        active = execution
        state = .running
        let work = self.work
        execution.worker = Task { @MainActor [weak self] in
            defer {
                execution.completed = true
                execution.worker = nil
            }
            guard !Task.isCancelled else { return }
            let outcome: Result<Output, any Error>
            do {
                outcome = .success(try await work(input))
            } catch {
                outcome = .failure(error)
            }
            guard let self, self.active === execution, !self.owner.closed else { return }
            self.active = nil
            if execution.cancelled || Task.isCancelled {
                self.state = .idle
                return
            }
            switch outcome {
            case let .success(value):
                self.state = .succeeded(value)
            case .failure(is CancellationError):
                execution.cancelled = true
                self.state = .idle
                return
            case .failure:
                self.state = .failed
            }
            self.settle(outcome)
        }
        replaced?.cancel()
        return execution
    }

    public func close() {
        let execution = active
        active = nil
        state = .closed
        owner.remove(onClose)
        onClose = nil
        execution?.cancel()
    }
}

@MainActor
public final class Stage {
    private let parent: Lifetime
    private let lifetime = Lifetime()
    private var onClose: UUID?
    private var tasks: [AnyObject] = []

    public init(parent: Lifetime) {
        self.parent = parent
        onClose = parent.onClose { [weak self] in self?.close() }
        if parent.closed { close() }
    }

    public func task<I: Sendable, O: Sendable>(
        _ work: @escaping FipeTask<I, O>.Work
    ) -> FipeTask<I, O> {
        task(work, settle: { _ in })
    }

    func task<I: Sendable, O: Sendable>(
        _ work: @escaping FipeTask<I, O>.Work,
        settle: @escaping FipeTask<I, O>.Settle
    ) -> FipeTask<I, O> {
        let task = FipeTask(owner: lifetime, work: work, settle: settle)
        if !lifetime.closed { tasks.append(task) }
        return task
    }

    public func close() {
        lifetime.close()
        tasks.removeAll()
        parent.remove(onClose)
        onClose = nil
    }
}

public enum FailureKind: Equatable, Sendable { case operationFailed, invalidChange }

public struct TaskFailure: Equatable, Sendable {
    public let operation: String?
    public let kind: FailureKind

    public init(operation: String?, kind: FailureKind) {
        self.operation = operation
        self.kind = kind
    }
}

@MainActor
public final class Visit {}

public enum Change<State: Sendable>: Sendable {
    case update(@MainActor @Sendable (State) -> State)
    case enter(@MainActor @Sendable (State) -> State)
}

@MainActor
public final class WorkGroup<State: Sendable, Event: Sendable> {
    let label: String?
    let work: @MainActor (State, Event) async throws -> Change<State>

    public init(label: StaticString? = nil, work: @escaping @MainActor (State, Event) async throws -> Change<State>) {
        self.label = label.map { String(describing: $0) }
        self.work = work
    }
}

public enum StageAction<State: Sendable, Event: Sendable> {
    case reduce(@MainActor (State, Event) -> Change<State>)
    case work(WorkGroup<State, Event>, AdmissionPolicy)
}

public struct WorkflowSnapshot<State: Sendable> {
    public let visit: Visit
    public let state: State
    public let failures: [TaskFailure]

    public var failure: TaskFailure? { failures.last }
}

public enum WorkflowError: Error, Equatable { case unknownStage, updateChangesStage }

@MainActor
public final class Workflow<State: Sendable, Event: Sendable, Key: Hashable, Route: Hashable> {
    private typealias Work = FipeTask<Input, Change<State>>
    private struct Input: Sendable { let state: State; let event: Event }

    private let owner: Lifetime
    private let key: (State) -> Key
    private let route: (Event) -> Route
    private let stages: [Key: [Route: StageAction<State, Event>]]
    private var stage: Stage
    private var tasks: [ObjectIdentifier: Work] = [:]
    private var failures: [(ObjectIdentifier, TaskFailure)] = []
    private var closed = false
    public private(set) var visit = Visit()
    public private(set) var state: State

    public init(
        owner: Lifetime,
        initial: State,
        key: @escaping (State) -> Key,
        route: @escaping (Event) -> Route,
        stages: [Key: [Route: StageAction<State, Event>]]
    ) throws {
        guard stages[key(initial)] != nil else { throw WorkflowError.unknownStage }
        self.owner = owner
        self.state = initial
        self.key = key
        self.route = route
        self.stages = stages
        self.stage = Stage(parent: owner)
    }

    public var snapshot: WorkflowSnapshot<State> {
        WorkflowSnapshot(visit: visit, state: state, failures: failures.map { $0.1 })
    }

    @discardableResult
    public func send(_ event: Event, visit requested: Visit? = nil) throws -> Execution? {
        guard !closed, !owner.closed, requested == nil || requested === visit,
              let action = stages[key(state)]?[route(event)] else { return nil }
        switch action {
        case let .reduce(reduce):
            try apply(reduce(state, event))
            return nil
        case let .work(group, policy):
            let id = ObjectIdentifier(group)
            let task: Work
            if let existing = tasks[id] {
                task = existing
            } else {
                let admittedVisit = visit
                task = stage.task({ input in try await group.work(input.state, input.event) }, settle: { [weak self] result in
                    guard let self, !self.closed, !self.owner.closed, self.visit === admittedVisit else { return }
                    switch result {
                    case let .success(change):
                        do { try self.apply(change) }
                        catch { self.recordFailure(id, label: group.label, kind: .invalidChange) }
                    case .failure:
                        self.recordFailure(id, label: group.label, kind: .operationFailed)
                    }
                })
                tasks[id] = task
            }
            let execution = task.start(Input(state: state, event: event), policy: policy)
            if execution != nil { failures.removeAll { $0.0 == id } }
            return execution
        }
    }

    private func recordFailure(_ id: ObjectIdentifier, label: String?, kind: FailureKind) {
        failures.removeAll { $0.0 == id }
        failures.append((id, TaskFailure(operation: label, kind: kind)))
    }

    private func apply(_ change: Change<State>) throws {
        switch change {
        case let .update(transform):
            let updated = transform(state)
            guard key(updated) == key(state) else { throw WorkflowError.updateChangesStage }
            state = updated
        case let .enter(transform):
            let updated = transform(state)
            guard stages[key(updated)] != nil else { throw WorkflowError.unknownStage }
            let retired = stage
            stage = Stage(parent: owner)
            state = updated
            visit = Visit()
            tasks.removeAll()
            failures.removeAll()
            retired.close()
        }
    }

    public func close() {
        guard !closed else { return }
        closed = true
        stage.close()
        tasks.removeAll()
    }
}

public enum RefreshOutcome: Equatable, Sendable { case updated, failed, sessionClosed }

@MainActor
public final class SessionOwner<Credential: Sendable> {
    private let renew: @MainActor (Credential) async throws -> Credential
    private let parent: Lifetime
    public let lifetime = Lifetime()
    private var onClose: UUID?
    private var credential: Credential?
    private var flight: Task<Void, Never>?
    private var waiters: [UUID: CheckedContinuation<RefreshOutcome, any Error>] = [:]

    public init(parent: Lifetime, initial: Credential, renew: @escaping @MainActor (Credential) async throws -> Credential) {
        self.parent = parent
        self.credential = initial
        self.renew = renew
        _ = lifetime.onClose { [weak self] in self?.finishClosing() }
        onClose = parent.onClose { [weak self] in self?.close() }
        if parent.closed { close() }
    }

    public var currentCredential: Credential? { lifetime.closed ? nil : credential }

    public func refresh() async throws -> RefreshOutcome {
        try Task.checkCancellation()
        let id = UUID()
        let outcome: RefreshOutcome = try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { waiter in
                guard !Task.isCancelled else { waiter.resume(throwing: CancellationError()); return }
                guard let credential, !lifetime.closed else { waiter.resume(returning: .sessionClosed); return }
                waiters[id] = waiter
                if flight == nil { start(credential) }
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.waiters.removeValue(forKey: id)?.resume(throwing: CancellationError())
            }
        }
        try Task.checkCancellation()
        return lifetime.closed ? .sessionClosed : outcome
    }

    private func start(_ current: Credential) {
        let renew = self.renew
        flight = Task { @MainActor [weak self] in
            guard !Task.isCancelled else { return }
            do {
                let updated = try await renew(current)
                guard let self, !self.lifetime.closed, !Task.isCancelled else { return }
                self.credential = updated
                self.finish(.success(.updated))
            } catch is CancellationError {
                guard let self, !self.lifetime.closed, !Task.isCancelled else { return }
                self.finish(.failure(CancellationError()))
            } catch {
                guard let self, !self.lifetime.closed, !Task.isCancelled else { return }
                self.finish(.success(.failed))
            }
        }
    }

    private func finish(_ outcome: Result<RefreshOutcome, any Error>) {
        flight = nil
        let waiting = waiters.values
        waiters.removeAll()
        for waiter in waiting { waiter.resume(with: outcome) }
    }

    public func close() {
        lifetime.close()
    }

    private func finishClosing() {
        credential = nil
        let operation = flight
        parent.remove(onClose)
        onClose = nil
        operation?.cancel()
        finish(.success(.sessionClosed))
    }
}
