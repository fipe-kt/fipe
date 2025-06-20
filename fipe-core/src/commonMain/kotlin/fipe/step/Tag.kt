// fipe/step/Tag.kt
package fipe.step

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.DEFAULT_CONCURRENCY
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

data class Tagged<T>(val idx: Long, val value: T)

fun <T> Flow<T>.tagged(): Flow<Tagged<T>> = flow {
    var idx = 0L
    collect { emit(Tagged(idx++, it)) }
}

fun <T> Flow<Tagged<T>>.untagged(): Flow<T> = map { it.value }

fun <T> Flow<Tagged<T>>.ordered(): Flow<Tagged<T>> = flow {
    val buffer = mutableMapOf<Long, Tagged<T>>()
    var nextIdx = 0L
    collect { tagged ->
        buffer[tagged.idx] = tagged
        while (true) {
            val candidate = buffer[nextIdx]
            if (candidate != null) {
                emit(candidate)
                buffer.remove(nextIdx)
                nextIdx++
            } else {
                break
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
fun <T, R> Flow<Tagged<T>>.flatMapMergeOrdered(
    concurrency: Int = DEFAULT_CONCURRENCY,
    transform: suspend (T) -> R,
): Flow<Tagged<R>> =
    flatMapMerge(concurrency) { tagged ->
        flow {
            val out = transform(tagged.value)
            emit(Tagged(tagged.idx, out))
        }
    }.ordered()