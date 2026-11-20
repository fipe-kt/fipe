package fipe.state.checks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

internal fun CoroutineScope.childOwner(): CoroutineScope =
    CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
