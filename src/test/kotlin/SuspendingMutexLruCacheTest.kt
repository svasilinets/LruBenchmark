import lru.bench.SuspendingMutexLruCache
import junit.framework.TestCase
import kotlinx.coroutines.experimental.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.*

@RunWith(JUnit4::class)
class LruCacheLockLockTest {
    private var expectedCreateCount: Int = 0
    private var expectedPutCount: Int = 0
    private var expectedHitCount: Int = 0
    private var expectedMissCount: Int = 0
    private var expectedEvictionCount: Int = 0

    @Test
    fun testStatistics() = runBlocking {
        val cache = SuspendingMutexLruCache<String, String>(3)
        assertStatistics(cache)

        assertEquals(null, cache.put("a", "A"))
        expectedPutCount++
        assertStatistics(cache)
        assertHit(cache, "a", "A")
        assertSnapshot(cache, "a", "A")

        assertEquals(null, cache.put("b", "B"))
        expectedPutCount++
        assertStatistics(cache)
        assertHit(cache, "a", "A")
        assertHit(cache, "b", "B")
        assertSnapshot(cache, "a", "A", "b", "B")

        assertEquals(null, cache.put("c", "C"))
        expectedPutCount++
        assertStatistics(cache)
        assertHit(cache, "a", "A")
        assertHit(cache, "b", "B")
        assertHit(cache, "c", "C")
        assertSnapshot(cache, "a", "A", "b", "B", "c", "C")

        assertEquals(null, cache.put("d", "D"))
        expectedPutCount++
        expectedEvictionCount++ // a should have been evicted
        assertStatistics(cache)
        assertMiss(cache, "a")
        assertHit(cache, "b", "B")
        assertHit(cache, "c", "C")
        assertHit(cache, "d", "D")
        assertHit(cache, "b", "B")
        assertHit(cache, "c", "C")
        assertSnapshot(cache, "d", "D", "b", "B", "c", "C")

        assertEquals(null, cache.put("e", "E"))
        expectedPutCount++
        expectedEvictionCount++ // d should have been evicted
        assertStatistics(cache)
        assertMiss(cache, "d")
        assertMiss(cache, "a")
        assertHit(cache, "e", "E")
        assertHit(cache, "b", "B")
        assertHit(cache, "c", "C")
        assertSnapshot(cache, "e", "E", "b", "B", "c", "C")
    }

    @Test
    fun testStatisticsWithCreate() = runBlocking {
        val cache = newCreatingCache()
        assertStatistics(cache)

        assertCreated(cache, "aa", "created-aa")
        assertHit(cache, "aa", "created-aa")
        assertSnapshot(cache, "aa", "created-aa")

        assertCreated(cache, "bb", "created-bb")
        assertMiss(cache, "c")
        assertSnapshot(cache, "aa", "created-aa", "bb", "created-bb")

        assertCreated(cache, "cc", "created-cc")
        assertSnapshot(cache, "aa", "created-aa", "bb", "created-bb", "cc", "created-cc")

        expectedEvictionCount++ // aa will be evicted
        assertCreated(cache, "dd", "created-dd")
        assertSnapshot(cache, "bb", "created-bb", "cc", "created-cc", "dd", "created-dd")

        expectedEvictionCount++ // bb will be evicted
        assertCreated(cache, "aa", "created-aa")
        assertSnapshot(cache, "cc", "created-cc", "dd", "created-dd", "aa", "created-aa")
    }

    @Test
    fun testCreateOnCacheMiss() = runBlocking  {
        val cache = newCreatingCache()
        val created = cache.get("aa")
        TestCase.assertEquals("created-aa", created)
    }

    @Test
    fun testNoCreateOnCacheHit() = runBlocking {
        val cache = newCreatingCache()
        cache.put("aa", "put-aa")
        TestCase.assertEquals("put-aa", cache.get("aa"))
    }

