package com.getvideo.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.getvideo.app.data.local.TrackEntity
import com.getvideo.app.data.remote.*
import com.getvideo.app.data.repo.*
import kotlinx.coroutines.*
import java.io.File

@Composable
fun RecreationScreen(initialTrack: TrackEntity? = null) {
    val context = LocalContext.current
    val account by AccountStore.current.collectAsState()
    val server by Settings(context).serverUrl.collectAsState(initial = "")
    var login by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    val active = account?.takeIf { it.serverUrl == server }
    if (active == null) Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI 视频重建", style = MaterialTheme.typography.headlineSmall)
        Text("登录服务器后，将视频解析为可编辑的分镜文字，再生成新视频。模型凭据由服务器管理。")
        Button(onClick = { login = true }) { Text("登录服务器账号") }
        TextButton(onClick = { settings = true }) { Text("服务器设置") }
    } else key(active) { RecreationContent(remember(active) { RecreationRepository(context, active) }, initialTrack) }
    if (login) AccountLoginDialog { login = false }
    if (settings) ServerSettingsDialog { settings = false }
}

@Composable
internal fun RecreationDialog(track: TrackEntity, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) { Column {
            TextButton(onClick = onClose) { Text("返回视频库") }
            RecreationScreen(track)
        } }
    }
}

