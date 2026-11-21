import { Cancelled, enter, Session, Stage, Task, update, Workflow, type Run, type WhileRunning } from "fipe-state";
import { useScreenWorkflow, useWorkflowSnapshot } from "fipe-state/react";

export function useSnapshotTypes(model: Workflow<number, string>, create: () => typeof model) {
    const count: number = useWorkflowSnapshot(model).state;
    const missing: undefined = useWorkflowSnapshot(undefined);
    const screen = useWorkflowSnapshot(useScreenWorkflow(create));
    const beforeSetup: typeof screen = undefined;
    const screenCount: number | undefined = screen?.state;
    return { count, missing, beforeSetup, screenCount };
}

type Report = (line: string) => void;

function deferred<T>() {
    let resolve!: (value: T) => void;
    let reject!: (error: unknown) => void;
    const promise = new Promise<T>((success, failure) => {
        resolve = success;
        reject = failure;
    });
    return { promise, resolve, reject };
}

function check(condition: unknown, label = "contract assertion"): asserts condition {
    if (!condition) throw new Error(label);
}

const matches = (actual: unknown, expected: unknown) => actual === expected;

function admitted(run: Run | undefined): Run {
    check(run, "expected an admitted execution");
    return run;
}

class Controlled<I, O> {
    private readonly queue: Array<{ input: I; complete: (output: O) => void; fail: (error: Error) => void }> = [];
    private next?: () => void;

    readonly run = (input: I) => {
        const gate = deferred<O>();
        this.queue.push({ input, complete: gate.resolve, fail: gate.reject });
        this.next?.();
        this.next = undefined;
        return gate.promise;
    };

    get pending() { return this.queue.length; }

    async take() {
        if (this.pending === 0) await new Promise<void>(resolve => { this.next = resolve; });
        const call = this.queue.shift();
        check(call, "expected a provider call");
        return call;
    }
}

async function taskChecks(report: Report) {
    const search = new Controlled<string, string>();
    const task = new Task(search.run);
    check(task.state.kind === "idle" && !search.pending);
    const first = admitted(task.start("A"));
    const oldA = await search.take();
    check(oldA.input === "A");
    check(task.start("A") === undefined && !search.pending);
    const middle = admitted(task.start("B", "replacePrevious"));
    const b = await search.take();
    const last = admitted(task.start("A", "replacePrevious"));
    const newA = await search.take();
    check(b.input === "B" && newA.input === "A");
    oldA.complete("old A");
    await first.settled;
    check(matches(task.state.kind, "running"));
    newA.complete("new A");
    await last.settled;
    b.fail(new Error("late B failure"));
    await middle.settled;
    check(JSON.stringify(task.state) === JSON.stringify({ kind: "succeeded", value: "new A" }));
    report("PASS task: replace A → B → A and ignore late success and failure");

    const pending = admitted(task.start("C"));
    (await search.take()).fail(new Cancelled());
    await pending.settled;
    check(task.state.kind === "idle");
    const retry = admitted(task.start("C"));
    (await search.take()).complete("retry");
    await retry.settled;
    check(JSON.stringify(task.state) === JSON.stringify({ kind: "succeeded", value: "retry" }));
    report("PASS task: cancellation releases the slot");

    const children = new Controlled<void, void>();
    const parent = new Task(async (_: void, context) => {
        context.spawn(() => children.run(undefined));
        return "parent";
    });
    const work = admitted(parent.start(undefined));
    const child = await children.take();
    check(parent.start(undefined) === undefined && parent.state.kind === "running" && !work.completed);
    child.complete(undefined);
    await work.settled;
    check(JSON.stringify(parent.state) === JSON.stringify({ kind: "succeeded", value: "parent" }));
    report("PASS task: completion waits for structured child work");
}

