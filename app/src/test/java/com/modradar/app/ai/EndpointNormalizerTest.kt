package com.modradar.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointNormalizerTest {

    @Test fun keepsVersionedUrl() {
        val r = EndpointNormalizer.normalize("https://api.deepseek.com/v1")
        assertTrue(r.ok)
        assertEquals("https://api.deepseek.com/v1/chat/completions", r.chatUrl)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun appendsV1WhenOnlyHost() {
        val r = EndpointNormalizer.normalize("api.deepseek.com")
        assertEquals("https://api.deepseek.com/v1", r.baseUrl)
        assertTrue(r.warnings.any { "https" in it })
        assertTrue(r.warnings.any { "/v1" in it })
    }

    @Test fun stripsPastedCompletionsPathAndSpaces() {
        val r = EndpointNormalizer.normalize("  https://api.openai.com/v1/chat/completions/ \n")
        assertEquals("https://api.openai.com/v1", r.baseUrl)
    }

    @Test fun stripsModelsSuffix() {
        assertEquals("https://x.com/v1", EndpointNormalizer.normalize("https://x.com/v1/models").baseUrl)
    }

    @Test fun keepsNonV1Path() {
        val r = EndpointNormalizer.normalize("https://generativelanguage.googleapis.com/v1beta/openai/")
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", r.baseUrl)
    }

    @Test fun rejectsBadScheme() {
        assertFalse(EndpointNormalizer.normalize("ftp://x.com/v1").ok)
        assertFalse(EndpointNormalizer.normalize("").ok)
    }

    @Test fun warnsOnPublicHttp() {
        assertTrue(EndpointNormalizer.normalize("http://example.com/v1").warnings.any { "明文" in it })
        assertTrue(EndpointNormalizer.normalize("http://192.168.1.2:11434/v1").warnings.none { "明文" in it })
    }

    @Test fun cleansKey() {
        assertEquals("sk-abc123", EndpointNormalizer.cleanKey(" Bearer sk-abc\n123 "))
        assertEquals("sk-abc", EndpointNormalizer.cleanKey("\"sk-abc\""))
    }

    @Test fun masksKey() {
        assertEquals("sk-a…7890", EndpointNormalizer.maskKey("sk-a1234567890"))
    }

    @Test fun settingsCleanedNormalizesAll() {
        val s = AiSettings(baseUrl = "api.x.com/v1/chat/completions", apiKey = " sk-1 ", model = " m ").cleaned()
        assertEquals("https://api.x.com/v1", s.baseUrl)
        assertEquals("sk-1", s.apiKey)
        assertEquals("m", s.model)
    }
}
