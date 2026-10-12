package com.getvideo.app.data.repo

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ServerConnectionPolicyTest {
    @Test fun `old development defaults migrate while custom servers remain`() {
        assertEquals("http://10.0.2.2:8900/", effectiveServerUrl("http://127.0.0.1:18900/"))
        assertEquals("http://10.0.2.2:8900/", effectiveServerUrl("http://10.0.2.2:8900/"))
        assertEquals("http://10.0.2.2:8900/", effectiveServerUrl(null))
        assertEquals("https://custom.example/", effectiveServerUrl("https://custom.example/"))
    }
    @Test fun `credentials only accompany intended HTTPS origin including downloads`() {
        val origin = "https://getvideo.example.com/".toHttpUrl()
        assertTrue(isCredentialOrigin("https://getvideo.example.com/video/2".toHttpUrl(), origin))
        assertFalse(isCredentialOrigin("http://getvideo.example.com/video/2".toHttpUrl(), origin))
        assertFalse(isCredentialOrigin("https://getvideo.example.com:8900/video/2".toHttpUrl(), origin))
        assertFalse(isCredentialOrigin("https://other.example/video/2".toHttpUrl(), origin))
    }
}
