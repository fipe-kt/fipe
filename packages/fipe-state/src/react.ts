import { useSyncExternalStore } from "react";
import type { Workflow, WorkflowSnapshot } from "./runtime";

export function useWorkflowSnapshot<S, E>(workflow: Workflow<S, E>): WorkflowSnapshot<S> {
    return useSyncExternalStore(workflow.subscribe, workflow.getSnapshot, workflow.getSnapshot);
}
