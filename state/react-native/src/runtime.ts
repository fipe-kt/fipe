export type WhileRunning = "ignoreRepeated" | "replacePrevious";
export type FailureKind = "OperationFailed" | "InvalidChange";
export type TaskFailure = Readonly<{ operation?: string; kind: FailureKind }>;
export type TaskState<T> =
    | Readonly<{ kind: "idle" | "running" | "failed" | "closed" }>
    | Readonly<{ kind: "succeeded"; value: T }>;

export class Cancelled extends Error {}

function deferred<T>() {
    let resolve!: (value: T) => void;
    let reject!: (error: unknown) => void;
    const promise = new Promise<T>((onResolve, onReject) => {
        resolve = onResolve;
        reject = onReject;
    });
    return { promise, resolve, reject };
}

export interface TaskContext {
    readonly signal: AbortSignal;
    spawn(work: (context: TaskContext) => Promise<void>): void;
}

type Result<T> = { kind: "success"; value: T } | { kind: "failed" | "cancelled" };
type Body<I, O> = (input: I, context: TaskContext) => Promise<O>;
type Retire = () => void;

async function structured<I, O>(input: I, body: Body<I, O>, authority: AbortSignal): Promise<Result<O>> {
    const controller = new AbortController();
    const cancel = () => controller.abort();
    if (authority.aborted) return { kind: "cancelled" };
    authority.addEventListener("abort", cancel, { once: true });
    const children: Promise<void>[] = [];
    let childFailed = false;
    let acceptingChildren = true;
    const context: TaskContext = {
        signal: controller.signal,
        spawn(work) {
            if (!acceptingChildren || controller.signal.aborted) return;
            const child = Promise.resolve().then(() => {
                if (!controller.signal.aborted) return work(context);
                return undefined;
            }).catch((error: unknown) => {
                if (error instanceof Cancelled) return;
                childFailed = true;
                cancel();
            });
            children.push(child);
        },
    };
    try {
        let result: Result<O>;
        try {
            result = { kind: "success", value: await body(input, context) };
        } catch (error) {
            result = { kind: error instanceof Cancelled ? "cancelled" : "failed" };
            cancel();
        }
        for (const child of children) await child;
        if (authority.aborted) return { kind: "cancelled" };
        return childFailed ? { kind: "failed" } : result;
    } finally {
        acceptingChildren = false;
        authority.removeEventListener("abort", cancel);
    }
}

export interface Run {
    readonly signal: AbortSignal;
    readonly settled: Promise<void>;
    readonly completed: boolean;
}

class Execution implements Run {
    readonly controller = new AbortController();
    private readonly completion = deferred<void>();
    readonly settled = this.completion.promise;
    completed = false;

    get signal() { return this.controller.signal; }

    finish() {
        this.completed = true;
        this.completion.resolve(undefined);
    }
}

export class Task<I, O> {
    private current?: Execution;
    private currentState: TaskState<O> = { kind: "idle" };

    constructor(body: Body<I, O>);
    /** @internal */
    constructor(body: Body<I, O>, commit: (result: Result<O>) => Retire[]);
    constructor(private readonly body: Body<I, O>, private readonly commit: (result: Result<O>) => Retire[] = () => []) {}

    get state(): TaskState<O> { return { ...this.currentState }; }

    start(input: I, policy: WhileRunning = "ignoreRepeated"): Run | undefined {
        return this.reserve(input, policy)?.();
    }

    /** @internal */
    reserve(input: I, policy: WhileRunning): (() => Run) | undefined {
        if (this.currentState.kind === "closed") return;
        const prior = this.current;
        if (prior && policy === "ignoreRepeated") return;
        const next = new Execution();
        this.current = next;
        this.currentState = { kind: "running" };
        return () => {
            prior?.controller.abort();
            if (this.current === next) {
                void this.execute(next, input);
            } else {
                next.finish();
            }
            return next;
        };
    }

    private async execute(execution: Execution, input: I) {
        try {
            const result = await structured(input, this.body, execution.signal);
            if (this.current !== execution || execution.signal.aborted) return;
            this.current = undefined;
            switch (result.kind) {
                case "success": this.currentState = { kind: "succeeded", value: result.value }; break;
                case "failed": this.currentState = { kind: "failed" }; break;
                case "cancelled": this.currentState = { kind: "idle" }; break;
            }
            for (const retire of this.commit(result)) retire();
        } finally {
            execution.finish();
        }
    }

    /** @internal */
    detach(): Retire | undefined {
        const prior = this.current;
        this.current = undefined;
        this.currentState = { kind: "closed" };
        return prior && (() => prior.controller.abort());
    }

    close() { this.detach()?.(); }
}

export class Stage {
    private readonly detachTasks: Array<() => Retire | undefined> = [];
    private closed = false;

