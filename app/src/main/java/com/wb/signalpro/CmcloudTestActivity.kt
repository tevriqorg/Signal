package com.wb.signalpro

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import me.impa.icmpenguin.trace.HopStatus
import me.impa.icmpenguin.trace.Response
import me.impa.icmpenguin.trace.SimpleTracer
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class ProbeState { EMPTY, CHECKING, READY, ERROR }
enum class TestState { IDLE, RUNNING, SUCCESS, ERROR, CANCELLED }

data class StageUi(
    val id: Int,
    val title: String,
    val detail: String = "等待",
    val progress: Int? = 0,
    val done: Boolean = false,
    val active: Boolean = false,
    val failed: Boolean = false
)

data class CmcloudUiState(
    val probeUrl: String = "",
    val probeDraft: String = "",
    val probeState: ProbeState = ProbeState.EMPTY,
    val probeMessage: String = "先配置 Remote Probe",
    val testState: TestState = TestState.IDLE,
    val testTitle: String = "",
    val summary: String = "",
    val stages: List<StageUi> = emptyList(),
    val traceLines: List<String> = emptyList()
)

class CmcloudViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(CmcloudUiState())
    val ui: StateFlow<CmcloudUiState> = _ui.asStateFlow()
    private var runJob: Job? = null
    private val stopRequested = AtomicBoolean(false)
    private var activeRemoteJobId: String? = null
    private var activeClient: CmcloudClient? = null

    init {
        val saved = ProbeSettings.getBaseUrl(app)
        if (saved.isNotBlank()) {
            _ui.value = _ui.value.copy(probeUrl = saved, probeDraft = saved, probeState = ProbeState.CHECKING, probeMessage = "正在恢复已保存的 Probe…")
            checkProbe(saved, saveOnSuccess = false)
        }
    }

    fun setProbeDraft(value: String) { if (_ui.value.probeState != ProbeState.READY && _ui.value.testState != TestState.RUNNING) _ui.value = _ui.value.copy(probeDraft = value) }

    fun saveAndCheck() {
        val url = normalize(_ui.value.probeDraft) ?: run {
            _ui.value = _ui.value.copy(probeState = ProbeState.ERROR, probeMessage = "Remote Probe 必须是 HTTPS 地址")
            return
        }
        checkProbe(url, saveOnSuccess = true)
    }

    fun recheck() {
        val url = _ui.value.probeUrl.ifBlank { _ui.value.probeDraft }
        if (url.isNotBlank()) checkProbe(url, saveOnSuccess = false)
    }

    fun clearProbe() {
        if (_ui.value.testState == TestState.RUNNING) return
        ProbeSettings.clearBaseUrl(getApplication())
        _ui.value = _ui.value.copy(probeUrl = "", probeDraft = "", probeState = ProbeState.EMPTY, probeMessage = "先配置 Remote Probe")
    }

    private fun checkProbe(url: String, saveOnSuccess: Boolean) {
        if (_ui.value.testState == TestState.RUNNING) return
        _ui.value = _ui.value.copy(probeState = ProbeState.CHECKING, probeMessage = "正在检查 Remote Probe…")
        viewModelScope.launch {
            try {
                val health = withContext(Dispatchers.IO) { CmcloudClient(url).health() }
                if (!health.optBoolean("ok", false)) error("Probe 返回异常状态")
                if (saveOnSuccess) ProbeSettings.setBaseUrl(getApplication(), url)
                _ui.value = _ui.value.copy(probeUrl = url, probeDraft = url, probeState = ProbeState.READY, probeMessage = "✓ 已连接 · 配置已固化")
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(probeUrl = url, probeDraft = url, probeState = ProbeState.ERROR, probeMessage = "Probe 不可用：${shortError(t)}")
            }
        }
    }

    fun startServerToPhone() = startTest(
        title = "服务器 → 云盘 → 本机",
        initialStages = listOf("Tokyo 准备随机业务文件", "Tokyo VPS → CMCloud", "CMCloud → 本机", "完整性校验", "手机侧路径分析", "清理测试文件")
    ) { client ->
        updateStage(1, "Tokyo 正在生成随机业务文件", "类型、内容、大小每次重新随机", 2, active = true)
        var job = withContext(Dispatchers.IO) { client.startPrepareDownloadJob() }
        activeRemoteJobId = job.jobId
        var prepared: CmcloudClient.PreparedDownload? = null
        while (true) {
            ensureRunning()
            job = withContext(Dispatchers.IO) { client.getRemoteJob(job.jobId) }
            when (job.phase) {
                "selecting_sample" -> updateStage(1, "Tokyo 选择随机样本", "不会使用 20/50/100 MiB 整齐测试包", 3, active = true)
                "generating_sample" -> updateStage(1, "Tokyo 生成随机业务文件", "${formatBytes(job.progressBytes)} / ${formatBytes(job.totalBytes)}", pct(job.progressBytes, job.totalBytes), active = true)
                "requesting_upload_capability" -> {
                    completeStage(1, "随机业务文件已准备", "${formatBytes(job.totalBytes)} · 正在申请上传 capability")
                    updateStage(2, "Tokyo VPS → CMCloud", "准备上传", 2, active = true)
                }
                "tokyo_to_cmcloud" -> updateTransferStage(2, "Tokyo VPS → CMCloud", job)
            }
            when (job.state) {
                "completed" -> { prepared = client.preparedFromJob(job); completeStage(2, "Tokyo VPS → CMCloud 完成", "${prepared.name} · ${formatBytes(prepared.size)} · 平均 ${fmtMbps(job.averageMbps)}"); break }
                "failed" -> error(job.error ?: "Tokyo 上传失败")
                "cancelled" -> throw CancellationException("remote cancelled")
            }
            delay(400)
        }
        activeRemoteJobId = null
        val p = prepared ?: error("Tokyo 没有返回测试样本")
        updateStage(3, "CMCloud → 本机", "申请下载 capability", 2, active = true)
        val result = withContext(Dispatchers.IO) {
            client.download(p, shouldCancel = { stopRequested.get() }, onCapability = { host -> ProbeSettings.setLastEos(getApplication(), host) }) { progress ->
                updateStageThreadsafe(3, "CMCloud → 本机", transferDetail(progress), pct(progress.done, progress.total))
            }
        }
        completeStage(3, "CMCloud → 本机完成", "${formatBytes(result.bytes)} · 平均 ${fmtMbps(result.averageMbps)}")
        if (result.sha256Match == true) completeStage(4, "完整性校验通过", "SHA-256 ✓ · 两端内容一致")
        else failStage(4, "完整性校验失败", "SHA-256 ✗").also { error("SHA-256 mismatch") }
        ProbeSettings.setLastEos(getApplication(), result.eosHost)
        traceEos(result.eosHost, 5)
        updateStage(6, "清理测试文件", "正在从 CMCloud 回收对象", 40, active = true)
        withContext(Dispatchers.IO) { client.trash(p.sessionId) }
        completeStage(6, "清理完成", "随机测试文件已从云盘回收")
        _ui.value = _ui.value.copy(summary = "完成 · Tokyo ${fmtMbps(job.averageMbps)} · 手机 ${fmtMbps(result.averageMbps)}")
    }

    fun startPhoneToServer() = startTest(
        title = "本机 → 云盘 → 服务器",
        initialStages = listOf("本机准备随机业务文件", "本机 → CMCloud", "CMCloud → Tokyo VPS", "Tokyo 完整性校验", "手机侧路径分析", "清理测试文件")
    ) { client ->
        updateStage(1, "本机生成随机业务文件", "类型、内容、大小每次重新随机", 1, active = true)
        val sample = withContext(Dispatchers.IO) {
            SyntheticMedia.createRandom(getApplication<Application>().cacheDir) { done, all ->
                updateStageThreadsafe(1, "本机生成随机业务文件", "${formatBytes(done)} / ${formatBytes(all)}", pct(done, all))
            }
        }
        try {
            completeStage(1, "随机业务文件已准备", "${sample.displayName} · ${formatBytes(sample.file.length())}")
            updateStage(2, "本机 → CMCloud", "申请上传 capability", 2, active = true)
            val upload = withContext(Dispatchers.IO) {
                client.upload(sample, shouldCancel = { stopRequested.get() }, onCapability = { host -> ProbeSettings.setLastEos(getApplication(), host) }) { progress ->
                    updateStageThreadsafe(2, "本机 → CMCloud", transferDetail(progress), pct(progress.done, progress.total))
                }
            }
            completeStage(2, "本机 → CMCloud 完成", "${formatBytes(upload.bytes)} · 平均 ${fmtMbps(upload.averageMbps)}")
            ProbeSettings.setLastEos(getApplication(), upload.eosHost)
            updateStage(3, "CMCloud → Tokyo VPS", "Tokyo 正在申请下载 capability", 2, active = true)
            var remote = withContext(Dispatchers.IO) { client.startVerifyDownloadJob(upload.sessionId ?: error("missing session"), sample.sha256, trash = true) }
            activeRemoteJobId = remote.jobId
            while (true) {
                ensureRunning()
                remote = withContext(Dispatchers.IO) { client.getRemoteJob(remote.jobId) }
                when (remote.phase) {
                    "requesting_download_capability" -> updateStage(3, "CMCloud → Tokyo VPS", "Tokyo 正在建立下载", 3, active = true)
                    "cmcloud_to_tokyo" -> updateTransferStage(3, "CMCloud → Tokyo VPS · 实际落盘", remote)
                }
                when (remote.state) {
                    "completed" -> break
                    "failed" -> error(remote.error ?: "Tokyo 下载失败")
                    "cancelled" -> throw CancellationException("remote cancelled")
                }
                delay(400)
            }
            activeRemoteJobId = null
            val verify = remote.result ?: error("Tokyo 没有返回校验结果")
            completeStage(3, "CMCloud → Tokyo VPS 完成", "${formatBytes(verify.optLong("size"))} · 平均 ${fmtMbps(remote.averageMbps)} · 已实际落盘")
            if (verify.optBoolean("sha256_match", false)) completeStage(4, "Tokyo 完整性校验通过", "SHA-256 ✓ · 本机与 Tokyo 内容一致")
            else failStage(4, "Tokyo 完整性校验失败", "SHA-256 ✗").also { error("Tokyo SHA-256 mismatch") }
            traceEos(upload.eosHost, 5)
            completeStage(6, "清理完成", if (verify.optBoolean("trashed", false)) "测试对象已从 CMCloud 回收" else "远端未确认清理")
            _ui.value = _ui.value.copy(summary = "完成 · 手机 ${fmtMbps(upload.averageMbps)} · Tokyo ${fmtMbps(remote.averageMbps)}")
        } finally { sample.file.delete() }
    }

    fun stopTest() {
        if (_ui.value.testState != TestState.RUNNING) return
        stopRequested.set(true)
        val id = activeRemoteJobId; val client = activeClient
        if (id != null && client != null) viewModelScope.launch(Dispatchers.IO) { runCatching { client.cancelRemoteJob(id) } }
        runJob?.cancel(); runJob = null
        _ui.value = _ui.value.copy(testState = TestState.CANCELLED, summary = "已取消")
    }

    private fun startTest(title: String, initialStages: List<String>, block: suspend (CmcloudClient) -> Unit) {
        if (_ui.value.probeState != ProbeState.READY || _ui.value.testState == TestState.RUNNING) return
        stopRequested.set(false)
        val stages = initialStages.mapIndexed { i, name -> StageUi(i + 1, name) }
        _ui.value = _ui.value.copy(testState = TestState.RUNNING, testTitle = title, summary = "开始测试", stages = stages, traceLines = emptyList())
        val client = CmcloudClient(_ui.value.probeUrl); activeClient = client
        runJob = viewModelScope.launch {
            try { block(client); _ui.value = _ui.value.copy(testState = TestState.SUCCESS) }
            catch (_: CancellationException) { _ui.value = _ui.value.copy(testState = TestState.CANCELLED, summary = "已取消") }
            catch (t: Throwable) { _ui.value = _ui.value.copy(testState = TestState.ERROR, summary = "失败：${shortError(t)}") }
            finally { activeRemoteJobId = null; activeClient = null; stopRequested.set(false) }
        }
    }

    private suspend fun traceEos(host: String, stageId: Int) {
        updateStage(stageId, "手机侧路径分析", "traceroute → $host", 4, active = true)
        val hops = sortedMapOf<Int, HopStatus>()
        try {
            SimpleTracer(host = host, timeout = 2500, maxHops = 30, probesPerHop = 2).trace().collect { hop ->
                hops[hop.num] = hop
                _ui.value = _ui.value.copy(traceLines = formatHops(hops.values.toList()))
                updateStage(stageId, "手机侧路径分析", if (hop.isLast) "已到达 $host · ${hop.num} 跳" else "正在测第 ${hop.num} 跳", if (hop.isLast) 100 else minOf(95, hop.num * 4), active = !hop.isLast)
            }
            completeStage(stageId, "手机侧路径分析完成", "记录 ${hops.size} 个 hop")
        } catch (t: Throwable) { completeStage(stageId, "路径分析已结束", "部分节点不响应：${shortError(t)}") }
    }

    private fun formatHops(hops: List<HopStatus>) = hops.map { hop ->
        val ips = if (hop.ips.isEmpty()) listOf("*") else hop.ips.toList()
        val times = hop.probes.mapNotNull { (it as? Response.Success)?.timeUsec }
        val delay = if (times.isEmpty()) "超时" else String.format(Locale.US, "%.1f ms", times.average() / 1000.0)
        val role = when { hop.isLast -> "● 目标 CMCloud EOS"; ips.any { privateLike(it) } -> "◇ 接入 / 运营商内部（推断）"; else -> "◇ 公网 / 骨干（待识别）" }
        "${hop.num}. ${ips.joinToString(" / ")} · $delay · $role"
    }

    private fun privateLike(ip: String) = runCatching { InetAddress.getByName(ip).let { it.isSiteLocalAddress || it.isLinkLocalAddress || it.isLoopbackAddress } }.getOrDefault(false)
    private fun ensureRunning() { if (stopRequested.get()) throw CancellationException("user stopped") }
    private fun updateTransferStage(id: Int, title: String, job: CmcloudClient.RemoteJob) = updateStage(id, title, "${formatBytes(job.progressBytes)} / ${formatBytes(job.totalBytes)} · 当前 ${fmtMbps(job.currentMbps)} · 平均 ${fmtMbps(job.averageMbps)}", pct(job.progressBytes, job.totalBytes), active = true)
    private fun transferDetail(p: CmcloudClient.TransferProgress) = "${formatBytes(p.done)} / ${formatBytes(p.total)} · 当前 ${fmtMbps(p.currentMbps)} · 平均 ${fmtMbps(p.averageMbps)}"

    private fun updateStageThreadsafe(id: Int, title: String, detail: String, progress: Int) { viewModelScope.launch { updateStage(id, title, detail, progress, active = true) } }
    private fun updateStage(id: Int, title: String, detail: String, progress: Int?, active: Boolean = false) { _ui.value = _ui.value.copy(stages = _ui.value.stages.map { if (it.id == id) it.copy(title = title, detail = detail, progress = progress, active = active, done = false, failed = false) else it }) }
    private fun completeStage(id: Int, title: String, detail: String) { _ui.value = _ui.value.copy(stages = _ui.value.stages.map { if (it.id == id) it.copy(title = title, detail = detail, progress = 100, active = false, done = true, failed = false) else it }) }
    private fun failStage(id: Int, title: String, detail: String) { _ui.value = _ui.value.copy(stages = _ui.value.stages.map { if (it.id == id) it.copy(title = title, detail = detail, active = false, failed = true) else it }) }
    private fun normalize(value: String): String? = value.trim().trimEnd('/').takeIf { it.startsWith("https://") }
    private fun pct(done: Long, total: Long) = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
    private fun fmtMbps(value: Double) = String.format(Locale.US, "%.2f Mbps", value)
    private fun formatBytes(value: Long): String = when { value >= 1024L*1024L -> String.format(Locale.US,"%.2f MiB",value/1024.0/1024.0); value >= 1024 -> String.format(Locale.US,"%.1f KiB",value/1024.0); else -> "$value B" }
    private fun shortError(t: Throwable) = (t.message ?: t.javaClass.simpleName).replace('\n',' ').take(180)
}

class CmcloudTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { SignalTheme { CmcloudScreen() } } }
}

@Composable
private fun CmcloudScreen(vm: CmcloudViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val running = state.testState == TestState.RUNNING
    Scaffold(topBar = { TopAppBar(title = { Text("CMCloud 真实链路") }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ProbeCard(state, vm, running) }
            item { TestRouteCard("服务器 → 云盘 → 本机", "Tokyo VPS → CMCloud → 这台手机", "同时观察服务器上传和云盘到手机", state.probeState == ProbeState.READY && !running) { vm.startServerToPhone() } }
            item { TestRouteCard("本机 → 云盘 → 服务器", "这台手机 → CMCloud → Tokyo VPS", "同时观察手机上传和 Tokyo 实际接收/落盘", state.probeState == ProbeState.READY && !running) { vm.startPhoneToServer() } }
            if (state.probeState != ProbeState.READY) item { Text("请先让 Remote Probe 变为“已连接”，测试入口才会启用。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.testState != TestState.IDLE || state.stages.isNotEmpty()) {
                item {
                    ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.testTitle, style = MaterialTheme.typography.titleLarge)
                        Text(state.summary, color = when(state.testState){ TestState.ERROR -> MaterialTheme.colorScheme.error; TestState.SUCCESS -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant })
                        if (running) { LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton(onClick = vm::stopTest) { Text("停止测试") } }
                        else if (state.testState == TestState.SUCCESS) Text("✓ 测试完成", color = MaterialTheme.colorScheme.primary)
                        else if (state.testState == TestState.CANCELLED) Text("已取消")
                    } }
                }
                items(state.stages) { StageCard(it) }
                if (state.traceLines.isNotEmpty()) {
                    item { Text("手机侧真实路由", style = MaterialTheme.typography.titleMedium) }
                    items(state.traceLines) { line -> ElevatedCard { Text(line, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) } }
                }
            }
        }
    }
}

