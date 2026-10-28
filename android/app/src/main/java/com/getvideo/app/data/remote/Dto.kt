package com.getvideo.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 服务器返回的一条视频。字段名与 FastAPI 的 TrackOut 一一对应。 */
@Serializable
data class TrackDto(
    val id: Long,
    val platform: String,
    @SerialName("video_id") val videoId: String,
    val title: String,
    val uploader: String? = null,
    @SerialName("duration_sec") val durationSec: Double? = null,
    @SerialName("audio_bytes") val audioBytes: Long? = null,
    @SerialName("audio_format") val audioFormat: String,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("updated_seq") val updatedSeq: Long,
    @SerialName("updated_at_ms") val updatedAtMs: Long = 0,
    val deleted: Boolean,
    @SerialName("audio_sha256") val audioSha256: String? = null,
    val categories: List<String> = emptyList(),
    @SerialName("has_vocal") val hasVocal: Boolean? = null,
    @SerialName("classify_stage") val classifyStage: String? = null,
    @SerialName("download_url") val downloadUrl: String,
)

@Serializable
data class ExtractRequest(
    val text: String,
    val force: Boolean = false,
)

@Serializable
data class ExtractResponse(
    val track: TrackDto,
    val reused: Boolean,
)

@Serializable
data class SyncResponse(
    val items: List<TrackDto>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
)

@Serializable
data class LoginStartRequest(
    val platform: String,
)

@Serializable
data class LoginStartResponse(
    @SerialName("session_id") val sessionId: String,
    val platform: String,
    @SerialName("qr_url") val qrUrl: String,
    @SerialName("qr_image_url") val qrImageUrl: String,
    @SerialName("expires_in") val expiresIn: Int,
)

@Serializable
data class LoginPollResponse(
    @SerialName("session_id") val sessionId: String,
    val platform: String,
    /** waiting | scanned | confirmed | expired | failed */
    val state: String,
    val message: String,
)

@Serializable
data class CookieImportRequest(
    val platform: String,
    val cookies: String,
)

@Serializable
data class CredentialDto(
    val platform: String,
    val status: String,
    @SerialName("last_checked_at") val lastCheckedAt: String? = null,
)

/** FastAPI 的错误体统一是 {"detail": "..."}，解出来直接给用户看。 */
@Serializable
data class ApiError(val detail: String? = null)

@Serializable
data class AgentChatRequest(
    @SerialName("user_id") val userId: String,
    val message: String,
    @SerialName("local_tracks") val localTracks: List<AgentLocalTrack> = emptyList(),
)

@Serializable
data class AgentLocalTrack(
    val id: Long,
    val title: String,
    val artist: String? = null,
    val platform: String,
    @SerialName("duration_sec") val durationSec: Double? = null,
    val categories: List<String> = emptyList(),
)

@Serializable
data class AgentChatResponse(
    val answer: String,
    @SerialName("track_ids") val trackIds: List<Long>,
    val model: String? = null,
)

@Serializable
data class AgentMemoryRequest(
    @SerialName("user_id") val userId: String,
    val content: String,
)

@Serializable
data class AgentMemoryUpdate(
    val content: String,
    @SerialName("expected_revision") val expectedRevision: Int,
)

@Serializable
data class AgentMemoryOut(
    val id: Long,
    val content: String,
    val revision: Int,
)

@Serializable
data class AccountCredentials(val phone: String, val password: String)
@Serializable
data class AccountIdentity(@SerialName("user_id") val userId: String, val phone: String)
@Serializable
data class AccountSession(@SerialName("user_id") val userId: String, val phone: String,
    @SerialName("session_token") val sessionToken: String, @SerialName("expires_at") val expiresAt: String)
@Serializable
data class ExtractionAgentRequest(@SerialName("request_id") val requestId: String,
    @SerialName("conversation_id") val conversationId: String? = null, val message: String,
    @SerialName("local_tracks") val localTracks: List<AgentLocalTrack> = emptyList())
@Serializable
data class ExtractionAgentResponse(@SerialName("conversation_id") val conversationId: String,
    val answer: String, @SerialName("track_ids") val trackIds: List<Long> = emptyList(), val model: String? = null,
    @SerialName("memory_proposal") val memoryProposal: String? = null, val action: AgentActionDto? = null)
@Serializable
data class AgentActionDto(val id: String, @SerialName("input_url") val inputUrl: String,
    val status: String, val mode: String? = null, val result: AgentToolResult? = null)
@Serializable
data class AgentClaim(val mode: String)
@Serializable
data class AgentToolResult(val success: Boolean, @SerialName("track_id") val trackId: Long? = null,
    val title: String? = null, val diagnostic: String = "", val source: String? = null, val mode: String? = null)
@Serializable
data class AgentConversationDto(val id: String, @SerialName("created_at") val createdAt: String)
@Serializable
data class AgentEventDto(val role: String, val payload: kotlinx.serialization.json.JsonObject)
@Serializable
data class AgentHistoryDto(val events: List<AgentEventDto>, val actions: List<AgentActionDto>)

@Serializable data class MigrationClaim(val ticket: String)
@Serializable data class MigrationResult(val platforms: List<String>)


@Serializable
data class PlatformLoginCapability(
    val platform: String,
    val available: Boolean,
    val method: String,
    val note: String,
)
@Serializable
data class PlatformAccountBinding(
    val platform: String,
    val nickname: String,
)
@Serializable
data class PlatformAuthStart(
    val platform: String,
    val mode: String,
)
@Serializable
data class PlatformAuthTicket(
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("poll_token") val pollToken: String,
    val platform: String,
    val method: String,
    @SerialName("qr_url") val qrUrl: String? = null,
    @SerialName("authorization_url") val authorizationUrl: String? = null,
    @SerialName("expires_in") val expiresIn: Int,
)
@Serializable
data class PlatformAuthProof(
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("poll_token") val pollToken: String,
)
@Serializable
data class PlatformAuthStatus(
    val status: String,
    val nickname: String,
    val message: String,
)
@Serializable
data class PlatformAuthCompletion(
    val bound: Boolean,
    val platform: String,
    val nickname: String,
    val session: AccountSession? = null,
)

/** 提取失败上报：App 端在失败时把分享文案和脱敏日志暂存本地，联网后补传。 */
@Serializable
data class ExtractionFailureReport(
    val id: String,
    val version: String,
    @SerialName("occurred_at_ms") val occurredAtMs: Long,
    @SerialName("share_text") val shareText: String,
    val logs: List<String>,
)
@Serializable
data class ExtractionFailureAck(val id: String, val stored: Boolean)
@Serializable
data class ExtractionFailureDetail(
    val id: String,
    val version: String,
    @SerialName("occurred_at_ms") val occurredAtMs: Long,
    @SerialName("received_at") val receivedAt: String,
    @SerialName("share_text") val shareText: String,
    val logs: List<String>,
)
