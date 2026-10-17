package com.getvideo.app.data.repo

import kotlinx.serialization.json.*

internal val recreationActiveStates = setOf("queued", "running", "submitting", "composing", "cancel_requested")
internal fun recreationStateLabel(state: String): String = when (state) {
    "uploaded" -> "素材已上传，等待解析"
    "queued" -> "排队中"
    "analyzing" -> "正在解析视频"
    "ready" -> "详细描述已完成"
    "running", "submitting" -> "生成中"
    "composing" -> "正在合成视频"
    "succeeded" -> "已完成"
    "pending" -> "等待处理"
    "failed" -> "任务失败"
    "submission_unknown" -> "提交结果待核对"
    "cancel_requested" -> "正在停止后续片段"
    "canceled" -> "已停止"
    else -> "处理中"
}

/** Render structured audio observations without exposing JSON syntax in the reading flow. */
internal fun recreationAudioText(raw: String): String {
    val labels = mapOf("analysis_status" to "分析状态", "speech_segments" to "语音", "music_segments" to "音乐", "sound_events" to "声音事件", "uncertainties" to "待核实", "start" to "开始", "end" to "结束", "start_sec" to "开始秒数", "end_sec" to "结束秒数", "speaker" to "说话者", "speaker_id" to "说话者", "text" to "内容", "transcript" to "原话", "language" to "语言", "description" to "声音描述", "uncertain_spans" to "听不清的片段", "tone" to "语气", "pace" to "语速")
    fun render(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.joinToString("\n") { (key, item) -> "${labels[key] ?: key}：${render(item)}" }
        is JsonArray -> if (value.isEmpty()) "无" else value.joinToString("；") { render(it) }
        is JsonPrimitive -> when (value.content) { "silent" -> "无声"; "not_analyzed" -> "未分析"; "unknown" -> "无法辨认"; "completed", "success", "analyzed" -> "已完成"; else -> value.content }
        else -> "无"
    }
    return runCatching { render(Json.parseToJsonElement(raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim())) }.getOrDefault(raw)
}
