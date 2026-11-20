package fipe.step

import fipe.Fipe
import fipe.Step
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn

interface ShareStep<T> : Step<T, T> {
    val scope: CoroutineScope
    val replay: Int
    val started: SharingStarted
}

fun <T> ShareStep(
    scope: CoroutineScope,
    replay: Int = 0,
    started: SharingStarted = SharingStarted.Eagerly,
    name: String = "ShareStep",
): ShareStep<T> = ShareStepImpl(scope, replay, started, name)

fun <T> Fipe<T, T>.share(
    scope: CoroutineScope,
    replay: Int = 0,
    started: SharingStarted = SharingStarted.Eagerly,
): Fipe<T, T> = this.then(ShareStep(scope, replay, started))

private class ShareStepImpl<T>(
    override val scope: CoroutineScope,
    override val replay: Int,
    override val started: SharingStarted,
    override val name: String,
) : ShareStep<T> {
    override fun process(flow: Flow<T>): Flow<T> = flow.shareIn(scope, started, replay)
}
