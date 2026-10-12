package com.getvideo.app.data.repo

import com.getvideo.app.data.remote.AgentToolResult
import com.getvideo.app.data.remote.AgentHistoryDto
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AgentProtocolTest {
    @Test fun resultRequestDoesNotInventEvidenceSource() {
        val body = Json.encodeToString(AgentToolResult.serializer(), AgentToolResult(true, -42, "手机歌曲", "已保存"))
        assertFalse(body.contains("source"))
        assertFalse(body.contains("mode"))
        assertTrue(body.contains("track_id"))
    }

    @Test fun historyRetainsActualToolEvidence() {
        val body = """{"events":[{"role":"tool","payload":{"success":false,"diagnostic":"HTTP 403","source":"device_reported"}}],"actions":[{"id":"action","input_url":"https://v.douyin.com/test/","status":"failed","mode":"phone","result":{"success":false,"diagnostic":"HTTP 403","source":"device_reported","mode":"phone"}}]}"""
        val history = Json.decodeFromString<AgentHistoryDto>(body)
        assertFalse(history.actions.first().result!!.success)
        assertEquals("device_reported", history.actions.first().result!!.source)
        assertEquals("tool", history.events.first().role)
    }
}
