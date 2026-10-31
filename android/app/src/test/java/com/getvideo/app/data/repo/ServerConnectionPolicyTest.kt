package com.getvideo.app.data.repo

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ServerConnectionPolicyTest {
    @Test fun `old development defaults migrate while custom servers remain`() {
        assertEquals("https://43.142.37.199/getvideo/", effectiveServerUrl("http://10.0.2.2:8900/"))
        assertEquals("https://43.142.37.199/getvideo/", effectiveServerUrl(null))
        assertEquals("https://43.142.37.199/getvideo/", effectiveServerUrl("https://43.142.37.199:8443/"))
        assertEquals("", effectiveServerUrl("  "))
        assertEquals("http://127.0.0.1:18900/", effectiveServerUrl("http://127.0.0.1:18900/"))
        assertEquals("https://custom.example/", effectiveServerUrl("https://custom.example/"))
    }
    @Test fun `credentials only accompany intended HTTPS origin including downloads`() {
        val origin = "https://getvideo.example.com/".toHttpUrl()
        assertTrue(isCredentialOrigin("https://getvideo.example.com/video/2".toHttpUrl(), origin))
        assertFalse(isCredentialOrigin("http://getvideo.example.com/video/2".toHttpUrl(), origin))
        assertFalse(isCredentialOrigin("https://getvideo.example.com:8900/video/2".toHttpUrl(), origin))
        assertFalse(isCredentialOrigin("https://other.example/video/2".toHttpUrl(), origin))
    }
    @Test fun `shared HTTPS origin never receives credentials outside project path`() {
        val base = "https://43.142.37.199/getvideo/".toHttpUrl()
        assertTrue(isCredentialOrigin("https://43.142.37.199/getvideo/v2/video/1".toHttpUrl(), base))
        assertFalse(isCredentialOrigin("https://43.142.37.199/v2/video/1".toHttpUrl(), base))
        assertFalse(isCredentialOrigin("https://43.142.37.199/getaudio/v2/video/1".toHttpUrl(), base))
        assertFalse(isCredentialOrigin("https://43.142.37.199/getvideo-evil/video/1".toHttpUrl(), base))
        assertFalse(isCredentialOrigin("https://43.142.37.199/getvideo/../account/me".toHttpUrl(), base))
    }
}
