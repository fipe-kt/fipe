import { useLayoutEffect, useState, useSyncExternalStore } from "react";
import type { Workflow, WorkflowSnapshot } from "./runtime";

const noSnapshot = () => undefined;
const subscribeWhenAbsent = () => noSnapshot;

export function useWorkflowSnapshot<S, E>(workflow: Workflow<S, E>): WorkflowSnapshot<S>;
export function useWorkflowSnapshot(workflow: undefined): undefined;
export function useWorkflowSnapshot<S, E>(workflow: Workflow<S, E> | undefined): WorkflowSnapshot<S> | undefined;
export function useWorkflowSnapshot<S, E>(workflow: Workflow<S, E> | undefined): WorkflowSnapshot<S> | undefined {
    const getSnapshot = workflow?.getSnapshot ?? noSnapshot;
    return useSyncExternalStore(workflow?.subscribe ?? subscribeWhenAbsent, getSnapshot, getSnapshot);
}

export function useScreenWorkflow<S, E>(create: () => Workflow<S, E>): Workflow<S, E> | undefined {
    const [mounted, setMounted] = useState<{ create: typeof create; workflow: Workflow<S, E> }>();

    useLayoutEffect(() => {
        const workflow = create();
        setMounted({ create, workflow });
        return () => workflow.close();
    }, [create]);

    return mounted?.create === create ? mounted.workflow : undefined;
}
