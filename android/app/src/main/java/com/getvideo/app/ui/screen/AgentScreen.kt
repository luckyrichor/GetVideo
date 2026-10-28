package com.getvideo.app.ui.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.getvideo.app.data.remote.*
import com.getvideo.app.ui.components.solidButtonColors
import com.getvideo.app.ui.components.uiCardColor
import com.getvideo.app.ui.components.uiSolidPage
import com.getvideo.app.data.repo.AgentOutbox
import com.getvideo.app.data.repo.accountResponseIsCurrent
import com.getvideo.app.data.repo.AccountStore
import com.getvideo.app.data.repo.ApiProvider
import com.getvideo.app.data.repo.Settings
import com.getvideo.app.data.repo.TrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID

class AgentViewModel(context: Context, private val owner: AccountStore.Account?) : ViewModel() {
    private val outbox = AgentOutbox(context.applicationContext)
    private val settings = Settings(context.applicationContext)
    private val tracks = TrackRepository(context.applicationContext)
    var serverReady by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var question by mutableStateOf("")
    var draft by mutableStateOf("")
    var editing by mutableStateOf<AgentMemoryOut?>(null)
    val dialogue = mutableStateListOf<Pair<String, String>>()
    val memories = mutableStateListOf<AgentMemoryOut>()
    val actions = mutableStateListOf<AgentActionDto>()
    val conversations = mutableStateListOf<AgentConversationDto>()
    var conversationId by mutableStateOf<String?>(null)
        private set
    var proposal by mutableStateOf<String?>(null)
        private set
    var diagnostic by mutableStateOf("")
        private set
    private var unsentResult: Pair<String, AgentToolResult>? = null
    var resultPending by mutableStateOf(false)
        private set
    private val activeUser = owner?.userId
    private val activeServer = owner?.serverUrl
    private var initialized = false

    private suspend fun ensureOwner() {
        val server = settings.serverUrlNow()
        check(accountResponseIsCurrent(owner, AccountStore.forServer(server), server)) {
            "账号或服务器已切换，请重试"
        }
    }

    private suspend fun apiAndUser(): Pair<GetVideoApi, String> {
        val url = settings.serverUrlNow()
        require(url.isNotBlank()) { "请先在「我的 → 服务器设置」填写服务器地址" }
        AccountStore.requireSecureServer(url)
        ensureOwner()
        val account = owner ?: error("请先在「我的 → 账号」登录，再使用视频 Agent")
        return ApiProvider.api(url, account) to account.userId
    }

    fun refresh() = viewModelScope.launch {
        error = null
        runCatching {
            serverReady = settings.serverUrlNow().isNotBlank()
            val (api, user) = apiAndUser()
            val server = settings.serverUrlNow()
            val scopeChanged = !initialized
            if (!initialized) {
                initialized = true
                val receipt = outbox.load(user, server)
                unsentResult = receipt?.let { it.actionId to it.result }
                resultPending = receipt != null
                conversationId = null
                dialogue.clear(); actions.clear(); proposal = null
            }
            conversations.clear()
            val loadedConversations = api.agentConversations()
            ensureOwner()
            conversations.addAll(loadedConversations)
            if (scopeChanged && conversationId == null && conversations.isNotEmpty()) loadHistory(api, conversations.first().id)
            api.agentMemories(user).also { ensureOwner() }
        }.onSuccess { memories.clear(); memories.addAll(it) }
            .onFailure { memories.clear(); dialogue.clear(); actions.clear(); conversations.clear(); error = ApiProvider.humanMessage(it) }
    }