    @Test
    fun testConstructorDoesNotAllowZeroCacheSize() {
        try {
            SuspendingMutexLruCache<String, String>(0)
            TestCase.fail()
        } catch (expected: IllegalArgumentException) {
        }

    }

    @Test
    fun testEvictionWithSingletonCache() = runBlocking  {
        val cache = SuspendingMutexLruCache<String, String>(1)
        cache.put("a", "A")
        cache.put("b", "B")
        assertSnapshot(cache, "b", "B")
    }

    @Test
    fun testEntryEvictedWhenFull() = runBlocking  {
        val log = ArrayList<String>()
        val cache = newRemovalLogCache(log)

        cache.put("a", "A")
        cache.put("b", "B")
        cache.put("c", "C")
        assertEquals(emptyList<String>(), log)

        cache.put("d", "D")
        TestCase.assertEquals(Arrays.asList("a=A"), log)
    }

    /**
     * Replacing the value for a key doesn't cause an eviction but it does bring
     * the replaced entry to the front of the queue.
     */
    @Test
    fun testPutCauseEviction() = runBlocking  {
        val log = ArrayList<String>()
        val cache = newRemovalLogCache(log)

        cache.put("a", "A")
        cache.put("b", "B")
        cache.put("c", "C")
        cache.put("b", "B2")
        TestCase.assertEquals(Arrays.asList("b=B>B2"), log)
        assertSnapshot(cache, "a", "A", "c", "C", "b", "B2")
    }

    @Test
    fun testCustomSizesImpactsSize() = runBlocking  {
        val cache = object : SuspendingMutexLruCache<String, String>(10) {
            override fun sizeOf(key: String, value: String): Int {
                return key.length + value.length
            }
        }

        assertEquals(0, cache.size())
        cache.put("a", "AA")
        assertEquals(3, cache.size())
        cache.put("b", "BBBB")
        assertEquals(8, cache.size())
        cache.put("a", "")
        assertEquals(6, cache.size())
    }

    @Test
    fun testEvictionWithCustomSizes() = runBlocking {
        val cache = object : SuspendingMutexLruCache<String, String>(4) {
            override fun sizeOf(key: String, value: String): Int {
                return value.length
            }
        }

        cache.put("a", "AAAA")
        assertSnapshot(cache, "a", "AAAA")
        cache.put("b", "BBBB") // should evict a
        assertSnapshot(cache, "b", "BBBB")
        cache.put("c", "CC") // should evict b
        assertSnapshot(cache, "c", "CC")
        cache.put("d", "DD")
        assertSnapshot(cache, "c", "CC", "d", "DD")
        cache.put("e", "E") // should evict c
        assertSnapshot(cache, "d", "DD", "e", "E")
        cache.put("f", "F")
        assertSnapshot(cache, "d", "DD", "e", "E", "f", "F")
        cache.put("g", "G") // should evict d
        assertSnapshot(cache, "e", "E", "f", "F", "g", "G")
        cache.put("h", "H")
        assertSnapshot(cache, "e", "E", "f", "F", "g", "G", "h", "H")
        cache.put("i", "III") // should evict e, f, and g
        assertSnapshot(cache, "h", "H", "i", "III")
        cache.put("j", "JJJ") // should evict h and i
        assertSnapshot(cache, "j", "JJJ")
    }

    @Test
    fun testEvictionThrowsWhenSizesAreInconsistent() = runBlocking {
        val cache = object : SuspendingMutexLruCache<String, IntArray>(4) {
            override fun sizeOf(key: String, value: IntArray): Int {
                return value[0]
            }
        }

        val a = intArrayOf(4)
        cache.put("a", a)

        // get the cache size out of sync
        a[0] = 1
        assertEquals(4, cache.size())

        // evict something
        try {
            cache.put("b", intArrayOf(2))
            TestCase.fail()
        } catch (expected: IllegalStateException) {
        }

    }