    task<I, O>(body: Body<I, O>): Task<I, O>;
    /** @internal */
    task<I, O>(body: Body<I, O>, commit: (result: Result<O>) => Retire[]): Task<I, O>;
    task<I, O>(body: Body<I, O>, commit?: (result: Result<O>) => Retire[]): Task<I, O> {
        if (this.closed) throw new Error("StageClosed");
        const task = commit ? new Task(body, commit) : new Task(body);
        this.detachTasks.push(() => task.detach());
        return task;
    }

    /** @internal */
    detach(): Retire[] {
        this.closed = true;
        return this.detachTasks.splice(0).flatMap(detach => {
            const retire = detach();
            return retire ? [retire] : [];
        });
    }

    close() { for (const retire of this.detach()) retire(); }
}

export type Change<S> = Readonly<{ kind: "update" | "enter"; apply: (state: S) => S }>;
export const update = <S>(apply: (state: S) => S): Change<S> => ({ kind: "update", apply });
export const enter = <S>(apply: (state: S) => S): Change<S> => ({ kind: "enter", apply });

const visitBrand = Symbol();
export class Visit { readonly [visitBrand] = undefined; }

type Input<S, E> = { readonly state: S; readonly event: E };
type Group<S, E> = {
    readonly operation?: string;
    readonly body: Body<Input<S, E>, Change<S>>;
};
type Action<S, E> =
    | { kind: "reduce"; reduce: (input: Input<S, E>) => Change<S> }
    | { kind: "run"; group: Group<S, E>; policy: WhileRunning };

export interface StageDefinition<S, E> {
    on(route: string, reduce: (input: Input<S, E>) => Change<S>): void;
    task(routes: string | ReadonlyMap<string, WhileRunning>, body: Body<Input<S, E>, Change<S>>,
        operation?: string): void;
}

type Definition<S, E> = {
    readonly routes: ReadonlyMap<string, Action<S, E>>;
    readonly groups: readonly Group<S, E>[];
};
type VisitState<S, E> = {
    readonly token: Visit;
    readonly stage: Stage;
    readonly key: string;
    readonly definition: Definition<S, E>;
    readonly tasks: ReadonlyMap<Group<S, E>, Task<Input<S, E>, Change<S>>>;
};

export type WorkflowSnapshot<S> = Readonly<{
    visit: Visit;
    state: S;
    failures: readonly TaskFailure[];
}>;

export class Workflow<S, E> {
    private readonly definitions = new Map<string, Definition<S, E>>();
    private readonly failures = new Map<Group<S, E>, TaskFailure>();
    private current: VisitState<S, E>;
    private closed = false;
    private currentSnapshot: WorkflowSnapshot<S>;
    private readonly listeners = new Set<() => void>();
    private notificationScheduled = false;
    private readonly closeWithOwner = () => this.close();

    constructor(
        private currentState: S,
        private readonly stageOf: (state: S) => string,
        private readonly routeOf: (event: E) => string,
        define: (stage: (key: string, build: (definition: StageDefinition<S, E>) => void) => void) => void,
        private readonly owner?: AbortSignal,
    ) {
        define((key, build) => {
            const routes = new Map<string, Action<S, E>>();
            const groups: Group<S, E>[] = [];
            const register = (route: string, action: Action<S, E>) => {
                if (routes.has(route)) throw new Error("DuplicateRoute");
                routes.set(route, action);
            };
            if (this.definitions.has(key)) throw new Error("DuplicateStage");
            build({
                on: (route, reduce) => register(route, { kind: "reduce", reduce }),
                task: (keys, body, operation) => {
                    const policies = typeof keys === "string" ? new Map([[keys, "ignoreRepeated" as const]]) : new Map(keys);
                    if (policies.size === 0) throw new Error("EmptyTask");
                    const group = { body, operation };
                    groups.push(group);
                    for (const [route, policy] of policies) register(route, { kind: "run", group, policy });
                },
            });
            this.definitions.set(key, { routes: new Map(routes), groups: [...groups] });
        });
        this.current = this.newVisit(stageOf(currentState));
        this.currentSnapshot = Object.freeze({
            visit: this.visit,
            state: this.state,
            failures: Object.freeze([]),
        });
        if (owner?.aborted) this.close();
        else owner?.addEventListener("abort", this.closeWithOwner, { once: true });
    }

    get visit(): Visit { return this.current.token; }
    get state(): S { return this.currentState; }
    get snapshot(): WorkflowSnapshot<S> { return this.currentSnapshot; }

    readonly getSnapshot = () => this.currentSnapshot;

    readonly subscribe = (listener: () => void): (() => void) => {
        this.listeners.add(listener);
        return () => { this.listeners.delete(listener); };
    };

    send(event: E, visit?: Visit): Run | undefined {
        if (this.closed || this.owner?.aborted || (visit !== undefined && visit !== this.current.token)) return;
        const action = this.current.definition.routes.get(this.routeOf(event));
        if (!action) return;
        const input = { state: this.currentState, event };
        if (action.kind === "reduce") {
            for (const retire of this.apply(action.reduce(input))) retire();
            return;
        }
        const start = this.current.tasks.get(action.group)?.reserve(input, action.policy);
        if (!start) return;
        if (this.failures.delete(action.group)) this.publishSnapshot();
        return start();
    }

