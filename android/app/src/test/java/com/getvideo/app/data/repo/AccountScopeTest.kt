package com.getvideo.app.data.repo

import org.junit.Assert.*
import org.junit.Test

class AccountScopeTest {
    @Test fun officialEndpointMigrationPreservesHistoricalCloudIdentity() {
        val old = "https://43.142.37.199:8443/"
        val fresh = "https://43.142.37.199/getvideo/"
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest((old + "|acct_a").toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals(expected, cloudScopeKey(old, "acct_a"))
        assertEquals(expected, cloudScopeKey(fresh, "acct_a"))
        assertNotEquals(expected, cloudScopeKey("https://43.142.37.199/getaudio/", "acct_a"))
    }
    @Test fun scopeSeparatesAccountAndServer() {
        assertEquals(cloudScopeKey("https://example.com/", "acct_a"), cloudScopeKey("https://example.com", "acct_a"))
        assertNotEquals(cloudScopeKey("https://example.com/", "acct_a"), cloudScopeKey("https://example.com/", "acct_b"))
        assertNotEquals(cloudScopeKey("https://example.com/", "acct_a"), cloudScopeKey("https://other.example/", "acct_a"))
    }
    @Test fun lateResponseRequiresOriginalAccountAndServer() {
        val a = AccountStore.Account("acct_a", "", "token-a", "https://example.com/")
        val b = a.copy(userId = "acct_b", token = "token-b")
        assertTrue(accountResponseIsCurrent(a, a, a.serverUrl))
        assertFalse(accountResponseIsCurrent(a, b, a.serverUrl))
        assertFalse(accountResponseIsCurrent(a, null, a.serverUrl))
        assertFalse(accountResponseIsCurrent(a, a, "https://other.example/"))
        assertFalse(accountResponseIsCurrent(a, a.copy(token = "renewed"), a.serverUrl))
    }
    @Test fun remoteIdsCannotOverwriteExistingLocalIds() {
        assertEquals(17L, chooseCloudLocalId(17L, emptySet()))
        assertEquals(18L, chooseCloudLocalId(17L, setOf(17L, -30L)))
        assertEquals(21L, chooseCloudLocalId(17L, setOf(17L, 20L)))
    }
}