    @Test
    fun testEvictionThrowsWhenSizesAreNegative() = runBlocking  {
        val cache = object : SuspendingMutexLruCache<String, String>(4) {
             override fun sizeOf(key: String, value: String): Int {
                return -1
            }
        }

        try {
            cache.put("a", "A")
            TestCase.fail()
        } catch (expected: IllegalStateException) {
        }

    }

    /**
     * Naive caches evict at most one element at a time. This is problematic
     * because evicting a small element may be insufficient to make room for a
     * large element.
     */
    @Test
    fun testDifferentElementSizes() = runBlocking {
        val cache = object : SuspendingMutexLruCache<String, String>(10) {
            override fun sizeOf(key: String, value: String): Int {
                return value.length
            }
        }

        cache.put("a", "1")
        cache.put("b", "12345678")
        cache.put("c", "1")
        assertSnapshot(cache, "a", "1", "b", "12345678", "c", "1")
        cache.put("d", "12345678") // should evict a and b
        assertSnapshot(cache, "c", "1", "d", "12345678")
        cache.put("e", "12345678") // should evict c and d
        assertSnapshot(cache, "e", "12345678")
    }

    @Test
    fun testEvictAll() = runBlocking  {
        val log = ArrayList<String>()
        val cache = newRemovalLogCache(log)
        cache.put("a", "A")
        cache.put("b", "B")
        cache.put("c", "C")
        cache.evictAll()
        assertEquals(0, cache.size())
        TestCase.assertEquals(Arrays.asList("a=A", "b=B", "c=C"), log)
    }

    @Test
    fun testEvictAllEvictsSizeZeroElements() = runBlocking {
        val cache = object : SuspendingMutexLruCache<String, String>(10) {
            override fun sizeOf(key: String, value: String): Int {
                return 0
            }
        }

        cache.put("a", "A")
        cache.put("b", "B")
        cache.evictAll()
        assertSnapshot(cache)
    }

    @Test
    fun testRemoveWithCustomSizes() = runBlocking  {
        val cache = object : SuspendingMutexLruCache<String, String>(10) {
            override fun sizeOf(key: String, value: String): Int {
                return value.length
            }
        }
        cache.put("a", "123456")
        cache.put("b", "1234")
        cache.remove("a")
        assertEquals(4, cache.size())
    }

    @Test
    fun testRemoveAbsentElement() = runBlocking  {
        val cache = SuspendingMutexLruCache<String, String>(10)
        cache.put("a", "A")
        cache.put("b", "B")
        assertEquals(null, cache.remove("c"))
        assertEquals(2, cache.size())
    }

    @Test
    fun testRemoveCallsEntryRemoved() = runBlocking  {
        val log = ArrayList<String>()
        val cache = newRemovalLogCache(log)
        cache.put("a", "A")
        cache.remove("a")
        TestCase.assertEquals(Arrays.asList("a=A>null"), log)
    }

    @Test
    fun testPutCallsEntryRemoved() = runBlocking {
        val log = ArrayList<String>()
        val cache = newRemovalLogCache(log)
        cache.put("a", "A")
        cache.put("a", "A2")
        TestCase.assertEquals(Arrays.asList("a=A>A2"), log)
    }

    @Test
    fun testEntryRemovedIsCalledWithoutSynchronization() = runBlocking  {
        val cache = object : SuspendingMutexLruCache<String, String>(3) {
            override fun entryRemoved(
                evicted: Boolean, key: String, oldValue: String, newValue: String?
            ) {
                TestCase.assertFalse(Thread.holdsLock(this))
            }
        }

        cache.put("a", "A")
        cache.put("a", "A2") // replaced
        cache.put("b", "B")
        cache.put("c", "C")
        cache.put("d", "D")  // single eviction
        cache.remove("a")    // removed
        cache.evictAll()     // multiple eviction
    }

