package com.getvideo.app.data.repo

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ServerAccessStoreTest {
    @Test fun rejectedPasswordDoesNotReplaceWorkingCredential() {
        val store = ServerAccessStore(Files.createTempDirectory("access").resolve("server-access.json").toFile())
        store.saveValidated("lck80", "correct") { }
        val original = store.authorization()
        runCatching { store.saveValidated("lck80", "wrong") { error("401") } }
        assertEquals(original, store.authorization())
        assertEquals("lck80", store.username())
    }

    @Test fun reopeningStoreKeepsCredentialAndEmptyPasswordKeepsExistingValue() {
        val file = Files.createTempDirectory("access").resolve("server-access.json").toFile()
        val store = ServerAccessStore(file)
        store.saveValidated("lck80", "correct") { }
        val reopened = ServerAccessStore(file)
        assertEquals(store.authorization(), reopened.candidate("lck80", ""))
        assertNotNull(reopened.authorization())
        assertTrue(runCatching { reopened.candidate("someone-else", "") }.isFailure)
    }

    @Test fun missingAndMalformedCredentialsAreNotTreatedAsConfigured() {
        val file = Files.createTempDirectory("access").resolve("server-access.json").toFile()
        val store = ServerAccessStore(file)
        assertNull(store.authorization())
        assertTrue(runCatching { store.candidate("lck80", "") }.isFailure)
        file.writeText("""{"authorization":"Basic invalid"}""")
        assertNull(store.authorization())
        assertTrue(runCatching { store.candidate("bad:name", "secret") }.isFailure)
    }
}
