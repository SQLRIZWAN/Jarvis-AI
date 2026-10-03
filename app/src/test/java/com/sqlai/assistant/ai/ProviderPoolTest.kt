package com.sqlai.assistant.ai

import com.sqlai.assistant.core.AiProvider
import com.sqlai.assistant.core.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ProviderPool contract: failover order, keyless skipping, cooldowns and buckets. */
class ProviderPoolTest {

    private fun settings(
        provider: AiProvider,
        apiKey: String = "primary-key",
        providerKeys: Map<String, String> = emptyMap()
    ) = AppSettings(provider = provider, apiKey = apiKey, providerKeys = providerKeys)

    // ------------------------------------------------------------ candidates

    @Test
    fun fallbackOrderIsFixed() {
        assertEquals(
            listOf(
                AiProvider.GEMINI,
                AiProvider.GROQ,
                AiProvider.OPENROUTER,
                AiProvider.DEEPSEEK,
                AiProvider.OLLAMA
            ),
            ProviderPool.FALLBACK_ORDER
        )
    }

    @Test
    fun candidatesPrimaryGroqKeepsGeminiAndOllama() {
        val candidates = ProviderPool.candidates(
            settings(
                provider = AiProvider.GROQ,
                providerKeys = mapOf("GEMINI" to "gemini-key")
            )
        )

        assertEquals(listOf(AiProvider.GROQ, AiProvider.GEMINI, AiProvider.OLLAMA), candidates)
    }

    @Test
    fun candidatesPrimaryGeminiKeepsGroqAndOllama() {
        val candidates = ProviderPool.candidates(
            settings(
                provider = AiProvider.GEMINI,
                providerKeys = mapOf("GROQ" to "groq-key")
            )
        )

        assertEquals(listOf(AiProvider.GEMINI, AiProvider.GROQ, AiProvider.OLLAMA), candidates)
    }

    @Test
    fun candidatesWithoutSecondaryKeysArePrimaryPlusOllama() {
        val candidates = ProviderPool.candidates(settings(provider = AiProvider.DEEPSEEK))

        assertEquals(listOf(AiProvider.DEEPSEEK, AiProvider.OLLAMA), candidates)
    }

    @Test
    fun candidatesPrimaryIsNeverDuplicatedFromFallbackOrder() {
        val candidates = ProviderPool.candidates(
            settings(
                provider = AiProvider.OPENROUTER,
                providerKeys = mapOf("GEMINI" to "g", "GROQ" to "k", "DEEPSEEK" to "d")
            )
        )

        assertEquals(
            listOf(
                AiProvider.OPENROUTER,
                AiProvider.GEMINI,
                AiProvider.GROQ,
                AiProvider.DEEPSEEK,
                AiProvider.OLLAMA
            ),
            candidates
        )
    }

    // ------------------------------------------------------------- cooldowns

    @Test
    fun cooldownForRateLimitAndServerErrorsIsThirtySeconds() {
        assertEquals(30_000L, ProviderPool.cooldownFor(429))
        assertEquals(30_000L, ProviderPool.cooldownFor(500))
        assertEquals(30_000L, ProviderPool.cooldownFor(503))
        assertEquals(30_000L, ProviderPool.cooldownFor(418))
        assertEquals(30_000L, ProviderPool.cooldownFor(0))
    }

    @Test
    fun cooldownForAuthErrorsIsFiveMinutes() {
        assertEquals(300_000L, ProviderPool.cooldownFor(401))
        assertEquals(300_000L, ProviderPool.cooldownFor(403))
    }

    @Test
    fun noteFailureExtendsCooldownUntilFromNow() {
        val now = 1_700_000_000_000L

        ProviderPool.noteFailure(AiProvider.GROQ, 429, now)

        assertEquals(now + 30_000L, ProviderPool.cooldownUntil(AiProvider.GROQ))
    }

    @Test
    fun providerWithoutFailureHasNoCooldown() {
        assertEquals(0L, ProviderPool.cooldownUntil(AiProvider.DEEPSEEK))
    }

    // ---------------------------------------------------------------- bucket

    @Test
    fun bucketExhaustsTenPermitsThenBlocksInSameWindow() {
        val bucket = ProviderPool.Bucket(permitsPerWindow = 10)
        val now = 5_000_000L

        repeat(10) { assertTrue(bucket.tryAcquire(now)) }

        assertFalse(bucket.tryAcquire(now))
        assertTrue(bucket.nextSlotIn(now) > 0L)
    }

    @Test
    fun bucketReopensOnceTheWindowElapsed() {
        val bucket = ProviderPool.Bucket(permitsPerWindow = 10, windowMs = 60_000L)
        val now = 10_000L
        repeat(10) { assertTrue(bucket.tryAcquire(now)) }
        assertFalse(bucket.tryAcquire(now))

        assertTrue(bucket.tryAcquire(now + 60_000L))
        assertEquals(0L, bucket.nextSlotIn(now + 60_000L))
    }

    @Test
    fun nextSlotInIsZeroWhilePermitsRemain() {
        val bucket = ProviderPool.Bucket(permitsPerWindow = 3)
        val now = 42L

        assertTrue(bucket.tryAcquire(now))
        assertTrue(bucket.tryAcquire(now))

        assertEquals(0L, bucket.nextSlotIn(now))
    }

    @Test
    fun geminiBucketIsTighterThanTheOthers() {
        val gemini = ProviderPool.bucketFor(AiProvider.GEMINI)
        val groq = ProviderPool.bucketFor(AiProvider.GROQ)

        assertEquals(10, gemini?.permitsPerWindow ?: -1)
        assertEquals(30, groq?.permitsPerWindow ?: -1)
        assertEquals(60_000L, gemini?.windowMs ?: -1L)
    }
}
