package com.getvideo.app.data.repo

import org.junit.Assert.*
import org.junit.Test

class ServerResourceUrlTest {
    private val base = "https://43.142.37.199/getvideo/"
    @Test fun apiPathsAndPublicPathsKeepExactlyOnePrefix() {
        for (path in listOf("/v2/video/2", "v2/video/2", "/getvideo/v2/video/2")) {
            assertEquals(base + "v2/video/2", serverResourceUrl(base, path).toString())
        }
        assertEquals(base + "v2/recreations/p/source", serverResourceUrl(base, "/v2/recreations/p/source").toString())
        assertEquals(base + "v2/video/2", serverResourceUrl(base, "https://43.142.37.199:8443/v2/video/2").toString())
        assertEquals("https://custom.example/service/v2/video/2", serverResourceUrl("https://custom.example/service/", "/v2/video/2").toString())
    }
    @Test fun unsafeResourcesAreRejectedBeforeAnyRequest() {
        for (path in listOf("https://evil.example/v2/video/2", "//evil.example/v2/video/2", "../getaudio/health", "/%2e%2e/account/me", "/v2/../../account/me", "/getvideo/../getaudio/health")) {
            try { serverResourceUrl(base, path); fail("Accepted unsafe URL: $path") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
