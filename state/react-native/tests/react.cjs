const assert = require("node:assert/strict");
const React = require("react");
const { create } = require("react-test-renderer");
const { Session, Workflow, enter, update } = require("fipe-state");
const { useScreenWorkflow, useWorkflowSnapshot } = require("fipe-state/react");

async function main() {
    global.IS_REACT_ACT_ENVIRONMENT = true;
    assert.equal(require("fipe-state").deferred, undefined);
    assert.throws(() => require("fipe-state/dist/runtime.js"), { code: "ERR_PACKAGE_PATH_NOT_EXPORTED" });

    const model = new Workflow({ count: 0 }, () => "ready", event => event, stage => {
        stage("ready", definition => {
            definition.on("noop", () => update(state => state));
            definition.on("count", () => update(state => ({ count: state.count + 1 })));
            definition.on("reset", () => enter(state => state));
            definition.task("fail", async () => { throw new Error("provider detail"); }, "read");
        });
    });
    const initial = model.snapshot;
    assert.equal(model.getSnapshot(), initial);
    model.send("noop");
    assert.equal(model.getSnapshot(), initial);

    function Counter() {
        const { state, failures } = useWorkflowSnapshot(model);
        return React.createElement("output", null, `${state.count}:${failures.length}`);
    }

    let mounted;
    await React.act(async () => { mounted = create(React.createElement(Counter)); });
    assert.deepEqual(mounted.toJSON().children, ["0:0"]);

    await React.act(async () => { model.send("count"); });
    assert.notEqual(model.getSnapshot(), initial);
    assert.equal(model.snapshot.visit, initial.visit);
    assert.deepEqual(mounted.toJSON().children, ["1:0"]);

    await React.act(async () => { await model.send("fail").settled; });
    assert.deepEqual(mounted.toJSON().children, ["1:1"]);
    assert.deepEqual(model.snapshot.failures, [{ operation: "read", kind: "OperationFailed" }]);
    assert(Object.isFrozen(model.snapshot.failures[0]));

    await React.act(async () => { model.send("reset"); });
    assert.notEqual(model.snapshot.visit, initial.visit);
    assert.deepEqual(mounted.toJSON().children, ["1:0"]);

    await React.act(async () => { mounted.unmount(); });
    model.send("count");
    assert.equal(model.state.count, 2);
    model.close();
    console.log("PASS packaged React hook: cached snapshots render, failures invalidate, unmount preserves host");

    await checkScreenSnapshots();
    await checkScreenLifetime();
}

async function checkScreenSnapshots() {
    const makeCounter = initial => () => new Workflow(initial, () => "ready", event => event, stage => {
        stage("ready", definition => definition.on("count", () => update(value => value + 1)));
    });
    const commits = [];
    function Screen({ factory }) {
        const workflow = useScreenWorkflow(factory);
        const snapshot = useWorkflowSnapshot(workflow);
        React.useLayoutEffect(() => { commits.push(snapshot?.state); }, [snapshot]);
        return React.createElement("button", { onClick: () => workflow.send("count") }, snapshot?.state);
    }

    let renderer;
    await React.act(async () => { renderer = create(React.createElement(Screen, { factory: makeCounter(1) })); });
    assert.deepEqual(commits, [undefined, 1]);
    await React.act(async () => { renderer.root.findByType("button").props.onClick(); });
    assert.deepEqual(renderer.toJSON().children, ["2"]);
    commits.length = 0;
    await React.act(async () => { renderer.update(React.createElement(Screen, { factory: makeCounter(10) })); });
    assert.deepEqual(commits, [undefined, 10]);
    await React.act(async () => { renderer.root.findByType("button").props.onClick(); });
    assert.deepEqual(renderer.toJSON().children, ["11"]);
    await React.act(async () => { renderer.unmount(); });
    console.log("PASS packaged screen snapshots: setup, updates, replacement and resubscription");
}

async function checkScreenLifetime() {
    let completeRefresh;
    let completeLateWork;
    let refreshes = 0;
    const refresh = new Promise(resolve => { completeRefresh = resolve; });
    const lateWork = new Promise(resolve => { completeLateWork = resolve; });
    const session = new Session({ version: 1 }, async () => {
        refreshes++;
        return refresh;
    });
    const createScreen = () => new Workflow("waiting", () => "ready", event => event, stage => {
        stage("ready", definition => {
            definition.task("refresh", async (_, { signal }) => {
                const result = await session.refresh(signal);
                return update(() => result);
            });
            definition.task("lateWork", async () => {
                await lateWork;
                return update(() => "stale");
            });
        });
    }, session.signal);

    const active = new Map();
    function Screen({ name, factory }) {
        const workflow = useScreenWorkflow(factory);
        React.useLayoutEffect(() => {
            if (!workflow) return;
            active.set(name, workflow);
            return () => { active.delete(name); };
        }, [name, workflow]);
        return null;
    }
    const tree = (leftFactory, showLeft = true) => React.createElement(React.StrictMode, null,
        showLeft && React.createElement(Screen, { key: "left", name: "left", factory: leftFactory }),
        React.createElement(Screen, { key: "right", name: "right", factory: createScreen }),
    );
    let renderer;
    await React.act(async () => { renderer = create(tree(createScreen)); });
    const original = active.get("left");
    const right = active.get("right");
    assert.notEqual(original, right);
    const replacement = () => createScreen();
    await React.act(async () => { renderer.update(tree(replacement)); });
    const left = active.get("left");
    assert.notEqual(left.visit, original.visit);
    assert.equal(original.send("refresh"), undefined);
    assert.equal(active.get("right"), right);

    const leftWait = left.send("refresh");
    const rightWait = right.send("refresh");
    const staleRun = left.send("lateWork");
    assert.equal(refreshes, 1);
    await React.act(async () => { renderer.update(tree(replacement, false)); });
    await leftWait.settled;
    assert.equal(staleRun.signal.aborted, true);
    assert.equal(rightWait.signal.aborted, false);
    assert.equal(left.send("refresh"), undefined);
    assert.equal(session.signal.aborted, false);

    completeLateWork();
    completeRefresh({ version: 2 });
    await Promise.all([staleRun.settled, rightWait.settled]);
    assert.equal(left.state, "waiting");
    assert.equal(right.state, "updated");
    assert.deepEqual(session.currentCredential, { version: 2 });
    await React.act(async () => { renderer.unmount(); });
    session.close();
    console.log("PASS packaged screen hook: replacement and unmount close only the owned workflow");
}

main().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
