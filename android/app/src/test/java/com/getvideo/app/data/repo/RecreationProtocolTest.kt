package com.getvideo.app.data.repo

import com.getvideo.app.data.remote.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecreationProtocolTest {
    @Test fun queuedGenerationUsesServerCredentialsAndStableRequestId() {
        val body = Json { encodeDefaults = true }.encodeToJsonElement(RecreationGenerateBody.serializer(), RecreationGenerateBody("stable-request", 3, "first_frame", "480p", true, true, false)).jsonObject
        assertEquals("stable-request", body["request_key"]!!.jsonPrimitive.content)
        assertEquals(3, body["revision"]!!.jsonPrimitive.int)
        assertTrue(body["preview"]!!.jsonPrimitive.boolean)
        assertEquals("first_frame", body["mode"]!!.jsonPrimitive.content)
        assertEquals(setOf("request_key", "revision", "mode", "resolution", "keep_audio", "preview", "acknowledge_duplicate_risk"), body.keys)
    }
    @Test fun recoveredTaskRetainsVerifiedDownloadAndUncertainty() {
        val json = """{"id":"p","title":"视频","status":"ready","revision":2,"guidance":"","duration":4.0,"width":160,"height":90,"has_audio":true,"analysis_model":"vision","generation_model":"fast","source_url":"/v2/recreations/p/source","source_sha256":"abc","clips":[{"id":"c","position":0,"start":0.0,"end":4.0,"status":"succeeded","description":"用户修订的详细分镜","original_description":"模型原始描述","audio_description":"连续声音","uncertainties":["小字无法辨认"]}],"runs":[{"id":"r","status":"succeeded","revision":1,"mode":"text","resolution":"480p","keep_audio":true,"model":"fast","total":1,"completed":1,"duration":4.0,"result_track_id":42,"download_url":"/v2/recreations/p/runs/r/video","result_sha256":"def","result_bytes":123,"usage_tokens":10}]}"""
        val p = Json.decodeFromString<RecreationProjectDto>(json)
        assertEquals("模型原始描述", p.clips.first().originalDescription)
        assertEquals("小字无法辨认", p.clips.first().uncertainties.single())
        assertEquals("def", p.runs.first().resultSha256)
        assertEquals(42L, p.runs.first().resultTrackId)
        assertEquals(1, p.runs.first().revision)
        assertEquals(2, p.revision)
    }
    @Test fun unknownSubmissionIsTerminalAndCannotPretendToBeRunning() {
        assertFalse("submission_unknown" in recreationActiveStates)
        assertTrue("cancel_requested" in recreationActiveStates)
        assertNotEquals("submission_unknown", recreationStateLabel("submission_unknown"))
    }
    @Test fun structuredAudioKeepsActualSpeechAndUncertaintyReadable() {
        val text = recreationAudioText("""{"analysis_status":"analyzed","speech_segments":[{"start":1.2,"end":2.0,"text":"你好","uncertain_spans":["unknown"]}],"music_segments":[]}""")
        assertTrue(text.contains("原话").not() && text.contains("内容：你好"))
        assertTrue(text.contains("无法辨认"))
        assertFalse(text.contains("speech_segments"))
        assertEquals("音轨未分析", recreationAudioText("音轨未分析"))
    }
}
