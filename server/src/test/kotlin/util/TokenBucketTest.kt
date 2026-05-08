package app.krail.bff.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenBucketTest {
    @Test
    fun `allow drains capacity then rejects`() {
        val bucket = TokenBucket(capacity = 3, refillPerSecond = 0)
        repeat(3) { assertTrue(bucket.allow(), "consumed token #${it + 1}") }
        assertFalse(bucket.allow(), "fourth call must be rate-limited (refillPerSecond=0)")
    }

    @Test
    fun `isFull is true at start and false after consumption`() {
        val bucket = TokenBucket(capacity = 5, refillPerSecond = 0)
        assertTrue(bucket.isFull(), "newly-minted bucket should be full")
        assertTrue(bucket.allow())
        assertFalse(bucket.isFull(), "after consuming a token, bucket should not be full")
    }

    @Test
    fun `independent buckets do not share state`() {
        val a = TokenBucket(capacity = 1, refillPerSecond = 0)
        val b = TokenBucket(capacity = 1, refillPerSecond = 0)
        assertTrue(a.allow())
        assertTrue(b.allow(), "draining bucket A must not affect bucket B")
        assertFalse(a.allow())
        assertFalse(b.allow())
    }
}