async function stageChecks(report: Report) {
    const upload = new Controlled<string, string>();
    const orders = new Controlled<void, number>();
    const order = new Task(orders.run);
    const tracking = admitted(order.start(undefined));
    const orderUpdate = await orders.take();
    const stage = new Stage();
    const photoA = stage.task(upload.run);
    const photoB = stage.task(upload.run);
    const a = admitted(photoA.start("A"));
    const oldA = await upload.take();
    const b = admitted(photoB.start("B"));
    const oldB = await upload.take();
    photoA.close();
    check(photoA.state.kind === "closed" && photoB.state.kind === "running");
    stage.close();
    check(matches(photoB.state.kind, "closed") && photoB.start("B") === undefined);
    check(!b.completed && order.state.kind === "running");
    const next = new Stage();
    const current = next.task(upload.run);
    const active = admitted(current.start("A"));
    const newA = await upload.take();
    oldA.complete("old A");
    oldB.complete("old B");
    await Promise.all([a.settled, b.settled]);
    check(current.state.kind === "running");
    newA.complete("new A");
    await active.settled;
    check(JSON.stringify(current.state) === JSON.stringify({ kind: "succeeded", value: "new A" }));
    orderUpdate.complete(42);
    await tracking.settled;
    check(JSON.stringify(order.state) === JSON.stringify({ kind: "succeeded", value: 42 }));
    report("PASS stage: close returns before cleanup and leaves independent work running");
}

type SignupPage = "email" | "verify" | "terms";
type SignupEvent = "sendCode" | "confirm" | "resend" | "back";

async function workflowChecks(report: Report) {
    const auth = new Controlled<SignupEvent, SignupPage>();
    const race: ReadonlyMap<string, WhileRunning> = new Map([["confirm", "ignoreRepeated"], ["resend", "replacePrevious"]]);
    const signup = new Workflow<SignupPage, SignupEvent>("email", s => s, e => e, stage => {
        stage("email", def => def.on("sendCode", () => enter<SignupPage>(() => "verify")));
        stage("verify", def => {
            def.on("back", () => enter<SignupPage>(() => "email"));
            def.task(race, async ({ event }) => {
                const next = await auth.run(event);
                return enter(() => next);
            });
        });
        stage("terms", () => {});
    });
    check(signup.send("confirm") === undefined && !auth.pending);
    signup.send("sendCode");
    const first = signup.visit;
    check(signup.state === "verify" && !auth.pending);
    const checking = admitted(signup.send("confirm", first));
    const confirmation = await auth.take();
    check(confirmation.input === "confirm" && signup.send("confirm", first) === undefined && !auth.pending);
    const resending = admitted(signup.send("resend", first));
    const resend = await auth.take();
    check(resend.input === "resend");
    resend.fail(new Error("resend failed"));
    await resending.settled;
    confirmation.complete("terms");
    await checking.settled;
    check(signup.visit === first && signup.snapshot.failures[0]?.kind === "OperationFailed");
    report("PASS workflow: a failed resend survives a late successful confirmation");

    const old = admitted(signup.send("confirm", first));
    const oldConfirmation = await auth.take();
    signup.send("back", first);
    check(signup.snapshot.state === "email");
    signup.send("sendCode");
    const second = signup.visit;
    check(second !== first && matches(signup.snapshot.state, "verify"));
    check(signup.send("confirm", first) === undefined && !auth.pending);
    signup.send("back", first);
    check(signup.visit === second);
    oldConfirmation.complete("terms");
    await old.settled;
    check(signup.visit === second && signup.snapshot.failures.length === 0);
    const current = admitted(signup.send("confirm", second));
    (await auth.take()).complete("terms");
    await current.settled;
    check(matches(signup.snapshot.state, "terms"));
    report("PASS workflow: re-entry rejects events and results from the first visit");
}

type Editor = Readonly<{ page: "editing" | "closed"; color: string; opacity: number; name: string }>;
type Edit =
    | { key: "setColor"; color: string }
    | { key: "opacity"; value: number }
    | { key: "setName"; name: string }
    | { key: "refresh" | "close" | "invalid" };

