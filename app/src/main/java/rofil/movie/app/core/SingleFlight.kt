package rofil.movie.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async

/** Shares active work; the last departing consumer cancels obsolete network work. No result cache. */
class SingleFlight<K, V> {
    private class Flight<V>(val work: Deferred<V>, var users: Int = 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = HashMap<K, Flight<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        val flight = synchronized(active) {
            active.getOrPut(key) { Flight(scope.async(start = CoroutineStart.LAZY) { block() }) }
                .also { it.users++ }
        }
        try { return flight.work.await() }
        finally {
            synchronized(active) {
                flight.users--
                if (flight.users == 0) {
                    if (active[key] === flight) active.remove(key)
                    if (flight.work.isActive) flight.work.cancel()
                }
            }
        }
    }
}