    fun ask() = viewModelScope.launch {
        val text = question.trim()
        if (text.isEmpty() || busy || resultPending) return@launch
        question = ""
        busy = true
        error = null
        dialogue.add("我" to text)
        runCatching {
            val (api, _) = apiAndUser()
            val local = tracks.observeAll().first().asSequence().filter { it.id < 0 }.take(100)
                .map { AgentLocalTrack(it.id, it.title.take(200), it.uploader?.take(100),
                    it.platform, it.durationSec, it.categories.split(" · ").filter(String::isNotBlank).take(6)) }.toList()
            ensureOwner()
            api.extractionAgent(ExtractionAgentRequest(UUID.randomUUID().toString(), conversationId, text, local)).also { ensureOwner() }
        }.onSuccess {
            conversationId = it.conversationId
            dialogue.add("视频助手" to it.answer)
            it.action?.let { action -> actions.add(action) }
            proposal = it.memoryProposal
            val (api, _) = apiAndUser()
            val updatedConversations = api.agentConversations()
            ensureOwner()
            conversations.clear(); conversations.addAll(updatedConversations)
        }.onFailure { question = text; error = ApiProvider.humanMessage(it) }
        busy = false
    }

    private suspend fun loadHistory(api: GetVideoApi, id: String) {
        val history = api.agentHistory(id)
        ensureOwner()
        conversationId = id
        dialogue.clear()
        history.events.forEach { event ->
            when (event.role) {
                "user" -> event.payload["text"]?.jsonPrimitive?.contentOrNull?.let { dialogue.add("我" to it) }
                "assistant" -> event.payload["answer"]?.jsonPrimitive?.contentOrNull?.let { dialogue.add("视频助手" to it) }
                "tool" -> {
                    val success = event.payload["success"]?.jsonPrimitive?.contentOrNull == "true"
                    dialogue.add("提取结果" to if (success) "视频已保存：${event.payload["title"]?.jsonPrimitive?.contentOrNull ?: "视频"}" else
                        "提取失败：${event.payload["diagnostic"]?.jsonPrimitive?.contentOrNull ?: "请重试"}")
                }
            }
        }
        actions.clear(); actions.addAll(history.actions)
        proposal = null
    }

    fun openConversation(id: String) = viewModelScope.launch {
        if (busy || resultPending) return@launch
        busy = true
        runCatching { loadHistory(apiAndUser().first, id) }.onFailure { error = ApiProvider.humanMessage(it) }
        busy = false
    }

    fun newConversation() {
        if (busy || resultPending) return
        conversationId = null; dialogue.clear(); actions.clear(); proposal = null; error = null
    }

    fun clearConversation() = viewModelScope.launch {
        val id = conversationId ?: return@launch
        if (busy || resultPending) return@launch
        busy = true
        runCatching { apiAndUser().first.agentClear(id).also { ensureOwner() } }
            .onSuccess { conversationId = null; dialogue.clear(); actions.clear(); proposal = null; conversations.removeAll { it.id == id } }
            .onFailure { error = ApiProvider.humanMessage(it) }
        busy = false
    }

    fun acceptProposal() { draft = proposal.orEmpty(); proposal = null; editing = null }

    private fun replaceAction(action: AgentActionDto) {
        val index = actions.indexOfFirst { it.id == action.id }
        if (index >= 0) actions[index] = action else actions.add(action)
    }

    private suspend fun reportResult() {
        val pending = unsentResult ?: return
        val updated = apiAndUser().first.agentResult(pending.first, pending.second)
        ensureOwner()
        replaceAction(updated)
        outbox.clear(activeUser ?: error("请重新登录"), activeServer ?: error("请重新登录"))
        unsentResult = null; resultPending = false
    }

    fun retryReport() = viewModelScope.launch {
        if (busy || !resultPending) return@launch
        busy = true
        runCatching { reportResult() }.onFailure { error = "视频提取结果尚未同步：${ApiProvider.humanMessage(it)}" }
        busy = false
    }

