# Fipe State for React Native

Use `fipe-state` to coordinate asynchronous work with the lifetime of a screen or session. A task has an execution identity, and each stage entry has a fresh visit. Task completion checks both before committing a workflow change.

## Install the beta

Install the npm tarball from the Fipe release.

```sh
npm install ./fipe-state-1.0.0-beta.2.tgz
```

The core entry has no React dependency. The `fipe-state/react` entry requires React 18 or later. The mobile conformance runs use React Native 0.88.1 with Hermes.

## Read workflow state in a React component

```tsx
import { View, Text } from "react-native";
import { Workflow, update } from "fipe-state";
import { useWorkflowSnapshot } from "fipe-state/react";

const counter = new Workflow<number, "increment">(0, () => "count", event => event, stage => {
    stage("count", definition => {
        definition.on("increment", () => update(value => value + 1));
    });
});

export function Counter() {
    const { state } = useWorkflowSnapshot(counter);
    return <View><Text onPress={() => counter.send("increment")}>{state}</Text></View>;
}
```

Use immutable domain state. `update` applies a change to the latest state and keeps the current visit. `enter` applies a change and opens a fresh visit, even when the stage key stays the same. Pass the captured visit as the second argument to `send` when a callback belongs to a specific visit.

The hook subscribes to the workflow during rendering. The host owns the workflow and closes it at the end of its lifetime. `TaskContext.spawn` registers child work that the task waits for. A task's `signal` requests cancellation where the operation supports it.

## Own a workflow in a screen

The beta.2 package exports `useScreenWorkflow` from `fipe-state/react`. Pass a stable factory that returns a fresh workflow each time React sets up the screen.

```tsx
import { useScreenWorkflow } from "fipe-state/react";

export function EditorScreen() {
    const editor = useScreenWorkflow(createEditorWorkflow);
    return editor ? <Editor workflow={editor} /> : null;
}
```

The hook returns `undefined` before setup or while replacing the workflow. A factory declared at module scope is stable. If the factory captures a document ID, memoize it with `useCallback` and include the ID in its dependencies. Changing the factory or removing the screen closes that screen's workflow. A screen that remains mounted while offscreen keeps its workflow.

After beta.2, you can read the screen snapshot in the same component. Both hooks return `undefined` before setup and while the factory changes. A caller that passes a mounted workflow still gets a definite snapshot.

```tsx
const editor = useScreenWorkflow(createEditorWorkflow);
const snapshot = useWorkflowSnapshot(editor);
```

### Pause a mounted screen when it loses focus

For a preview that should only fetch while visible, pass the navigation focus flag to the screen. Subscribe to React Native's `AppState` in a layout effect. This example assumes the preview has a `load` task and a `pause` handler defined as `preview.on("pause", () => enter(state => state))`. Entering a new visit cancels the previous task and keeps the last displayed value. A late result from that task cannot update the new visit.

```tsx
useLayoutEffect(() => {
    if (!workflow || !focused) return;
    let foreground = false;
    const sync = (next: boolean) => {
        if (next === foreground) return;
        foreground = next;
        workflow.send(next ? "load" : "pause");
    };
    const subscription = AppState.addEventListener("change", state => sync(state === "active"));
    sync(AppState.currentState === "active");
    return () => {
        subscription.remove();
        if (foreground) workflow.send("pause");
    };
}, [focused, workflow]);
```

Import `AppState` from `react-native`, and `useLayoutEffect` from `react`. The first active notification starts a load. Repeated active notifications leave it running. Regaining focus while the app is active starts a fresh load.

Give a shared session its own owner and close it at logout. The `Editor` component can read the screen workflow with `useWorkflowSnapshot`. The same subscription hook can read a workflow owned by the session.

`Session` shares one in-flight refresh for each login instance and configured provider call. Component waits can cancel independently. Keep credentials and raw provider errors at the authentication boundary and use safe failure labels.

## Build and check an installed artifact

Run `npm ci` and `npm test`. The check builds the package, packs it, and installs the tarball into a temporary consumer. It runs the state, ownership, security, and React subscription checks from there.