@Composable
private fun RecreationContent(repo: RecreationRepository, initialTrack: TrackEntity?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var caps by remember { mutableStateOf<RecreationCapabilities?>(null) }
    var projects by remember { mutableStateOf<List<RecreationProjectDto>>(emptyList()) }
    var project by remember { mutableStateOf<RecreationProjectDto?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var audio by remember { mutableStateOf(true) }
    var mode by remember { mutableStateOf("text") }
    var resolution by remember { mutableStateOf("480p") }
    var keepAudio by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf(true) }
    var acknowledge by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var drafts by remember { mutableStateOf<Map<String,String>>(emptyMap()) }
    var guidance by remember { mutableStateOf("") }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var exportFile by remember { mutableStateOf<File?>(null) }
    var track by remember(initialTrack) { mutableStateOf(initialTrack) }
    var chooseTrack by remember { mutableStateOf(false) }
    val tracks by remember { TrackRepository(context).observeAll() }.collectAsState(initial = emptyList())
    fun accept(p: RecreationProjectDto) { project = p; drafts = p.clips.associate { it.id to it.description }; guidance = p.guidance }
    fun operate(block: suspend () -> Unit) {
        if (busy) return
        busy = true; notice = null
        scope.launch { try { block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = ApiProvider.humanMessage(e) }
            finally { busy = false } }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) operate { accept(repo.uploadUri(uri, audio, caps?.maxBytes ?: 268435456L)); projects = repo.projects() }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val file = exportFile
        if (uri != null && file != null) operate {
            withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("无法写入导出位置") }
            notice = "视频已导出"
        }
    }
    LaunchedEffect(repo) {
        try { caps = repo.capabilities(); projects = repo.projects(); repo.lastId()?.let { id -> projects.firstOrNull { it.id == id }?.let { accept(it) } } }
        catch (e: CancellationException) { throw e } catch (_: Exception) { notice = "无法加载任务，请检查登录与服务器连接" }
    }
    val pid = project?.id
    val active = project?.let { p -> p.status in listOf("queued", "analyzing") || p.runs.any { it.status in recreationActiveStates } } == true
    LaunchedEffect(pid, active) {
        while (pid != null && active) {
            delay(4000)
            if (!busy) try {
                val updated = repo.project(pid)
                if (project?.status != "ready" && updated.status == "ready") accept(updated) else project = updated
            } catch (e: CancellationException) { throw e } catch (_: Exception) { notice = "暂时无法刷新，服务器任务将继续处理" }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AI 视频重建", style = MaterialTheme.typography.headlineSmall)
        Text("视频 → 详细分镜文字 → 新视频。已提交任务在关闭页面后仍会处理，可回来查看。")
        if (caps?.configured == false) Text("服务器尚未配置模型")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        HorizontalDivider()
        Text("1 · 选择原视频", style = MaterialTheme.typography.titleMedium)
        Text("MP4 / MOV，最多 5 分钟、256 MB。上传只创建素材，点击解析后才会调用模型。")
        Row { Checkbox(audio, { audio = it }, enabled = !busy); Text("同时分析原视频声音", Modifier.padding(top = 12.dp)) }
        TextButton(onClick = { chooseTrack = true }, enabled = !busy) { Text(track?.title ?: "从视频库选择") }
        track?.let { selected -> Button(onClick = { operate { accept(repo.uploadTrack(selected, audio)); projects = repo.projects() } }, enabled = !busy) { Text("上传选中视频") } }
        OutlinedButton(onClick = { pick.launch(arrayOf("video/mp4", "video/quicktime")) }, enabled = !busy) { Text("从手机文件选择并上传") }
        TextButton(onClick = { operate { projects = repo.projects(); project?.let { accept(repo.project(it.id)) } } }, enabled = !busy) { Text("刷新任务并载入已保存文字") }
        projects.forEach { item -> TextButton(onClick = { operate { accept(repo.project(item.id)) } }, enabled = !busy) { Text("${item.title} · ${recreationStateLabel(item.status)}") } }
        project?.let { p ->
            HorizontalDivider()
            Text("${p.title} · ${"%.1f".format(p.duration)} 秒", style = MaterialTheme.typography.titleMedium)
            Text("状态：${recreationStateLabel(p.status)}")
            p.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = { operate { previewFile = repo.download(p) } }, enabled = !busy) { Text("查看原视频") }
            if (p.status in listOf("uploaded", "failed")) {
                Button(onClick = { operate { accept(repo.analyze(p)) } }, enabled = !busy && caps?.configured == true) { Text(if (p.status == "failed") "重试未完成的解析" else "解析成详细文字") }
                Text("解析会将视频发给模型平台，按平台实际额度与价格计费。")
            }
            Text("2 · 检查和编辑分镜", style = MaterialTheme.typography.titleMedium)
            p.clips.forEach { clip ->
                Text("片段 ${clip.position + 1} · ${"%.2f".format(clip.start)}–${"%.2f".format(clip.end)} 秒 · ${recreationStateLabel(clip.status)}")
                clip.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (clip.description.isNotBlank()) {
                    OutlinedTextField(drafts[clip.id] ?: clip.description, { if (it.length <= 4000) drafts = drafts + (clip.id to it) }, Modifier.fillMaxWidth(), minLines = 4, maxLines = 14, enabled = p.status == "ready" && !busy, label = { Text("画面、动作和镜头细节") })
                    TextButton(onClick = { drafts = drafts + (clip.id to clip.originalDescription) }, enabled = !busy) { Text("恢复模型原始描述") }
                }
                if (clip.audioDescription.isNotBlank()) Text("原音轨记录：\n${recreationAudioText(clip.audioDescription)}")
                if (clip.uncertainties.isNotEmpty()) Text("需要核实：${clip.uncertainties.joinToString("；")}")
            }
            if (p.status == "ready") {
                OutlinedTextField(guidance, { if (it.length <= 600) guidance = it }, Modifier.fillMaxWidth(), label = { Text("补充要求（可选）") }, enabled = !busy)
                OutlinedButton(onClick = { operate { accept(repo.edit(p, guidance, drafts)); notice = "描述已保存" } }, enabled = !busy) { Text("保存描述") }
                Text("3 · 生成新视频", style = MaterialTheme.typography.titleMedium)
                Text("首帧参考会额外发送每段第一帧以约束外观。生成无法保证复刻，片段之间可能有跳变。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(mode == "text", { mode = "text" }, label = { Text("纯文字") })
                    FilterChip(mode == "first_frame", { mode = "first_frame" }, label = { Text("首帧参考") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("480p", "720p", "1080p").forEach { r -> FilterChip(resolution == r, { resolution = r }, label = { Text(r) }) } }
                Row { Checkbox(preview, { preview = it }); Text("先生成第一段预览（最多 10 秒）", Modifier.padding(top = 12.dp)) }
                if (p.hasAudio) Row { Checkbox(keepAudio, { keepAudio = it }); Text("合成原视频音轨", Modifier.padding(top = 12.dp)) }
                Text("当前模型只生成画面；不合成原音轨时输出无声视频。默认 480p，减少试错费用。")
                if (p.runs.any { it.status == "submission_unknown" }) Row { Checkbox(acknowledge, { acknowledge = it }); Text("已核对平台任务，确认重新提交可能重复计费", Modifier.padding(top = 12.dp)) }
                Button(onClick = { confirm = true }, enabled = !busy && p.runs.none { it.status in recreationActiveStates } && (acknowledge || p.runs.none { it.status == "submission_unknown" })) { Text("确认描述并生成") }
            }
            p.runs.forEach { run ->
                HorizontalDivider()
                Text("${if (run.mode == "text") "文字生成" else "首帧参考"} · ${run.resolution} · 描述版本 ${run.revision}")
                Text("${recreationStateLabel(run.status)} · ${run.completed}/${run.total} 段")
                run.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (run.status in recreationActiveStates) TextButton(onClick = { operate { project = repo.cancel(p, run) } }, enabled = !busy) { Text("停止后续片段（已受理仍可能计费）") }
                if (run.status == "succeeded") {
                    TextButton(onClick = { operate { previewFile = repo.download(p, run) } }, enabled = !busy) { Text("预览生成视频") }
                    TextButton(onClick = { operate { exportFile = repo.download(p, run); export.launch("GetVideo-${run.id.take(8)}.mp4") } }, enabled = !busy) { Text("导出视频") }
                    Button(onClick = { operate { repo.save(p, run); notice = "已保存到视频库并下载到手机" } }, enabled = !busy) { Text("保存到视频库") }
                }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("提交视频生成？") }, text = { Text("将保存当前描述并生成${if (preview) "第一段预览" else "完整视频"}，$resolution。按模型平台实际用量计费，已产生费用无法撤销。") }, confirmButton = { TextButton(onClick = {
        confirm = false
        project?.let { p -> operate {
            val changed = guidance != p.guidance || p.clips.any { (drafts[it.id] ?: it.description) != it.description }
            val saved = if (changed) repo.edit(p, guidance, drafts).also { accept(it) } else p
            accept(repo.generate(saved, mode, resolution, keepAudio && p.hasAudio, preview, acknowledge))
        } }
    }) { Text("提交生成") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("返回修改") } })
    if (chooseTrack) AlertDialog(onDismissRequest = { chooseTrack = false }, title = { Text("选择视频库视频") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) { tracks.forEach { item -> TextButton(onClick = { track = item; chooseTrack = false }) { Text(item.title) } } }
    }, confirmButton = { TextButton(onClick = { chooseTrack = false }) { Text("关闭") } })
    previewFile?.let { file -> RecreationPreview(file) { previewFile = null } }
}

@Composable
private fun RecreationPreview(file: File, close: () -> Unit) {
    val context = LocalContext.current
    val player = remember(file) { ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file))); prepare()
    } }
    DisposableEffect(player) { onDispose { player.release() } }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface { Column(Modifier.fillMaxWidth().padding(12.dp)) {
            VideoSurface(player, Modifier.fillMaxWidth().height(300.dp))
            TextButton(onClick = close) { Text("关闭预览") }
        } }
    }
}