    fun execute(action: AgentActionDto, mode: String) = viewModelScope.launch {
        if (busy || resultPending || action.status != "pending") return@launch
        busy = true; error = null; diagnostic = ""
        runCatching {
            val api = apiAndUser().first
            val claimed = api.agentClaim(action.id, AgentClaim(mode))
            try { ensureOwner() } catch (e: Exception) {
                outbox.save(AgentOutbox.Receipt(owner!!.userId, owner.serverUrl, action.id,
                    AgentToolResult(false, null, null, "账号已切换，提取未执行")))
                throw e
            }
            replaceAction(claimed)
            val steps = mutableListOf<String>()
            val observe: (String) -> Unit = { line ->
                if (AccountStore.current.value == owner) diagnostic = line
                steps.add(line.replace(Regex("https?://[^\\s]+"), "[链接]"))
            }
            val outcome = if (mode == "server") tracks.extractViaServer(action.inputUrl, observe, owner)
                          else tracks.extract(action.inputUrl, observe)
            val track = outcome.getOrNull()
            val failure = outcome.exceptionOrNull()
            val code = Regex("HTTP ?[0-9]{3}").find(failure?.message.orEmpty())?.value.orEmpty()
            val summary = if (track != null) "视频已保存到手机" else "${failure?.javaClass?.simpleName}: $code".trim()
            val result = AgentToolResult(track != null, if (mode == "server") track?.remoteTrackId else track?.id, track?.title?.take(200),
                (steps.takeLast(12).joinToString("\n") + "\n" + summary).takeLast(2000))
            outbox.save(AgentOutbox.Receipt(owner!!.userId, owner.serverUrl, action.id, result))
            ensureOwner()
            dialogue.add("提取结果" to if (track != null) "视频已加入视频库：${track.title}" else
                "提取失败：${failure?.let(ApiProvider::humanMessage) ?: "未知错误"}")
            unsentResult = action.id to result; resultPending = true
            reportResult()
        }.onFailure { error = ApiProvider.humanMessage(it) }
        busy = false
    }

    fun saveMemory() = viewModelScope.launch {
        val text = draft.trim()
        if (text.isEmpty() || busy) return@launch
        busy = true; error = null
        runCatching {
            val (api, user) = apiAndUser()
            val old = editing
            if (old == null) api.agentMemoryCreate(AgentMemoryRequest(user, text))
            else api.agentMemoryUpdate(user, old.id, AgentMemoryUpdate(text, old.revision))
            ensureOwner()
        }.onSuccess { draft = ""; editing = null; refresh() }
            .onFailure { error = ApiProvider.humanMessage(it) }
        busy = false
    }

    fun edit(memory: AgentMemoryOut) { editing = memory; draft = memory.content }

    fun delete(memory: AgentMemoryOut) = viewModelScope.launch {
        if (busy) return@launch
        busy = true; error = null
        runCatching { val (api, user) = apiAndUser(); api.agentMemoryDelete(user, memory.id); ensureOwner() }
            .onSuccess { refresh() }.onFailure { error = ApiProvider.humanMessage(it) }
        busy = false
    }

    class Factory(private val context: Context, private val owner: AccountStore.Account?) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AgentViewModel(context, owner) as T
    }
}

