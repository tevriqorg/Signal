package com.wb.signalpro

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.flow.asStateFlow
import me.impa.icmpenguin.trace.HopStatus
import me.impa.icmpenguin.trace.Response
import me.impa.icmpenguin.trace.SimpleTracer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class WoPanUi(val url: String = "", val draft: String = WoPanSettings.DEFAULT_URL, val probe: ProbeState = ProbeState.EMPTY, val message: String = "检查 WoPan 控制平面", val state: TestState = TestState.IDLE, val title: String = "", val summary: String = "", val stages: List<StageUi> = emptyList(), val trace: List<String> = emptyList(), val node: NodeUi? = null)

class WoPanViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(WoPanUi())
    val ui = _ui.asStateFlow()
    private var runJob: Job? = null
    private val stop = AtomicBoolean(false)
    private var activeRemoteId: String? = null
    private var activeClient: WoPanClient? = null
    private var traceJob: Job? = null
    private var currentRunId: String? = null
    private var currentDirection: String? = null
    private var currentStartedAtMs: Long = 0L
    private var currentReported = false
    init { val saved = WoPanSettings.getBaseUrl(app); _ui.value = _ui.value.copy(url = saved, draft = saved, probe = ProbeState.CHECKING); check(saved, false) }
    fun setDraft(v: String) { if (_ui.value.state != TestState.RUNNING) _ui.value = _ui.value.copy(draft = v) }
    fun saveAndCheck() { val u = _ui.value.draft.trim().trimEnd('/').takeIf { it.startsWith("https://") } ?: run { _ui.value = _ui.value.copy(probe = ProbeState.ERROR, message = "请输入 HTTPS 控制平面地址"); return }; check(u, true) }
    fun recheck() { check(_ui.value.url.ifBlank { _ui.value.draft }, false) }
    fun clear() { if (_ui.value.state == TestState.RUNNING) return; WoPanSettings.clearBaseUrl(getApplication()); _ui.value = _ui.value.copy(url = "", draft = "", probe = ProbeState.EMPTY, message = "检查 WoPan 控制平面") }
    private fun check(url: String, save: Boolean) {
        if (url.isBlank()) { _ui.value = _ui.value.copy(probe = ProbeState.EMPTY); return }
        _ui.value = _ui.value.copy(probe = ProbeState.CHECKING, message = "正在检查 WoPan 控制平面…")
        viewModelScope.launch { try {
            val h = withContext(Dispatchers.IO) { WoPanClient(url).health() }
            if (h.has("ok") && !h.optBoolean("ok")) error("health 检查失败")
            if (save) WoPanSettings.setBaseUrl(getApplication(), url)
            _ui.value = _ui.value.copy(url = url, draft = url, probe = ProbeState.READY, message = "已连接")
        } catch (t: Throwable) { _ui.value = _ui.value.copy(url = url, draft = url, probe = ProbeState.ERROR, message = shortError(t)) } }
    }

    fun serverToPhone() = start("tokyo_wopan_phone", "Tokyo → WoPan → 本机", listOf("Tokyo 准备随机文件", "Tokyo → WoPan", "WoPan → 本机", "SHA-256 校验", "清理 WoPan 文件")) { c ->
        stage(1, "Tokyo 正在准备随机文件", "远程任务运行中", 2, true)
        var job = withContext(Dispatchers.IO) { c.startPrepareDownloadJob() }; activeRemoteId = job.jobId
        var prepared: WoPanClient.Prepared? = null
        while (true) {
            ensureRunning(); job = withContext(Dispatchers.IO) { c.getRemoteJob(job.jobId) }
            when (job.phase) {
                "generating_sample", "selecting_sample" -> stage(1, "Tokyo 准备随机文件", if (job.totalBytes > 0) "${fmtBytes(job.progressBytes)} / ${fmtBytes(job.totalBytes)}" else job.phase, pct(job.progressBytes, job.totalBytes), true)
                "tokyo_to_wopan", "uploading_to_wopan" -> { done(1, "Tokyo 随机文件已准备", fmtBytes(job.totalBytes)); transfer(2, "Tokyo → WoPan", job.progressBytes, job.totalBytes, job.currentMbps, job.averageMbps) }
            }
            when (job.state) {
                "completed" -> { prepared = c.prepared(job); done(2, "Tokyo → WoPan 完成", "${prepared.name} · ${fmtBytes(prepared.size)} · ${fmtMbps(job.averageMbps)}"); break }
                "failed" -> error(job.error ?: "Tokyo → WoPan 失败")
                "cancelled" -> throw CancellationException("cancelled")
            }; delay(400)
        }
        activeRemoteId = null
        val p = prepared ?: error("Tokyo 未返回文件信息")
        stage(3, "WoPan → 本机", "获取下载地址", 2, true)
        val result = withContext(Dispatchers.IO) { c.download(p, { stop.get() }, { host -> WoPanSettings.setLastHost(getApplication(), host) }) { updateTransfer(3, "WoPan → 本机", it) } }
        done(3, "WoPan → 本机完成", "${fmtBytes(result.bytes)} · 平均 ${fmtMbps(result.averageMbps)}")
        if (result.sha256Match == true) done(4, "完整性校验通过", "SHA-256 ✓ · Tokyo 与本机内容一致") else { fail(4, "完整性校验失败", "SHA-256 ✗"); error("SHA-256 mismatch") }
        stage(5, "清理 WoPan 测试文件", "回收测试对象", 35, true)
        withContext(Dispatchers.IO) { c.delete(p.fid) }; done(5, "清理完成", "测试文件已删除")
        val rtt = withContext(Dispatchers.IO) { tcpRttMs(result.host) }
        reportSuccess(c, p.size, result.averageMbps, result.host, job.averageMbps, p.host, true, rtt)
        _ui.value = _ui.value.copy(summary = "完成 · Tokyo ${fmtMbps(job.averageMbps)} · 手机 ${fmtMbps(result.averageMbps)}")
        launchBackgroundTrace(result.host)
    }

    fun phoneToServer() = start("phone_wopan_tokyo", "本机 → WoPan → Tokyo", listOf("本机准备随机文件", "本机 → WoPan", "WoPan → Tokyo 实际下载", "Tokyo SHA-256 校验", "清理 WoPan 文件")) { c ->
        stage(1, "本机生成随机业务文件", "每次随机生成", 1, true)
        val sample = withContext(Dispatchers.IO) { SyntheticMedia.createRandom(getApplication<Application>().cacheDir) { done, total -> stageThread(1, "本机生成随机业务文件", "${fmtBytes(done)} / ${fmtBytes(total)}", pct(done, total)) } }
        try {
            done(1, "随机文件已准备", "${sample.displayName} · ${fmtBytes(sample.file.length())}")
            stage(2, "本机 → WoPan", "直接 multipart 上传", 2, true)
            val upload = withContext(Dispatchers.IO) { c.upload(sample, { stop.get() }, { host -> WoPanSettings.setLastHost(getApplication(), host) }) { updateTransfer(2, "本机 → WoPan", it) } }
            val fid = upload.fid?.takeIf { it.isNotBlank() } ?: error("WoPan 上传没有返回文件 ID")
            done(2, "本机 → WoPan 完成", "${fmtBytes(upload.bytes)} · 平均 ${fmtMbps(upload.averageMbps)}")
            stage(3, "WoPan → Tokyo 实际下载", "Tokyo 正在申请并验证文件", 3, true)
            var job = withContext(Dispatchers.IO) { c.startVerifyDownload(fid, sample.sha256, true) }; activeRemoteId = job.jobId
            while (true) {
                ensureRunning(); job = withContext(Dispatchers.IO) { c.getRemoteJob(job.jobId) }
                if (job.phase == "wopan_to_tokyo" || job.phase.contains("download", true) || job.phase.contains("verify", true)) transfer(3, "WoPan → Tokyo 实际下载", job.progressBytes, job.totalBytes, job.currentMbps, job.averageMbps)
                when (job.state) { "completed" -> break; "failed" -> error(job.error ?: "Tokyo 下载或校验失败"); "cancelled" -> throw CancellationException("cancelled") }
                delay(400)
            }; activeRemoteId = null
            val r = job.result ?: error("Tokyo 未返回校验结果")
            done(3, "WoPan → Tokyo 下载完成", "${fmtBytes(r.optLong("size"))} · 平均 ${fmtMbps(job.averageMbps)} · 远端实际校验")
            val shaOk = r.optBoolean("sha256_match", false)
            if (shaOk) done(4, "Tokyo SHA-256 校验通过", "SHA-256 ✓ · 手机与 Tokyo 内容一致") else { fail(4, "Tokyo SHA-256 校验失败", "SHA-256 ✗"); error("Tokyo SHA-256 mismatch") }
            done(5, "清理完成", if (r.optBoolean("trashed", false) || r.optBoolean("deleted", false)) "测试对象已回收" else "远端已执行校验任务")
            val rtt = withContext(Dispatchers.IO) { tcpRttMs(upload.host) }
            reportSuccess(c, upload.bytes, upload.averageMbps, upload.host, job.averageMbps, r.optString("host"), shaOk, rtt)
            _ui.value = _ui.value.copy(summary = "完成 · 手机 ${fmtMbps(upload.averageMbps)} · Tokyo ${fmtMbps(job.averageMbps)}")
            launchBackgroundTrace(upload.host)
        } finally { sample.file.delete() }
    }

    fun cancel() { if (_ui.value.state != TestState.RUNNING) return; stop.set(true); val id = activeRemoteId; val c = activeClient; if (id != null && c != null) viewModelScope.launch(Dispatchers.IO) { runCatching { c.cancelRemoteJob(id) } }; runJob?.cancel(); _ui.value = _ui.value.copy(state = TestState.CANCELLED, summary = "已取消") }
    private fun start(direction: String, title: String, titles: List<String>, block: suspend (WoPanClient) -> Unit) {
        if (_ui.value.probe != ProbeState.READY || _ui.value.state == TestState.RUNNING) return
        traceJob?.cancel(); currentRunId = UUID.randomUUID().toString(); currentDirection = direction
        currentStartedAtMs = System.currentTimeMillis(); currentReported = false
        stop.set(false); _ui.value = _ui.value.copy(state = TestState.RUNNING, title = title, summary = "开始测试", stages = titles.mapIndexed { i, t -> StageUi(i + 1, t) }, trace = emptyList())
        val c = WoPanClient(_ui.value.url); activeClient = c
        runJob = viewModelScope.launch {
            try { block(c); _ui.value = _ui.value.copy(state = TestState.SUCCESS) }
            catch (_: CancellationException) { _ui.value = _ui.value.copy(state = TestState.CANCELLED, summary = "已取消") }
            catch (t: Throwable) {
                reportFailure(c, shortError(t)); _ui.value = _ui.value.copy(state = TestState.ERROR, summary = "失败：${shortError(t)}")
            } finally { activeRemoteId = null; activeClient = null; stop.set(false) }
        }
    }

    private fun reportSuccess(c: WoPanClient, bytes: Long, phoneMbps: Double, phoneHost: String, tokyoMbps: Double, tokyoHost: String, shaOk: Boolean, rttMs: Double) {
        val payload = baseResult("success").put("bytes", bytes).put("phone_mbps", phoneMbps).put("phone_host", phoneHost)
            .put("tokyo_mbps", tokyoMbps).put("tokyo_host", tokyoHost).put("sha256_ok", shaOk).put("target_rtt_ms", rttMs)
        currentReported = true
        viewModelScope.launch(Dispatchers.IO) { runCatching { c.reportResult(payload) } }
    }

    private fun reportFailure(c: WoPanClient, message: String) {
        if (currentReported) return
        currentReported = true
        val payload = baseResult("failed").put("error", message)
        viewModelScope.launch(Dispatchers.IO) { runCatching { c.reportResult(payload) } }
    }

    private fun baseResult(status: String) = org.json.JSONObject()
        .put("run_id", currentRunId.orEmpty()).put("direction", currentDirection.orEmpty()).put("status", status)
        .put("started_at_ms", currentStartedAtMs).put("finished_at_ms", System.currentTimeMillis())
        .put("app_version", BuildConfig.VERSION_NAME).put("network", networkSnapshot())

    private fun networkSnapshot(): org.json.JSONObject {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
        }
        return org.json.JSONObject().put("transport", transports.joinToString("+").ifBlank { "unknown" })
            .put("metered", cm.isActiveNetworkMetered)
            .put("validated", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
            .put("roaming", caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) == false)
            .put("down_kbps", caps?.linkDownstreamBandwidthKbps ?: 0)
            .put("up_kbps", caps?.linkUpstreamBandwidthKbps ?: 0)
    }

    private fun tcpRttMs(host: String): Double = try {
        val started = System.nanoTime(); Socket().use { it.connect(InetSocketAddress(host, 443), 3000) }
        (System.nanoTime() - started) / 1_000_000.0
    } catch (_: Throwable) { 0.0 }

    private fun launchBackgroundTrace(host: String) {
        traceJob?.cancel()
        traceJob = viewModelScope.launch { traceBackground(host) }
    }

    private suspend fun traceBackground(host: String) = coroutineScope {
        val hops = sortedMapOf<Int, HopStatus>()
        try {
            withTimeout(15_000) {
                SimpleTracer(host = host, timeout = 1200, maxHops = 16, probesPerHop = 1).trace().collect { h ->
                    hops[h.num] = h
                    _ui.value = _ui.value.copy(trace = hops.values.map { hop ->
                        val ips = hop.ips.ifEmpty { listOf("*") }; val times = hop.probes.mapNotNull { (it as? Response.Success)?.timeUsec }
                        "${hop.num}. ${ips.joinToString(" / ")} · ${if (times.isEmpty()) "超时" else String.format(Locale.US, "%.1f ms", times.average() / 1000)} · ${if (hop.isLast) "● WoPan 目标" else "◇ 路由节点"}"
                    })
                }
            }
        } catch (_: Throwable) { }
    }

    private fun ensureRunning() { if (stop.get()) throw CancellationException("cancelled") }
    private fun stage(id: Int, title: String, detail: String, progress: Int?, active: Boolean) { _ui.value = _ui.value.copy(stages = _ui.value.stages.map { if (it.id == id) it.copy(title = title, detail = detail, progress = progress, active = active) else it }) }
    private fun stageThread(id: Int, title: String, detail: String, progress: Int) { viewModelScope.launch { stage(id, title, detail, progress, true) } }
    private fun done(id: Int, title: String, detail: String) { _ui.value = _ui.value.copy(stages = _ui.value.stages.map { if (it.id == id) it.copy(title = title, detail = detail, progress = 100, active = false, done = true) else it }) }
    private fun fail(id: Int, title: String, detail: String) { _ui.value = _ui.value.copy(stages = _ui.value.stages.map { if (it.id == id) it.copy(title = title, detail = detail, active = false, failed = true) else it }) }
    private fun transfer(id: Int, title: String, done: Long, total: Long, current: Double, average: Double) { _ui.value = _ui.value.copy(stages = _ui.value.stages.map { if (it.id == id) it.copy(title = title, detail = "实时传输", progress = pct(done, total), active = true, doneBytes = done, totalBytes = total, currentMbps = current, averageMbps = average) else it }) }
    private fun updateTransfer(id: Int, title: String, p: WoPanClient.TransferProgress) { viewModelScope.launch { transfer(id, title, p.done, p.total, p.currentMbps, p.averageMbps) } }


}