    /**
     * Test what happens when a value is added to the map while create is
     * working. The map value should be returned by get(), and the created value
     * should be released with entryRemoved().
     */
    @Test
    fun testCreateWithConcurrentPut() = runBlocking  {
        val log = ArrayList<String>()

        val cache = object : SuspendingMutexLruCache<String, String>(3) {
            override fun create(key: String): String {
                runBlocking {
                    put(key, "B")
                }
                return "A"
            }

            override fun entryRemoved(
                evicted: Boolean, key: String, oldValue: String, newValue: String?
            ) {
                log.add("$key=$oldValue>$newValue")
            }
        }

        TestCase.assertEquals("B", cache.get("a"))
        TestCase.assertEquals(Arrays.asList("a=A>B"), log)
    }

    /**
     * Test what happens when two creates happen concurrently. The result from
     * the first create to return is returned by both gets. The other created
     * values should be released with entryRemove().
     */
    @Test
    fun testCreateWithConcurrentCreate() = runBlocking  {
        val log = ArrayList<String>()
        val cache = object : SuspendingMutexLruCache<String, Int>(3) {
            var callCount = 0
            override fun create(key: String): Int? {
                if (callCount++ == 0) {
                    runBlocking {
                        assertEquals(2, get(key))
                    }
                    return 1
                } else {
                    return 2
                }
            }

            override fun entryRemoved(
                evicted: Boolean, key: String, oldValue: Int, newValue: Int?
            ) {
                log.add("$key=$oldValue>$newValue")
            }
        }

        assertEquals(2, cache.get("a"))
        TestCase.assertEquals(Arrays.asList("a=1>2"), log)
    }

    private fun newCreatingCache(): SuspendingMutexLruCache<String, String> {
        return object : SuspendingMutexLruCache<String, String>(3) {
            override fun create(key: String): String? {
                return if (key.length > 1) "created-$key" else null
            }
        }
    }

    private fun newRemovalLogCache(log: MutableList<String>): SuspendingMutexLruCache<String, String> {
        return object : SuspendingMutexLruCache<String, String>(3) {
            override fun entryRemoved(
                evicted: Boolean, key: String, oldValue: String, newValue: String?
            ) {
                val message = if (evicted)
                    "$key=$oldValue"
                else
                    "$key=$oldValue>$newValue"
                log.add(message)
            }
        }
    }

    private fun assertHit(cache: SuspendingMutexLruCache<String, String>, key: String, value: String) = runBlocking  {
        TestCase.assertEquals(value, cache.get(key))
        expectedHitCount++
        assertStatistics(cache)
    }

    private fun assertMiss(cache: SuspendingMutexLruCache<String, String>, key: String) = runBlocking  {
        TestCase.assertEquals(null, cache.get(key))
        expectedMissCount++
        assertStatistics(cache)
    }

    private suspend fun assertCreated(cache: SuspendingMutexLruCache<String, String>, key: String, value: String) {
        TestCase.assertEquals(value, cache.get(key))
        expectedMissCount++
        expectedCreateCount++
        assertStatistics(cache)
    }

    private suspend fun assertStatistics(cache: SuspendingMutexLruCache<*, *>) {
        assertEquals("create count", expectedCreateCount, cache.createCount())
        assertEquals("put count", expectedPutCount, cache.putCount())
        assertEquals("hit count", expectedHitCount, cache.hitCount())
        assertEquals("miss count", expectedMissCount, cache.missCount())
        assertEquals("eviction count", expectedEvictionCount, cache.evictionCount())
    }

    private suspend fun <T> assertSnapshot(cache: SuspendingMutexLruCache<T, T>, vararg keysAndValues: T) {
        val actualKeysAndValues = ArrayList<T>()
        for (entry in cache.snapshot()) {
            actualKeysAndValues.add(entry.key)
            actualKeysAndValues.add(entry.value)
        }

        // assert using lists because order is important for LRUs
        TestCase.assertEquals(Arrays.asList(*keysAndValues), actualKeysAndValues)
    }
}