@Composable
fun AgentScreen() {
    val context = LocalContext.current
    val account by AccountStore.current.collectAsState()
    val server by remember(context) { Settings(context).serverUrl }.collectAsState(initial = "")
    var confirmClear by remember(account, server) { mutableStateOf(false) }
    val ownerKey = "agent-${account?.userId}-${System.identityHashCode(account)}-$server"
    val vm: AgentViewModel = viewModel(key = ownerKey, factory = AgentViewModel.Factory(context, account))
    LaunchedEffect(ownerKey) { vm.refresh() }
    Column(Modifier.fillMaxSize().background(uiSolidPage()).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("视频 Agent", style = MaterialTheme.typography.titleLarge)
        Text("发送抖音或 B站分享链接，助手会准备提取操作，由你选择手机或服务器执行。会话和结果保存在登录账号中；长期偏好需手动确认保存。提问、视频元数据和偏好会发送给方舟模型，视频文件不会上传给模型。", style = MaterialTheme.typography.bodySmall)
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (!vm.serverReady) Text("请先在「我的 → 服务器设置」填写服务器地址。")
        if (account == null) Text("请先返回「我的」，点击访客头像登录账号。")
        else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(colors = solidButtonColors(), onClick = vm::newConversation, enabled = !vm.busy && !vm.resultPending) { Text("新会话") }
                OutlinedButton(colors = solidButtonColors(), onClick = { confirmClear = true }, enabled = !vm.busy && !vm.resultPending && vm.conversationId != null) { Text("删除当前会话") }
            }
            if (vm.conversations.isNotEmpty()) {
                Text("最近会话（UTC）", style = MaterialTheme.typography.labelMedium)
                vm.conversations.take(5).forEach { conversation ->
                    TextButton(onClick = { vm.openConversation(conversation.id) }, enabled = !vm.busy && !vm.resultPending) {
                        Text(conversation.createdAt.take(19).replace("T", " ") + if (vm.conversationId == conversation.id) " · 当前" else "")
                    }
                }
            }
            vm.dialogue.forEach { (speaker, message) ->
                OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = uiCardColor())) {
                    Column(Modifier.padding(12.dp)) { Text(speaker, style = MaterialTheme.typography.labelMedium); Text(message) }
                }
            }
            vm.actions.forEach { action ->
                OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = uiCardColor())) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("提取操作 · ${when(action.status) { "pending" -> "等待确认"; "running" -> "已开始"; "done" -> "已完成"; else -> "失败" }}")
                        Text(action.inputUrl, style = MaterialTheme.typography.bodySmall)
                        if (action.status == "pending") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.execute(action, "phone") }, enabled = !vm.busy && !vm.resultPending) { Text("手机提取") }
                            OutlinedButton(colors = solidButtonColors(), onClick = { vm.execute(action, "server") }, enabled = !vm.busy && !vm.resultPending) { Text("服务器提取") }
                        }
                        action.result?.let { Text(if (it.success) "视频已保存：${it.title.orEmpty()}" else "提取失败：${it.diagnostic}") }
                        if (action.status == "running" && !vm.busy && !vm.resultPending) Text("若提取曾被中断，请检查视频库；需要重试时重新发送链接。")
                    }
                }
            }
            if (vm.busy) Text(vm.diagnostic.ifBlank { "助手处理中…" })
            if (vm.resultPending) OutlinedButton(colors = solidButtonColors(), onClick = vm::retryReport, enabled = !vm.busy) { Text("同步提取结果（不会重复下载）") }
            vm.proposal?.let {
                Text("建议保存的偏好：$it")
                OutlinedButton(colors = solidButtonColors(), onClick = vm::acceptProposal, enabled = !vm.busy) { Text("填入偏好，确认后保存") }
            }
            OutlinedTextField(vm.question, { vm.question = it.take(2000) }, label = { Text("分享链接或提取问题") },
                placeholder = { Text("例如：提取这个抖音链接，或者推荐治愈的钢琴曲") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Button(onClick = vm::ask, enabled = !vm.busy && !vm.resultPending && vm.question.isNotBlank()) { Text("发送") }
            HorizontalDivider()
            Text("我的长期偏好", style = MaterialTheme.typography.titleMedium)
            vm.memories.forEach { memory ->
                Row(Modifier.fillMaxWidth().regionFrame().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(memory.content, Modifier.weight(1f))
                    AreaButton(onClick = { vm.edit(memory) }, enabled = !vm.busy, tone = ActionTone.CONFIRM) { Text("修改") }
                    AreaButton(onClick = { vm.delete(memory) }, enabled = !vm.busy, tone = ActionTone.DESTRUCTIVE) { Text("删除") }
                }
            }
            OutlinedTextField(vm.draft, { vm.draft = it.take(500) }, label = { Text(if (vm.editing == null) "添加偏好" else "修改偏好") },
                placeholder = { Text("例如：我喜欢完整的古风视频，优先手机提取") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Button(onClick = vm::saveMemory, enabled = !vm.busy && vm.draft.isNotBlank()) { Text("确认保存偏好") }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("删除会话？") },
        text = { Text("删除当前会话和提取结果记录。已下载的视频与长期偏好保留。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearConversation() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}
