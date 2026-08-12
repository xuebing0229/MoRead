package com.mozhi.reader.feature.reader

import com.mozhi.reader.ai.client.AiClientException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCompanionRecoveryTest {
    @Test
    fun `only upstream empty stream http errors are retryable`() {
        assertTrue(
            AiClientException.Http(
                500,
                "empty_stream: upstream stream closed before first payload"
            ).isRetryableEmptyStream()
        )
        assertFalse(AiClientException.Http(500, "database unavailable").isRetryableEmptyStream())
        assertFalse(AiClientException.Timeout().isRetryableEmptyStream())
        assertFalse(AiClientException.Empty().isRetryableEmptyStream())
    }
}
