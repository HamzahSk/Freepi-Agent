package rofil.movie.app.core

import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny thread-safe TTL cache used to avoid hammering the source site for the
 * same catalog/detail pages. Entries are also size-bounded.
 */
class TtlCache<K, V>(private val maxEntries: Int = 200) {

    private data class Entry<V>(val value: V, val expiresAt: Long)

    private val map = ConcurrentHashMap<K, Entry<V>>()

    fun get(key: K): V? {
        val entry = map[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            map.remove(key)
            return null
        }
        return entry.value
    }

    fun put(key: K, value: V, ttlMillis: Long) {
        if (map.size >= maxEntries) {
            // Cheap eviction: drop expired entries, then an arbitrary one.
            val now = System.currentTimeMillis()
            map.entries.removeIf { it.value.expiresAt < now }
            if (map.size >= maxEntries) {
                map.keys.firstOrNull()?.let { map.remove(it) }
            }
        }
        map[key] = Entry(value, System.currentTimeMillis() + ttlMillis)
    }

    fun remove(key: K) {
        map.remove(key)
    }

    fun clear() = map.clear()
}
