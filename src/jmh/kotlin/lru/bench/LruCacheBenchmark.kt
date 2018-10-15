package lru.bench

import lru.bench.SuspendingMutexLruCache
import kotlinx.coroutines.experimental.*
import org.openjdk.jmh.annotations.*
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 2)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
open class LruCacheBenchmark {
    private val repeat = 1000_000
    private val keyBound = 1000
    private val threadCount = 10
    private val executor = Executors.newFixedThreadPool(threadCount)

    @TearDown
    fun tearDown() {
        executor.shutdown()
    }

    @Benchmark
    fun blockingLru() {
        val lru = BlockingLruCacheImpl()
        val futures = (1..threadCount).map { threadNumber ->
            executor.submit {
                val r = Random(threadNumber.toLong())
                repeat(repeat / threadCount) {
                    lru.get(r.nextInt(keyBound))
                }
            }
        }
        futures.forEach { it.get() }
    }

    @Benchmark
    fun suspendingLru() = runBlocking {
        val lru = SuspendingLruCacheImpl()
        val deferred = (1..threadCount).map { threadNumber ->
            async(executor.asCoroutineDispatcher()) {
                val r = Random(threadNumber.toLong())
                repeat(repeat / threadCount) {
                    lru.get(r.nextInt(keyBound))
                }
            }
        }
        deferred.awaitAll()
    }
}

// it is less than
val lruMaxSize = 10_000

fun buildString(random: Random) = buildString(1000) {
    repeat(1000) {
        append('a' + random.nextInt(25))
    }
}

class BlockingLruCacheImpl : BlockingLruCache<Int, String>(lruMaxSize) {
    private val random = Random()
    override fun create(key: Int?) = buildString(random)

}

class SuspendingLruCacheImpl : SuspendingMutexLruCache<Int, String>(lruMaxSize) {
    private val random = Random()
    override fun create(key: Int) = buildString(random)
}