class WoPanTestActivity : ComponentActivity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { SignalTheme { WoPanScreen() } } } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun WoPanScreen(vm: WoPanViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val s by vm.ui.collectAsStateWithLifecycle(); val running = s.state == TestState.RUNNING
    Scaffold(topBar = { TopAppBar(title = { Text("WoPan 真实链路") }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("WoPan 控制平面", style = MaterialTheme.typography.titleLarge)
                if (s.probe == ProbeState.EMPTY || s.probe == ProbeState.ERROR) OutlinedTextField(s.draft, vm::setDraft, Modifier.fillMaxWidth(), singleLine = true, label = { Text("HTTPS 地址") }, enabled = !running)
                Text(s.message, color = if (s.probe == ProbeState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.probe == ProbeState.READY) Text(s.url, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.probe == ProbeState.EMPTY || s.probe == ProbeState.ERROR) Button(onClick = vm::saveAndCheck, enabled = !running && s.draft.isNotBlank()) { Text("保存并检查") }
                    if (s.probe == ProbeState.READY || s.probe == ProbeState.ERROR) OutlinedButton(onClick = vm::recheck, enabled = !running) { Text("重新检查") }
                    if (s.probe == ProbeState.READY) OutlinedButton(onClick = vm::clear, enabled = !running) { Text("清除") }
                }
            } } }
            item { routeCard("Tokyo → WoPan → 本机", "Tokyo VPS → WoPan → 这台手机", s.probe == ProbeState.READY && !running, vm::serverToPhone) }
            item { routeCard("本机 → WoPan → Tokyo", "这台手机 → WoPan → Tokyo VPS", s.probe == ProbeState.READY && !running, vm::phoneToServer) }
            if (s.stages.isNotEmpty()) {
                item { ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(s.title, style = MaterialTheme.typography.titleLarge); Text(s.summary); if (running) { LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton(onClick = vm::cancel) { Text("停止测试") } } } } }
                items(s.stages, key = { it.id }) { st -> ElevatedCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("${st.id}. ${st.title}", style = MaterialTheme.typography.titleMedium); Text(st.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (st.totalBytes > 0) { Text("${fmtBytes(st.doneBytes)} / ${fmtBytes(st.totalBytes)}"); Text("当前 ${fmtMbps(st.currentMbps)} · 平均 ${fmtMbps(st.averageMbps)}") }
                    st.progress?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
                } } }
                if (s.trace.isNotEmpty()) { item { Text("手机侧路由（后台，不阻塞测试）", style = MaterialTheme.typography.titleMedium) }; items(s.trace) { ElevatedCard { Text(it, Modifier.padding(12.dp)) } } }
            }
        }
    }
}
@Composable private fun routeCard(title: String, route: String, enabled: Boolean, go: () -> Unit) { ElevatedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleLarge); Text(route, color = MaterialTheme.colorScheme.primary); Button(onClick = go, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("开始完整链路测试") } } } }
private fun pct(done: Long, total: Long) = if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
private fun fmtBytes(v: Long) = when { v >= 1024L * 1024 -> String.format(Locale.US, "%.2f MiB", v / 1024.0 / 1024); v >= 1024 -> String.format(Locale.US, "%.1f KiB", v / 1024.0); else -> "$v B" }
private fun fmtMbps(v: Double) = String.format(Locale.US, "%.2f Mbps", v)
private fun shortError(t: Throwable) = (t.message ?: t.javaClass.simpleName).replace('\n', ' ').take(180)
