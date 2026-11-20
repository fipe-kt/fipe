package kipe.state.checks

import kipe.state.Stage
import kipe.state.Task
import kipe.state.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

fun main() = runBlocking {
    val host = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
        withTimeout(10_000) {
            val upload = Controlled<String, String>()
            val orders = Controlled<Unit, Int>()
            Task(host, orders::run).use { order ->
                val tracking = checkNotNull(order.start(Unit))
                val update = orders.take()
                val stage = Stage(host)
                val photoA = stage.task(upload::run)
                val photoB = stage.task(upload::run)
                val a = checkNotNull(photoA.start("A"))
                val oldA = upload.take()
                val b = checkNotNull(photoB.start("B"))
                val oldB = upload.take()
                photoA.close()
                check(photoA.state == TaskState.Closed && photoB.state == TaskState.Running)
                stage.close()
                check(photoB.state == TaskState.Closed && photoB.start("B") == null)
                check(order.state == TaskState.Running)

                Stage(host).use { next ->
                    val current = next.task(upload::run)
                    val new = checkNotNull(current.start("A"))
                    val newA = upload.take()
                    oldA.complete("old A")
                    oldB.complete("old B")
                    a.join()
                    b.join()
                    check(current.state == TaskState.Running)
                    newA.complete("new A")
                    new.await()
                    check(current.state == TaskState.Succeeded("new A"))
                }
                update.complete(42)
                tracking.await()
                check(order.state == TaskState.Succeeded(42))
                println("PASS stage: close returns before cleanup and leaves independent work running")
            }
        }
    } finally {
        host.cancel()
    }
}