async function editorChecks(report: Report) {
    const colors = new Controlled<string, string>();
    const opacity = new Controlled<number, number>();
    const editor = new Workflow<Editor, Edit>({ page: "editing", color: "blue", opacity: 100, name: "Layer" },
        s => s.page, e => e.key, stage => {
            stage("editing", def => {
                def.task("setColor", async ({ event }) => {
                    check(event.key === "setColor");
                    const color = await colors.run(event.color);
                    return update(s => ({ ...s, color }));
                }, "SetColor");
                def.task("opacity", async ({ event }) => {
                    check(event.key === "opacity");
                    const value = await opacity.run(event.value);
                    return update(s => ({ ...s, opacity: value }));
                });
                def.on("setName", ({ event }) => {
                    check(event.key === "setName");
                    return update(s => ({ ...s, name: event.name }));
                });
                def.on("refresh", () => enter(s => s));
                def.on("close", () => enter(s => ({ ...s, page: "closed" })));
                def.on("invalid", () => update(s => ({ ...s, page: "closed" })));
            });
            stage("closed", () => {});
        });
    const visit = editor.visit;
    const colorJob = admitted(editor.send({ key: "setColor", color: "red" }, visit));
    const colorCall = await colors.take();
    const opacityJob = admitted(editor.send({ key: "opacity", value: 70 }, visit));
    const opacityCall = await opacity.take();
    check(colorCall.input === "red" && opacityCall.input === 70);
    editor.send({ key: "setName", name: "Logo" }, visit);
    colorCall.complete("red");
    await colorJob.settled;
    check(editor.snapshot.state.color === "red" && editor.snapshot.state.name === "Logo"
        && editor.visit === visit && !opacityJob.signal.aborted);
    opacityCall.complete(70);
    await opacityJob.settled;
    check(editor.snapshot.state.opacity === 70 && editor.visit === visit);
    report("PASS editor: independent completions and typed input preserve one visit");

    const failing = admitted(editor.send({ key: "setColor", color: "pink" }, visit));
    const pendingColor = await colors.take();
    const independent = admitted(editor.send({ key: "opacity", value: 40 }, visit));
    const pendingOpacity = await opacity.take();
    pendingColor.fail(new Error("color rejected"));
    await failing.settled;
    pendingOpacity.complete(40);
    await independent.settled;
    check(matches(editor.snapshot.state.opacity, 40) && editor.snapshot.failures[0]?.operation === "SetColor"
        && editor.snapshot.failures[0]?.kind === "OperationFailed");
    report("PASS editor: an unrelated successful task preserves the color error");

    const before = editor.snapshot.state;
    let rejected = false;
    try { editor.send({ key: "invalid" }, visit); } catch { rejected = true; }
    check(rejected && editor.snapshot.state === before && editor.visit === visit);
    const old = admitted(editor.send({ key: "setColor", color: "yellow" }, visit));
    const late = await colors.take();
    editor.send({ key: "refresh" }, visit);
    check(editor.visit !== visit && editor.snapshot.state === before && !editor.snapshot.failures.length);
    late.complete("yellow");
    await old.settled;
    check(old.signal.aborted && editor.snapshot.state === before);
    editor.send({ key: "close" }, visit);
    check(editor.snapshot.state.page === "editing");
    editor.send({ key: "close" }, editor.visit);
    check(matches(editor.snapshot.state.page, "closed"));
    report("PASS editor: explicit re-entry revokes the old visit even at the same stage");

    const ingest = new Controlled<string, void>();
    const task = new Task(ingest.run);
    const first = admitted(task.start("first"));
    const oldCall = await ingest.take();
    check(task.start("second") === undefined && !ingest.pending);
    const second = admitted(task.start("second", "replacePrevious"));
    const newer = await ingest.take();
    check(oldCall.input === "first" && newer.input === "second" && first.signal.aborted && !first.completed);
    oldCall.complete(undefined);
    newer.complete(undefined);
    await Promise.all([first.settled, second.settled]);
    report("PASS scheduling boundary: ignore discards work and replacement allows physical overlap");
}

type Credential = Readonly<{ account: string; version: number }>;
type AuthPage = "waiting" | "ready" | "retry";

function refreshView(session: Session<Credential>) {
    return new Workflow<AuthPage, "refresh">("waiting", s => s, e => e, stage => {
        stage("waiting", def => def.task("refresh", async (_, context) => {
            const outcome = await session.refresh(context.signal);
            return enter(() => outcome === "updated" ? "ready" : outcome === "failed" ? "retry" : "waiting");
        }));
        stage("ready", () => {});
        stage("retry", () => {});
    }, session.signal);
}

