package fipe.step

import fipe.Step
import kotlinx.coroutines.flow.Flow

interface ParallelOrderedStep<In, Out> : Step<In, Out> {
    val concurrency: Int
    val transform: suspend (In) -> Out
}

fun <In, Out> ParallelOrderedStep(
    name: String,
    concurrency: Int,
    transform: suspend (In) -> Out
): ParallelOrderedStep<In, Out> =
    ParallelOrderedStepImpl(name, concurrency, transform)

class ParallelOrderedStepImpl<In, Out>(
    override val name: String,
    override val concurrency: Int,
    override val transform: suspend (In) -> Out
) : ParallelOrderedStep<In, Out> {
    override fun process(flow: Flow<In>): Flow<Out> =
        flow
            .tagged()
            .flatMapMergeOrdered(concurrency, transform)
            .untagged()
}