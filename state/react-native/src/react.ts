import { useLayoutEffect, useState, useSyncExternalStore } from "react";
import type { Workflow, WorkflowSnapshot } from "./runtime";

export function useWorkflowSnapshot<S, E>(workflow: Workflow<S, E>): WorkflowSnapshot<S> {
    return useSyncExternalStore(workflow.subscribe, workflow.getSnapshot, workflow.getSnapshot);
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