async function securityChecks(report: Report) {
    const provider = new Controlled<Credential, Credential>();
    const initial = { account: "same-account", version: 1 };
    const session = new Session(initial, provider.run);
    const left = refreshView(session);
    const right = refreshView(session);
    const first = admitted(left.send("refresh"));
    const call = await provider.take();
    const second = admitted(right.send("refresh"));
    check(call.input === initial && !provider.pending);
    left.close();
    await first.settled;
    check(first.signal.aborted && !second.completed);
    call.complete({ ...initial, version: 2 });
    await second.settled;
    check(left.state === "waiting" && right.state === "ready" && session.currentCredential?.version === 2);
    session.close();
    report("PASS security: two workflows share one refresh and closing one cancels only its wait");

    const retired = new Session(initial, provider.run);
    const oldView = refreshView(retired);
    const oldVisit = oldView.visit;
    const oldJob = admitted(oldView.send("refresh"));
    const oldCall = await provider.take();
    const outside = retired.refresh();
    check(!provider.pending);
    retired.close();
    check(await outside === "sessionClosed" && retired.currentCredential === undefined);
    await oldJob.settled;
    check(oldJob.signal.aborted && oldView.visit === oldVisit && oldView.state === "waiting");
    check(await retired.refresh() === "sessionClosed" && oldView.send("refresh") === undefined);
    const replacement = { ...initial, version: 10 };
    const currentProvider = new Controlled<Credential, Credential>();
    const current = new Session(replacement, currentProvider.run);
    const newView = refreshView(current);
    const newJob = admitted(newView.send("refresh"));
    const newCall = await currentProvider.take();
    check(newCall.input === replacement);
    oldCall.complete({ ...initial, version: 2 });
    await Promise.resolve();
    await Promise.resolve();
    check(retired.currentCredential === undefined && current.currentCredential === replacement && newView.state === "waiting");
    newCall.complete({ ...replacement, version: 11 });
    await newJob.settled;
    check(newView.snapshot.state === "ready" && current.currentCredential?.version === 11);
    current.close();
    report("PASS security: late refresh after logout cannot populate a new login for the same account");

    const parent = new AbortController();
    const abandoned = new Session(initial, provider.run, parent.signal);
    const waiting = abandoned.refresh();
    const stuck = await provider.take();
    parent.abort();
    check(await waiting === "sessionClosed" && abandoned.currentCredential === undefined);
    stuck.complete({ ...initial, version: 2 });
    await Promise.resolve();
    check(abandoned.currentCredential === undefined);
    report("PASS security: host cancellation releases external waiters before the provider returns");

    const retry = new Session(initial, provider.run);
    const failed = retry.refresh();
    (await provider.take()).fail(new Error("synthetic provider detail"));
    check(await failed === "failed" && retry.currentCredential === initial);
    const retried = retry.refresh();
    (await provider.take()).complete({ ...initial, version: 2 });
    check(await retried === "updated" && retry.currentCredential?.version === 2);
    retry.close();
    report("PASS security: provider failure returns a safe outcome and permits a fresh attempt");

    const markers = ["synthetic-payload-marker", "synthetic-sdk-marker", "synthetic-nested-marker"];
    const error = Object.assign(new Error(markers[1]), { cause: new Error(markers[2]) });
    const runner = new Workflow<"waiting", { payload: string }>("waiting", s => s, () => "validate",
        stage => stage("waiting", def => def.task("validate", async ({ event }) => {
            check(event.payload === markers[0]);
            throw error;
        }, "Validate")));
    await admitted(runner.send({ payload: markers[0] })).settled;
    check(JSON.stringify(runner.snapshot.failures) ===
        JSON.stringify([{ operation: "Validate", kind: "OperationFailed" }]));
    check(markers.every(marker => !JSON.stringify(runner.snapshot).includes(marker)));
    const standalone = new Task<void, void>(async () => { throw error; });
    await admitted(standalone.start(undefined)).settled;
    check(JSON.stringify(standalone.state) === '{"kind":"failed"}');
    report("PASS security: snapshots expose failure labels and kinds without payload or exception text");
}

async function abortReentry(report: Report) {
    const source = new Controlled<string, string>();
    let reentered: Run | undefined;
    const task = new Task<string, string>((input, context) => {
        if (input === "old") context.signal.addEventListener("abort", () => {
            reentered = task.start("third", "replacePrevious");
        }, { once: true });
        return source.run(input);
    });
    const first = admitted(task.start("old"));
    const old = await source.take();
    const overtaken = admitted(task.start("second", "replacePrevious"));
    const third = await source.take();
    check(third.input === "third" && !source.pending && overtaken.signal.aborted);
    third.complete("winner");
    old.complete("old");
    await Promise.all([first.settled, overtaken.settled, admitted(reentered).settled]);
    check(JSON.stringify(task.state) === '{"kind":"succeeded","value":"winner"}');
    report("PASS rn: synchronous abort reentry cannot start or commit an overtaken execution");
}

export async function verify(report: Report): Promise<void> {
    await taskChecks(report);
    await stageChecks(report);
    await workflowChecks(report);
    await editorChecks(report);
    await securityChecks(report);
    await abortReentry(report);
}