@Composable
private fun ProbeCard(state: CmcloudUiState, vm: CmcloudViewModel, running: Boolean) {
    ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Remote Probe", style = MaterialTheme.typography.titleLarge)
        when (state.probeState) {
            ProbeState.EMPTY -> {
                OutlinedTextField(state.probeDraft, vm::setProbeDraft, Modifier.fillMaxWidth(), singleLine = true, label = { Text("HTTPS 地址") }, enabled = !running)
                Button(onClick = vm::saveAndCheck, enabled = !running && state.probeDraft.isNotBlank()) { Text("保存并检查") }
            }
            ProbeState.CHECKING -> { Text(state.probeDraft); LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在检查…") }
            ProbeState.READY -> {
                Text("✓ 已连接", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Text(state.probeUrl, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {}, enabled = false) { Text("✓ 已保存") }
                    OutlinedButton(onClick = vm::clearProbe, enabled = !running) { Text("清除") }
                }
            }
            ProbeState.ERROR -> {
                Text("⚠ Remote Probe 暂时不可用", color = MaterialTheme.colorScheme.error)
                Text(state.probeMessage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::recheck, enabled = !running) { Text("重新检查") }
                    OutlinedButton(onClick = vm::clearProbe, enabled = !running) { Text("清除") }
                }
            }
        }
    } }
}

@Composable
private fun TestRouteCard(title: String, route: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(route, color = MaterialTheme.colorScheme.primary)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("开始测试") }
    } }
}

@Composable
private fun StageCard(stage: StageUi) {
    ElevatedCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${stage.id}. ${stage.title}", style = MaterialTheme.typography.titleMedium, color = when { stage.failed -> MaterialTheme.colorScheme.error; stage.done -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurface })
        Text(stage.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
        stage.progress?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
        Text(when { stage.failed -> "失败"; stage.done -> "✓ 完成"; stage.active -> "● 进行中"; else -> "○ 等待" }, style = MaterialTheme.typography.labelMedium)
    } }
}
