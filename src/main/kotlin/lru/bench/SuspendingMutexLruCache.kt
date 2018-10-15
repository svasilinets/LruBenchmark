package lru.bench

import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import java.util.*

open class SuspendingMutexLruCache<K, V>(maxSize: Int) {
    private val map: LinkedHashMap<K, V>

    /** Size of this cache in units. Not necessarily the number of elements.  */
    private var size: Int = 0
    private var maxSize: Int = 0

    private var putCount: Int = 0
    private var createCount: Int = 0
    private var evictionCount: Int = 0
    private var hitCount: Int = 0
    private var missCount: Int = 0

    private val mutex = Mutex()

    /**
     * @param maxSize for caches that do not override [.sizeOf], this is
     * the maximum number of entries in the cache. For all other caches,
     * this is the maximum sum of the sizes of the entries in this cache.
     */
    init {
        if (maxSize <= 0) {
            throw IllegalArgumentException("maxSize <= 0")
        }
        this.maxSize = maxSize
        map = LinkedHashMap<K, V>(0, 0.75f, true)
    }


    /**
     * Sets the size of the cache.
     *
     * @param maxSize The new maximum size.
     */
    suspend fun resize(maxSize: Int) {
        if (maxSize <= 0) {
            throw IllegalArgumentException("maxSize <= 0")
        }
        mutex.withLock {
            this.maxSize = maxSize
        }
        trimToSize(maxSize)
    }

    suspend fun get(key: K): V? {
        val mapValue = mutex.withLock {
            map[key].also {
                if (it != null) {
                    hitCount++
                } else {
                    missCount++
                }
            }
        }
        if (mapValue != null) return mapValue

        /*
         * Attempt to create a value. This may take a long time, and the map
         * may be different when create() returns. If a conflicting value was
         * added to the map while create() was working, we leave that value in
         * the map and release the created value.
         */
        val createdValue = create(key) ?: return null

        val previous = mutex.withLock {
            createCount++
            val previous = map.put(key, createdValue)
            if (previous != null) {
                // There was a conflict so undo that last put
                map[key] = previous
            } else {
                size += safeSizeOf(key, createdValue)
            }
            previous
        }

        return if (previous != null) {
            entryRemoved(false, key, createdValue, previous)
            previous
        } else {
            trimToSize(maxSize)
            createdValue
        }
    }

    /**
     * Caches `value` for `key`. The value is moved to the head of
     * the queue.
     *
     * @return the previous value mapped by `key`.
     */
    suspend fun put(key: K, value: V): V? {
        val previous = mutex.withLock {
            putCount++
            size += safeSizeOf(key, value)
            map.put(key, value)?.also {
                size -= safeSizeOf(key, it)
            }
        }

        if (previous != null) {
            entryRemoved(false, key, previous, value)
        }
        trimToSize(maxSize)
        return previous
    }


    /**
     * Removes the entry for `key` if it exists.
     *
     * @return the previous value mapped by `key`.
     */
    suspend fun remove(key: K): V? {
        val previous = mutex.withLock { map.remove(key)?.also { size -= safeSizeOf(key, it) } }
        if (previous != null) {
            entryRemoved(false, key, previous, null)
        }
        return previous
    }

    /**
     * Remove the eldest entries until the total of remaining entries is at or
     * below the requested size.
     *
     * @param maxSize the maximum size of the cache before returning. May be -1
     * to evict even 0-sized elements.
     */
    suspend fun trimToSize(maxSize: Int) {
        while (true) {
            val mapPair = mutex.withLock {
                if (size < 0 || map.isEmpty() && size != 0) {
                    throw IllegalStateException(javaClass.name + ".sizeOf() is reporting inconsistent results!")
                }

                if (size <= maxSize || map.isEmpty()) {
                    null
                } else {
                    val toEvict = map.entries.iterator().next()
                    map.remove(toEvict.key)
                    size -= safeSizeOf(toEvict.key, toEvict.value)
                    evictionCount++
                    toEvict.key to toEvict.value
                }
            } ?: break
            entryRemoved(true, mapPair.first, mapPair.second, null)
        }
    }

    open protected fun entryRemoved(evicted: Boolean, key: K, oldValue: V, newValue: V?) {}

    open protected fun create(key: K): V? = null

    private fun safeSizeOf(key: K, value: V): Int {
        val result = sizeOf(key, value)
        if (result < 0) {
            throw IllegalStateException("Negative size: $key=$value")
        }
        return result
    }

    open protected fun sizeOf(key: K, value: V): Int = 1

    /**
     * Clear the cache, calling [.entryRemoved] on each removed entry.
     */
    suspend fun evictAll() {
        trimToSize(-1) // -1 will evict 0-sized elements
    }

    /**
     * For caches that do not override [.sizeOf], this returns the number
     * of entries in the cache. For all other caches, this returns the sum of
     * the sizes of the entries in this cache.
     */
    suspend fun size(): Int {
        return mutex.withLock { size }
    }

    /**
     * For caches that do not override [.sizeOf], this returns the maximum
     * number of entries in the cache. For all other caches, this returns the
     * maximum sum of the sizes of the entries in this cache.
     */
    suspend fun maxSize(): Int {
        return mutex.withLock { maxSize }
    }

    /**
     * Returns the number of times [.get] returned a value that was
     * already present in the cache.
     */
    suspend fun hitCount(): Int {
        return mutex.withLock { hitCount }
    }

    /**
     * Returns the number of times [.get] returned null or required a new
     * value to be created.
     */
    suspend fun missCount(): Int {
        return mutex.withLock { missCount }
    }

    /**
     * Returns the number of times [.create] returned a value.
     */
    suspend fun createCount(): Int {
        return mutex.withLock { createCount }
    }

    /**
     * Returns the number of times [.put] was called.
     */
    suspend fun putCount(): Int {
        return mutex.withLock { putCount }
    }

    /**
     * Returns the number of values that have been evicted.
     */
    suspend fun evictionCount(): Int {
        return mutex.withLock { evictionCount }
    }

    /**
     * Returns a copy of the current contents of the cache, ordered from least
     * recently accessed to most recently accessed.
     */
    suspend fun snapshot(): Map<K, V> {
        return mutex.withLock { LinkedHashMap(map) }
    }

//    @Synchronized
//    override fun toString(): String {
//        val accesses = hitCount + missCount
//        val hitPercent = if (accesses != 0) 100 * hitCount / accesses else 0
//        return String.format(
//            Locale.US, "BlockingLruCache[maxSize=%d,hits=%d,misses=%d,hitRate=%d%%]",
//            maxSize, hitCount, missCount, hitPercent
//        )
//    }
}