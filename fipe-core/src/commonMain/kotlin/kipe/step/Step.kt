package kipe.step

import kotlinx.coroutines.flow.Flow

interface Step<In, Out> {
    val name: String
    fun process(flow: Flow<In>): Flow<Out>
}
