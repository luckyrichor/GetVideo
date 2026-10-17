package com.getvideo.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RecreationCapabilities(
    val configured: Boolean,
    @SerialName("analysis_model") val analysisModel: String,
    @SerialName("generation_model") val generationModel: String,
    @SerialName("max_bytes") val maxBytes: Long,
    @SerialName("max_seconds") val maxSeconds: Double,
    @SerialName("clip_seconds") val clipSeconds: Int,
    val modes: List<String>,
    val resolutions: List<String>,
)
@Serializable
data class RecreationProjectDto(
    val id: String,
    val title: String,
    val status: String,
    val error: String? = null,
    val revision: Int,
    val guidance: String,
    val duration: Double,
    val width: Int,
    val height: Int,
    @SerialName("has_audio") val hasAudio: Boolean,
    @SerialName("analysis_model") val analysisModel: String,
    @SerialName("generation_model") val generationModel: String,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("source_sha256") val sourceSha256: String,
    val clips: List<RecreationClipDto>,
    val runs: List<RecreationRunDto>,
)
@Serializable
data class RecreationClipDto(
    val id: String,
    val position: Int,
    val start: Double,
    val end: Double,
    val status: String,
    val description: String,
    @SerialName("original_description") val originalDescription: String,
    @SerialName("audio_description") val audioDescription: String,
    val uncertainties: List<String>,
    val error: String? = null,
)
@Serializable
data class RecreationRunDto(
    val id: String,
    val status: String,
    val error: String? = null,
    val revision: Int,
    val mode: String,
    val resolution: String,
    @SerialName("keep_audio") val keepAudio: Boolean,
    val model: String,
    val total: Int,
    val completed: Int,
    val duration: Double,
    @SerialName("result_track_id") val resultTrackId: Long? = null,
    @SerialName("download_url") val downloadUrl: String? = null,
    @SerialName("usage_tokens") val usageTokens: Long = 0,
    @SerialName("result_sha256") val resultSha256: String? = null,
    @SerialName("result_bytes") val resultBytes: Long? = null,
)
@Serializable
data class RecreationKeyBody(
    @SerialName("request_key") val requestKey: String,
    @SerialName("analyze_audio") val analyzeAudio: Boolean = true,
)
@Serializable
data class RecreationAnalyzeBody(
    @SerialName("retry_failed") val retryFailed: Boolean = false,
)
@Serializable
data class RecreationClipEdit(val id: String, val description: String)
@Serializable
data class RecreationDescriptionEdit(
    val revision: Int,
    val guidance: String,
    val clips: List<RecreationClipEdit>,
)
@Serializable
data class RecreationGenerateBody(
    @SerialName("request_key") val requestKey: String,
    val revision: Int,
    val mode: String = "text",
    val resolution: String = "480p",
    @SerialName("keep_audio") val keepAudio: Boolean = true,
    val preview: Boolean = false,
    @SerialName("acknowledge_duplicate_risk") val acknowledgeDuplicateRisk: Boolean = false,
)
