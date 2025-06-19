package kipe.step

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface MapStep<In, Out> : Step<In, Out> {
    val mapper: suspend (In) -> Out
}

inline fun <reified In, reified Out> MapStep(
    noinline mapper: suspend (In) -> Out,
): MapStep<In, Out> = MapStep("MapStep ${In::class.simpleName} - ${Out::class.simpleName}", mapper)

fun <In, Out> MapStep(
    name: String,
    mapper: suspend (In) -> Out,
): MapStep<In, Out> = MapStepImpl(name, mapper)

private class MapStepImpl<In, Out>(
    override val name: String,
    override val mapper: suspend (In) -> Out,
) : MapStep<In, Out> {
    override fun process(flow: Flow<In>): Flow<Out> {
        return flow.map(mapper)
    }
}