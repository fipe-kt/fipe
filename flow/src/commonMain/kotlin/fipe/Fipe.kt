package fipe

import kotlinx.coroutines.flow.Flow

interface Fipe<In, Out> {
    val builder: (Flow<In>) -> Flow<Out>

    fun <Next> then(step: Step<Out, Next>): Fipe<In, Next>

    fun toFlow(input: Flow<In>): Flow<Out>
}