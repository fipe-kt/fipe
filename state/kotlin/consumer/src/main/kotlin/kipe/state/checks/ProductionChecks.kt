package kipe.state.checks

import kipe.state.Change
import kipe.state.FailureKind
import kipe.state.Task
import kipe.state.WhileRunning
import kipe.state.workflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private enum class Page { Editing, Closed }
private enum class IntentKey { SetColor, AgreeOpacity, SetName, Refresh, Close, InvalidLocalEdit }

private sealed interface Intent {
    val key: IntentKey

    data class SetColor(val color: String) : Intent { override val key = IntentKey.SetColor }
    data class AgreeOpacity(val value: Int) : Intent { override val key = IntentKey.AgreeOpacity }
    data class SetName(val name: String) : Intent { override val key = IntentKey.SetName }
    data object Refresh : Intent { override val key = IntentKey.Refresh }
    data object Close : Intent { override val key = IntentKey.Close }
    data object InvalidLocalEdit : Intent { override val key = IntentKey.InvalidLocalEdit }
}

private data class Editor(
    val page: Page = Page.Editing,
    val color: String = "blue",
    val opacity: Int = 100,
    val name: String = "Layer",
)

fun main() = runBlocking {
    val host = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    try {
        withTimeout(10_000) {
            val colors = Controlled<String, String>()
            val opacity = Controlled<Int, Int>()
            workflow(host, Editor(), Editor::page, Intent::key) {
                stage(Page.Editing) {
                    task(IntentKey.SetColor, failureLabel = IntentKey.SetColor) { _, intent ->
                        val color = colors.run((intent as Intent.SetColor).color)
                        Change.Update { current -> current.copy(color = color) }
                    }
                    task(IntentKey.AgreeOpacity) { _, intent ->
                        val value = opacity.run((intent as Intent.AgreeOpacity).value)
                        Change.Update { current -> current.copy(opacity = value) }
                    }
                    on(IntentKey.SetName) { _, intent ->
                        Change.Update { current -> current.copy(name = (intent as Intent.SetName).name) }
                    }
                    on(IntentKey.Refresh) { _, _ -> Change.Enter { it } }
                    on(IntentKey.Close) { _, _ -> Change.Enter { it.copy(page = Page.Closed) } }
                    on(IntentKey.InvalidLocalEdit) { _, _ -> Change.Update { it.copy(page = Page.Closed) } }
                }
                stage(Page.Closed) {}
            }.use { editor ->
                val visit = editor.visit
                val colorJob = checkNotNull(editor.send(visit, Intent.SetColor("red")))
                val colorCall = colors.take()
                val opacityJob = checkNotNull(editor.send(visit, Intent.AgreeOpacity(70)))
                val opacityCall = opacity.take()
                check(colorCall.input == "red" && opacityCall.input == 70)

                editor.send(visit, Intent.SetName("Logo"))
                colorCall.complete("red")
                colorJob.await()
                check(editor.snapshot.state == Editor(color = "red", name = "Logo"))
                check(editor.snapshot.visit === visit && !opacityJob.isCancelled)
                opacityCall.complete(70)
                opacityJob.await()
                check(editor.snapshot.state == Editor(color = "red", opacity = 70, name = "Logo"))
                check(editor.snapshot.visit === visit)
                println("PASS editor: independent completions and typed input preserve one visit")

                val failingInput = Intent.SetColor("pink")
                val failing = checkNotNull(editor.send(visit, failingInput))
                val pendingColor = colors.take()
                val independent = checkNotNull(editor.send(visit, Intent.AgreeOpacity(40)))
                val pendingOpacity = opacity.take()
                val rejectedColor = IllegalStateException("color rejected")
                pendingColor.fail(rejectedColor)
                failing.await()
                pendingOpacity.complete(40)
                independent.await()
                check(editor.snapshot.state.opacity == 40)
                val error = editor.snapshot.failures.single()
                check(error.operation == "SetColor" && error.kind == FailureKind.OperationFailed)
                println("PASS editor: an unrelated successful task preserves the color error")

                val beforeInvalid = editor.snapshot
                val rejected = runCatching { editor.send(visit, Intent.InvalidLocalEdit) }.exceptionOrNull()
                check(rejected is IllegalArgumentException && editor.snapshot == beforeInvalid)

                val old = checkNotNull(editor.send(visit, Intent.SetColor("yellow")))
                val lateColor = colors.take()
                editor.send(visit, Intent.Refresh)
                check(editor.snapshot.visit !== visit && editor.snapshot.state == beforeInvalid.state)
                check(editor.snapshot.failures.isEmpty())
                lateColor.complete("yellow")
                old.join()
                check(old.isCancelled && editor.snapshot.state == beforeInvalid.state)
                editor.send(visit, Intent.Close)
                check(editor.snapshot.state.page == Page.Editing)
                editor.send(editor.visit, Intent.Close)
                check(editor.snapshot.state.page == Page.Closed)
                println("PASS editor: explicit re-entry revokes the old visit even at the same stage")
            }
            checkSchedulingBoundary(host)
        }
    } finally {
        host.cancel()
    }
}

private suspend fun checkSchedulingBoundary(host: CoroutineScope) {
    val ingest = Controlled<String, Unit>()
    Task(host, ingest::run).use { task ->
        val first = checkNotNull(task.start("first"))
        val active = ingest.take()
        check(task.start("second", WhileRunning.IgnoreRepeated) == null)
        check(!ingest.hasPendingCall())

        val second = checkNotNull(task.start("second", WhileRunning.ReplacePrevious))
        val overlap = ingest.take()
        check(active.input == "first" && overlap.input == "second")
        check(first.isCancelled && !first.isCompleted)
        active.complete(Unit)
        overlap.complete(Unit)
        first.join()
        second.await()
    }
    println("PASS scheduling boundary: ignore discards work and replacement allows physical overlap")
}