    close() {
        if (this.closed) return;
        this.closed = true;
        this.owner?.removeEventListener("abort", this.closeWithOwner);
        this.current.stage.close();
    }

    private newVisit(key: string): VisitState<S, E> {
        const definition = this.definitions.get(key);
        if (!definition) throw new Error("UndefinedStage");
        const stage = new Stage();
        const tasks = new Map(definition.groups.map(group => [group, stage.task(group.body, result => {
            if (this.closed || this.owner?.aborted || result.kind === "cancelled") return [];
            if (result.kind === "success") {
                try {
                    return this.apply(result.value);
                } catch {
                    this.recordFailure(group, "InvalidChange");
                }
            } else {
                this.recordFailure(group, "OperationFailed");
            }
            return [];
        })]));
        return { token: new Visit(), stage, key, definition, tasks };
    }

    private apply(change: Change<S>): Retire[] {
        const next = change.apply(this.currentState);
        const key = this.stageOf(next);
        if (change.kind === "update" && key !== this.current.key) throw new Error("InvalidChange");
        const retire: Retire[] = [];
        if (change.kind === "enter") {
            const newVisit = this.newVisit(key);
            retire.push(...this.current.stage.detach());
            this.current = newVisit;
            this.failures.clear();
        }
        this.currentState = next;
        this.publishSnapshot();
        return retire;
    }

    private recordFailure(group: Group<S, E>, kind: FailureKind) {
        this.failures.set(group, Object.freeze({ operation: group.operation, kind }));
        this.publishSnapshot();
    }

    private publishSnapshot() {
        const previous = this.currentSnapshot;
        const failures = [...this.failures.values()];
        if (previous.visit === this.visit && Object.is(previous.state, this.state) &&
            failures.length === previous.failures.length &&
            failures.every((failure, index) => failure === previous.failures[index])) return;
        this.currentSnapshot = Object.freeze({
            visit: this.visit,
            state: this.state,
            failures: Object.freeze(failures),
        });

        if (this.notificationScheduled || !this.listeners.size) return;
        this.notificationScheduled = true;
        // Let admission and cancellation finish before observers can send more events.
        queueMicrotask(() => {
            this.notificationScheduled = false;
            for (const listener of [...this.listeners]) listener();
        });
    }
}

export type RefreshOutcome = "updated" | "failed" | "sessionClosed";
type Wake = (result?: RefreshOutcome) => void;

class Flight {
    private readonly waiters = new Set<Wake>();

    wait(signal?: AbortSignal): Promise<RefreshOutcome> {
        if (signal?.aborted) return Promise.reject(new Cancelled());
        return new Promise((resolve, reject) => {
            const abort = () => wake();
            const wake: Wake = result => {
                this.waiters.delete(wake);
                signal?.removeEventListener("abort", abort);
                if (result === undefined) reject(new Cancelled());
                else resolve(result);
            };
            this.waiters.add(wake);
            signal?.addEventListener("abort", abort, { once: true });
        });
    }

    settle(result?: RefreshOutcome) {
        for (const wake of this.waiters) wake(result);
    }
}

export class Session<C extends object> {
    private credential?: C;
    private readonly controller = new AbortController();
    private flight?: Flight;
    private readonly closeWithHost = () => this.close();

    constructor(credential: C, private readonly renew: Body<C, C>, private readonly host?: AbortSignal) {
        this.credential = credential;
        if (host?.aborted) this.close();
        else host?.addEventListener("abort", this.closeWithHost, { once: true });
    }

    get signal() { return this.controller.signal; }
    get currentCredential() { return this.signal.aborted || this.host?.aborted ? undefined : this.credential; }

    async refresh(waiter?: AbortSignal): Promise<RefreshOutcome> {
        if (waiter?.aborted) throw new Cancelled();
        const credential = this.currentCredential;
        if (credential === undefined) return "sessionClosed";
        const current = this.flight;
        const flight = current ?? new Flight();
        const pending = flight.wait(waiter);
        if (!current) {
            this.flight = flight;
            void this.startRefresh(flight, credential);
        }
        const result = await pending;
        if (waiter?.aborted) throw new Cancelled();
        return this.currentCredential === undefined ? "sessionClosed" : result;
    }

    private async startRefresh(flight: Flight, credential: C) {
        const result = await structured(credential, this.renew, this.signal);
        if (this.flight !== flight || this.currentCredential === undefined) return;
        this.flight = undefined;
        if (result.kind === "success") {
            this.credential = result.value;
            flight.settle("updated");
        } else if (result.kind === "failed") {
            flight.settle("failed");
        } else {
            flight.settle();
        }
    }

    close() {
        if (this.signal.aborted) return;
        const flight = this.flight;
        this.credential = undefined;
        this.flight = undefined;
        this.host?.removeEventListener("abort", this.closeWithHost);
        this.controller.abort();
        flight?.settle("sessionClosed");
    }
}
