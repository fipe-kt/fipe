package kipe.state.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

enum class RefreshOutcome { Updated, Failed, SessionClosed }

class SessionOwner<C : Any>(
    parent: CoroutineScope,
    initialCredential: C,
    private val renew: suspend (C) -> C,
) : AutoCloseable {
    private class Flight(val result: CompletableDeferred<RefreshOutcome>, val worker: Job)

    private val lock = Any()
    private val owner = SupervisorJob(parent.coroutineContext[Job])
    val scope = CoroutineScope(parent.coroutineContext + owner)
    private var credential: C? = initialCredential
    private var inFlight: Flight? = null

    fun currentCredential(): C? = synchronized(lock) {
        credential.takeIf { owner.isActive }
    }

    suspend fun refresh(): RefreshOutcome {
        currentCoroutineContext().ensureActive()
        val flight = synchronized(lock) {
            val current = credential ?: return RefreshOutcome.SessionClosed
            if (!owner.isActive) return RefreshOutcome.SessionClosed
            inFlight ?: newFlightLocked(current)
        }
        flight.worker.start()
        val outcome = try {
            flight.result.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!owner.isActive) return RefreshOutcome.SessionClosed
            throw cancelled
        }
        return synchronized(lock) {
            if (credential == null || !owner.isActive) RefreshOutcome.SessionClosed else outcome
        }
    }

    private fun newFlightLocked(current: C): Flight {
        val result = CompletableDeferred<RefreshOutcome>(owner)
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            val candidate = try {
                coroutineScope { renew(current) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val outcome = synchronized(lock) {
                if (inFlight?.result !== result || credential == null || !owner.isActive) {
                    RefreshOutcome.SessionClosed
                } else {
                    inFlight = null
                    if (candidate == null) {
                        RefreshOutcome.Failed
                    } else {
                        credential = candidate
                        RefreshOutcome.Updated
                    }
                }
            }
            result.complete(outcome)
        }
        val flight = Flight(result, worker)
        inFlight = flight
        worker.invokeOnCompletion { cause ->
            if (cause != null) {
                synchronized(lock) {
                    if (inFlight === flight) inFlight = null
                }
                result.cancel()
            }
        }
        return flight
    }

    override fun close() {
        synchronized(lock) {
            if (credential == null) return
            credential = null
            inFlight = null
        }
        owner.cancel()
    }
}
