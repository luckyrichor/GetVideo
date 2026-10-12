package com.getvideo.app.data.repo

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class DiagnosticArchiveTest {
    @Test fun failuresSurviveSuccessAndReopening() {
        val dir = Files.createTempDirectory("diagnostics").toFile()
        val archive = DiagnosticArchive(dir)
        val first = archive.begin("local", "0.6.2")
        archive.record(first, "下载失败 HTTP 403")
        archive.finish(first, false)
        val success = archive.begin("server", "0.6.2")
        archive.record(success, "下载成功")
        archive.finish(success, true)
        val second = archive.begin("sync", "0.6.2")
        archive.record(second, "网络失败")
        archive.finish(second, false)
        val reopened = DiagnosticArchive(dir)
        val text = reopened.exportFailures()
        assertEquals(2, reopened.failureCount())
        assertTrue(text.contains("HTTP 403"))
        assertTrue(text.contains("网络失败"))
        assertFalse(text.contains("下载成功"))
        assertEquals(3, dir.listFiles()!!.size)
    }

    @Test fun unfinishedTaskAndSensitiveDataAreHandled() {
        val dir = Files.createTempDirectory("diagnostics").toFile()
        val archive = DiagnosticArchive(dir)
        val id = archive.begin("local", "0.6.2")
        archive.record(id, "地址 https://example.com/audio?token=secret Cookie: secret-cookie")
        listOf("token = credential-value", "{\"token\":\"credential-value\"}", "session: credential-value", "api_key = credential-value").forEach {
            archive.record(id, it)
        }
        val reopened = DiagnosticArchive(dir)
        assertEquals(1, reopened.failureCount())
        val text = reopened.exportFailures()
        assertTrue(text.contains("中断"))
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("example.com"))
        assertFalse(text.contains("credential-value"))
    }
}
