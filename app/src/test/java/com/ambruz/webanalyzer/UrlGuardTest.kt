package com.ambruz.webanalyzer

import com.ambruz.webanalyzer.security.UrlGuard
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlGuardTest {
    @Test
    fun rejectsHttp() {
        assertTrue(UrlGuard.validateHttpsUrl("http://example.com").isFailure)
    }

    @Test
    fun acceptsHttps() {
        assertTrue(UrlGuard.validateHttpsUrl("https://example.com/path").isSuccess)
    }

    @Test
    fun rejectsLocalhost() {
        assertTrue(UrlGuard.validateHttpsUrl("https://localhost/admin").isFailure)
    }
}
