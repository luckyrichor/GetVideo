package com.getvideo.app.data.repo

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class FailureOutboxMigrationTest {
    @Test fun pendingReportsFollowOfficialRelocationWithoutCrossingAccountsOrProjects() {
        val directory = Files.createTempDirectory("getvideo-outbox-migration").toFile()
        try {
            val box = FailureOutbox(directory)
            val id = box.enqueue("https://43.142.37.199:8443/", "acct_fixture", "sample", "test", emptyList())
            val pending = box.eligible("https://43.142.37.199/getvideo/", "acct_fixture")
            assertEquals(id, pending.single().getJSONObject("report").getString("id"))
            assertTrue(box.eligible("https://43.142.37.199/getvideo/", "acct_other").isEmpty())
            assertTrue(box.eligible("https://43.142.37.199/getaudio/", "acct_fixture").isEmpty())
            assertTrue(box.eligible("https://custom.example/", "acct_fixture").isEmpty())
        } finally { directory.deleteRecursively() }
    }
}
