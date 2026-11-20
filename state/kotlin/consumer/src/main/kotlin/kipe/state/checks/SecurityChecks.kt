package kipe.state.checks

import kipe.state.Change
import kipe.state.FailureKind
import kipe.state.Task
import kipe.state.TaskFailure
import kipe.state.TaskState
import kipe.state.auth.RefreshOutcome
import kipe.state.auth.SessionOwner
import kipe.state.workflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private data class Credential(val account: String, val version: Int)
private enum class AuthPage { Waiting, Ready, Retry }
private enum class AuthIntent { Refresh, Validate }
private data class Validation(val payload: String)

private fun refreshView(session: SessionOwner<Credential>) =
    workflow(AuthPage.Waiting, { it }, { event: AuthIntent -> event }) {
        stage(AuthPage.Waiting) {
            task(AuthIntent.Refresh) { _, _ ->
                val page = when (session.refresh()) {
                    RefreshOutcome.Updated -> AuthPage.Ready
                    RefreshOutcome.Failed -> AuthPage.Retry
                    RefreshOutcome.SessionClosed -> AuthPage.Waiting
                }
                Change.Enter { page }
            }
        }
        stage(AuthPage.Ready) {}
        stage(AuthPage.Retry) {}
    }.startIn(session.scope)

fun main() = runBlocking {
    val host = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
        withTimeout(10_000) {
            checkSharedRefresh(host)
            checkLogout(host)
            checkHostCancellation(host)
            checkRetry(host)
            checkSafeDiagnostics(host)
        }
    } finally {
        host.cancel()
    }
}

private suspend fun checkSharedRefresh(host: CoroutineScope) {
    val provider = Controlled<Credential, Credential>()
    val initial = Credential("same-account", 1)
    SessionOwner(host, initial, provider::run).use { session ->
        refreshView(session).use { left ->
            refreshView(session).use { right ->
                val first = checkNotNull(left.send(AuthIntent.Refresh))
                val call = provider.take()
                val second = checkNotNull(right.send(AuthIntent.Refresh))
                check(call.input == initial && !provider.hasPendingCall())
                left.close()
                first.join()
                check(first.isCancelled && !second.isCompleted)
                call.complete(initial.copy(version = 2))
                second.await()
                check(left.state == AuthPage.Waiting && right.state == AuthPage.Ready)
                check(session.currentCredential()?.version == 2)
            }
        }
    }
    println("PASS security: two workflows share one refresh and closing one cancels only its wait")
}

private suspend fun checkLogout(host: CoroutineScope) {
    val retiredProvider = Controlled<Credential, Credential>()
    val currentProvider = Controlled<Credential, Credential>()
    val initial = Credential("same-account", 1)
    SessionOwner(host, initial, retiredProvider::run).use { retired ->
        refreshView(retired).use { oldView ->
            val oldVisit = oldView.visit
            val oldJob = checkNotNull(oldView.send(AuthIntent.Refresh))
            val oldCall = retiredProvider.take()
            val outside = host.async { retired.refresh() }
            check(!retiredProvider.hasPendingCall())
            retired.close()
            check(outside.await() == RefreshOutcome.SessionClosed && retired.currentCredential() == null)
            oldJob.join()
            check(oldJob.isCancelled && oldView.visit === oldVisit && oldView.state == AuthPage.Waiting)
            check(retired.refresh() == RefreshOutcome.SessionClosed)
            check(oldView.send(AuthIntent.Refresh) == null)

            val replacement = initial.copy(version = 10)
            SessionOwner(host, replacement, currentProvider::run).use { current ->
                refreshView(current).use { newView ->
                    val newJob = checkNotNull(newView.send(AuthIntent.Refresh))
                    val newCall = currentProvider.take()
                    check(newCall.input == replacement)
                    oldCall.complete(initial.copy(version = 2))
                    check(retired.currentCredential() == null)
                    check(current.currentCredential() == replacement && newView.state == AuthPage.Waiting)
                    newCall.complete(replacement.copy(version = 11))
                    newJob.await()
                    check(newView.state == AuthPage.Ready && current.currentCredential()?.version == 11)
                }
            }
        }
    }
    println("PASS security: late refresh after logout cannot populate a new login for the same account")
}

private suspend fun checkHostCancellation(host: CoroutineScope) {
    val provider = Controlled<Credential, Credential>()
    val parent = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val initial = Credential("same-account", 1)
    SessionOwner(parent, initial, provider::run).use { session ->
        val waiting = host.async { session.refresh() }
        val stuck = provider.take()
        parent.cancel()
        check(waiting.await() == RefreshOutcome.SessionClosed)
        check(session.currentCredential() == null)
        stuck.complete(initial.copy(version = 2))
        check(session.currentCredential() == null)
    }
    println("PASS security: host cancellation releases external waiters before the provider returns")
}

private suspend fun checkRetry(host: CoroutineScope) {
    val provider = Controlled<Credential, Credential>()
    val initial = Credential("same-account", 1)
    SessionOwner(host, initial, provider::run).use { session ->
        val failed = host.async { session.refresh() }
        provider.take().fail(IllegalStateException("synthetic provider detail"))
        check(failed.await() == RefreshOutcome.Failed && session.currentCredential() == initial)
        val retried = host.async { session.refresh() }
        provider.take().complete(initial.copy(version = 2))
        check(retried.await() == RefreshOutcome.Updated && session.currentCredential()?.version == 2)
    }
    println("PASS security: provider failure returns a safe outcome and permits a fresh attempt")
}

private suspend fun checkSafeDiagnostics(host: CoroutineScope) {
    val payload = "synthetic-payload-marker"
    val sdkDetail = "synthetic-sdk-marker"
    val cause = "synthetic-nested-marker"
    val error = IllegalStateException(sdkDetail, IllegalArgumentException(cause))
    workflow(AuthPage.Waiting, { it }, { _: Validation -> AuthIntent.Validate }) {
        stage(AuthPage.Waiting) {
            task(AuthIntent.Validate, failureLabel = AuthIntent.Validate) { _, event ->
                check(event.payload == payload)
                throw error
            }
        }
    }.startIn(host).use { runner ->
        checkNotNull(runner.send(Validation(payload))).await()
        check(runner.snapshot.failures == listOf(TaskFailure("Validate", FailureKind.OperationFailed)))
        for (marker in listOf(payload, sdkDetail, cause)) {
            check(marker !in runner.snapshot.toString())
        }
    }
    Task<Unit, Unit>(host) { throw error }.use { standalone ->
        checkNotNull(standalone.start(Unit)).await()
        check(standalone.state == TaskState.Failed)
        check(sdkDetail !in standalone.state.toString() && cause !in standalone.state.toString())
    }
    println("PASS security: snapshots expose failure labels and kinds without payload or exception text")
}
