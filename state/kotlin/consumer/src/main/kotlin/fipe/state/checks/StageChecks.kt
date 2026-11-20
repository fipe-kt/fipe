package fipe.state.checks

import fipe.state.Stage
import fipe.state.Task
import fipe.state.TaskState
import kotlinx.coroutines.test.runTest

fun main() = runTest {
    val upload = Controlled<String, String>()
    val orders = Controlled<Unit, Int>()
    Task(this, orders::run).use { order ->
        val tracking = checkNotNull(order.start(Unit))
        val update = orders.take()
        val stage = Stage(this)
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

        Stage(this).use { next ->
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
