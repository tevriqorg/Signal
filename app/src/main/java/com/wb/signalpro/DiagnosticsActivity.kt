package com.wb.signalpro

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.impa.icmpenguin.ProbeResult
import me.impa.icmpenguin.ping.Pinger
import me.impa.icmpenguin.trace.HopStatus
import me.impa.icmpenguin.trace.Response
import me.impa.icmpenguin.trace.SimpleTracer
import org.json.JSONObject
import java.net.InetAddress
import java.util.Locale
import kotlin.math.abs

enum class DiagnosticMode { PING, TRACE }

data class PingRow(val seq: Int, val remote: String, val rttMs: Double?, val status: String)
data class PingStats(val sent: Int, val received: Int, val lossPct: Double, val minMs: Double, val avgMs: Double, val maxMs: Double, val jitterMs: Double)

data class HopUi(
    val number: Int,
    val ip: String,
    val rttMinMs: Double?,
    val rttAvgMs: Double?,
    val rttMaxMs: Double?,
    val probes: Int,
    val timeouts: Int,
    val isLast: Boolean,
    val isPrivate: Boolean,
    val hostname: String = "",
    val asn: String = "",
    val asName: String = "",
    val provider: String = "",
    val city: String = "",
    val region: String = "",
    val countryCode: String = "",
    val source: String = "",
    val enrichmentLoading: Boolean = false
)

data class DiagnosticUiState(
    val mode: DiagnosticMode = DiagnosticMode.PING,
    val running: Boolean = false,
    val status: String = "选择一个常用目标即可开始",
    val targetLabel: String = "",
    val pingRows: List<PingRow> = emptyList(),
    val pingStats: PingStats? = null,
    val hops: List<HopUi> = emptyList(),
    val customExpanded: Boolean = false,
    val customTarget: String = ""
)

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(DiagnosticUiState())
    val ui: StateFlow<DiagnosticUiState> = _ui.asStateFlow()
    private var operation: Job? = null
    private val enrichmentJobs = mutableListOf<Job>()
    private val enrichGate = Semaphore(4)

    fun setMode(mode: DiagnosticMode) {
        if (_ui.value.running || _ui.value.mode == mode) return
        _ui.value = _ui.value.copy(mode = mode, status = "选择一个常用目标即可开始", targetLabel = "", pingRows = emptyList(), pingStats = null, hops = emptyList())
    }
    fun setCustomExpanded(value: Boolean) { if (!_ui.value.running) _ui.value = _ui.value.copy(customExpanded = value) }
    fun setCustomTarget(value: String) { if (!_ui.value.running) _ui.value = _ui.value.copy(customTarget = value) }
    fun runTarget(host: String, label: String) { if (_ui.value.mode == DiagnosticMode.PING) ping(host, label) else trace(host, label) }
    fun stop() { operation?.cancel(); operation = null; enrichmentJobs.forEach { it.cancel() }; enrichmentJobs.clear(); _ui.value = _ui.value.copy(running = false, status = "已停止") }

    private fun ping(host: String, label: String) = launch("Ping · $label", label) {
        val rows = mutableListOf<PingRow>()
        Pinger(host = host, maxPingCount = 8, timeout = 3000).ping().collect { result ->
            rows += pingRow(result)
            _ui.value = _ui.value.copy(
                status = "Ping $label · ${rows.size} / 8",
                pingRows = rows.toList(),
                pingStats = stats(rows)
            )
        }
        _ui.value = _ui.value.copy(status = "Ping 完成 · $label", pingRows = rows, pingStats = stats(rows))
    }

    private fun trace(host: String, label: String) = launch("路由跟踪 · $label", label) {
        val latest = sortedMapOf<Int, HopUi>()
        SimpleTracer(host = host, timeout = 3000, maxHops = 30, probesPerHop = 3).trace().collect { raw ->
            val previous = latest[raw.num]
            val hop = hopFrom(raw, previous)
            latest[raw.num] = hop
            _ui.value = _ui.value.copy(
                status = if (raw.isLast) "已到达 $label · ${raw.num} 跳" else "正在寻找 $label · 第 ${raw.num} 跳",
                hops = latest.values.toList()
            )
            if (hop.ip != "*" && !hop.isPrivate && (previous == null || previous.ip != hop.ip)) enrichHop(hop.number, hop.ip)
        }
        if (!_ui.value.status.startsWith("已到达")) _ui.value = _ui.value.copy(status = "路由跟踪结束 · $label")
    }

    private fun launch(initial: String, label: String, block: suspend () -> Unit) {
        operation?.cancel(); enrichmentJobs.forEach { it.cancel() }; enrichmentJobs.clear()
        operation = viewModelScope.launch {
            _ui.value = _ui.value.copy(running = true, status = initial, targetLabel = label, pingRows = emptyList(), pingStats = null, hops = emptyList())
            try { block() }
            catch (_: CancellationException) { _ui.value = _ui.value.copy(status = "已停止") }
            catch (t: Throwable) { _ui.value = _ui.value.copy(status = "测试失败：${shortError(t)}") }
            finally { _ui.value = _ui.value.copy(running = false) }
        }
    }

    private fun pingRow(result: ProbeResult): PingRow = when (result) {
        is ProbeResult.Success -> PingRow(result.sequence, result.remote.toString(), result.elapsedUsec / 1000.0, "收到回复")
        is ProbeResult.Timeout -> PingRow(result.sequence, "--", null, "超时")
        is ProbeResult.ConnectionRefused -> PingRow(result.sequence, result.offender.toString(), result.elapsedUsec / 1000.0, "目标拒绝连接")
        is ProbeResult.HostUnreachable -> PingRow(result.sequence, result.offender.toString(), result.elapsedUsec / 1000.0, "主机不可达")
        is ProbeResult.NetUnreachable -> PingRow(result.sequence, result.offender.toString(), result.elapsedUsec / 1000.0, "网络不可达")
        is ProbeResult.NetError -> PingRow(result.sequence, "--", null, "网络错误 ${result.errNo}/${result.errCode}")
        is ProbeResult.Unknown -> PingRow(result.sequence, "--", null, result.error.toString())
    }

    private fun stats(rows: List<PingRow>): PingStats {
        val values = rows.mapNotNull { it.rttMs }
        val avg = if (values.isEmpty()) 0.0 else values.average()
        val jitter = if (values.size < 2) 0.0 else values.zipWithNext().map { abs(it.second - it.first) }.average()
        return PingStats(rows.size, values.size, if (rows.isEmpty()) 0.0 else (rows.size - values.size) * 100.0 / rows.size,
            values.minOrNull() ?: 0.0, avg, values.maxOrNull() ?: 0.0, jitter)
    }

    private fun hopFrom(hop: HopStatus, previous: HopUi?): HopUi {
        val ip = hop.ips.firstOrNull() ?: "*"
        val times = hop.probes.map { (it as? Response.Success)?.timeUsec?.div(1000.0) }
        val ok = times.filterNotNull()
        val base = HopUi(
            number = hop.num, ip = ip,
            rttMinMs = ok.minOrNull(), rttAvgMs = ok.takeIf { it.isNotEmpty() }?.average(), rttMaxMs = ok.maxOrNull(),
            probes = times.size, timeouts = times.count { it == null }, isLast = hop.isLast,
            isPrivate = ip != "*" && isPrivateOrReserved(ip), enrichmentLoading = ip != "*" && !isPrivateOrReserved(ip)
        )
        return if (previous != null && previous.ip == ip) base.copy(
            hostname = previous.hostname, asn = previous.asn, asName = previous.asName, provider = previous.provider,
            city = previous.city, region = previous.region, countryCode = previous.countryCode, source = previous.source,
            enrichmentLoading = previous.enrichmentLoading
        ) else base
    }

    private fun enrichHop(number: Int, ip: String) {
        val job = viewModelScope.launch(Dispatchers.IO) {
            enrichGate.withPermit {
                val info = remoteNodeInfo(ip) ?: localNodeInfo(ip)
                withContext(Dispatchers.Main) {
                    _ui.value = _ui.value.copy(hops = _ui.value.hops.map { hop ->
                        if (hop.number != number || hop.ip != ip) hop else hop.copy(
                            hostname = info.optString("hostname"), asn = info.optString("asn"), asName = info.optString("as_name"),
                            provider = info.optString("provider"), city = info.optString("city"), region = info.optString("region"),
                            countryCode = info.optString("country_code"), source = info.optJSONArray("sources")?.let { arr ->
                                (0 until arr.length()).joinToString(" + ") { arr.optString(it) }
                            }.orEmpty(), enrichmentLoading = false
                        )
                    })
                }
            }
        }
        enrichmentJobs += job
    }

    private fun remoteNodeInfo(ip: String): JSONObject? {
        val base = ProbeSettings.getBaseUrl(getApplication())
        if (base.isBlank()) return null
        return runCatching { CmcloudClient(base).nodeInfo(ip) }.getOrNull()
    }

    private fun localNodeInfo(ip: String): JSONObject {
        val host = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull().takeUnless { it == ip }
        return JSONObject().apply { if (!host.isNullOrBlank()) put("hostname", host); put("sources", org.json.JSONArray().put("reverse DNS")) }
    }

    private fun isPrivateOrReserved(ip: String): Boolean {
        val a = runCatching { InetAddress.getByName(ip) }.getOrNull() ?: return true
        if (a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress || a.isAnyLocalAddress || a.isMulticastAddress) return true
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size == 4) {
            val x = p[0]; val y = p[1]
            if (x == 100 && y in 64..127) return true
            if (x == 198 && y in 18..19) return true
            if (x == 192 && y == 0) return true
            if (x == 198 && y == 51 && p.getOrNull(2) == 100) return true
            if (x == 203 && y == 0 && p.getOrNull(2) == 113) return true
        }
        return false
    }

    private fun shortError(t: Throwable) = (t.message ?: t.javaClass.simpleName).replace('\n', ' ').take(160)
}

class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val network = NetworkSnapshot.capture(this).summary()
        val eos = ProbeSettings.getLastEos(this)
        setContent { SignalTheme { DiagnosticsScreen(network, eos) } }
    }
}

private data class QuickTarget(val label: String, val host: String, val sub: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(network: String, eos: String, vm: DiagnosticsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val targets = remember(eos) {
        buildList {
            add(QuickTarget("百度", "www.baidu.com", "国内"))
            add(QuickTarget("腾讯", "www.qq.com", "国内"))
            add(QuickTarget("Cloudflare", "1.1.1.1", "全球 DNS"))
            add(QuickTarget("Google", "8.8.8.8", "全球 DNS"))
            if (eos.isNotBlank()) add(QuickTarget("CMCloud", eos, "上次 EOS"))
        }
    }
    val resultCount = if (state.mode == DiagnosticMode.PING) state.pingRows.size else state.hops.size
    LaunchedEffect(resultCount) {
        if (resultCount > 0) listState.animateScrollToItem(resultCount - 1)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("网络诊断") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("● $network", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                DiagnosticMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { vm.setMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, DiagnosticMode.entries.size),
                        enabled = !state.running
                    ) { Text(if (mode == DiagnosticMode.PING) "Ping" else "路由跟踪") }
                }
            }

            Text("常用目标 · 点一下立即测试", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(targets) { t ->
                    AssistChip(onClick = { vm.runTarget(t.host, t.label) }, enabled = !state.running,
                        label = { Column { Text(t.label, fontWeight = FontWeight.SemiBold); Text(t.sub, style = MaterialTheme.typography.labelSmall) } })
                }
                item { AssistChip(onClick = { vm.setCustomExpanded(!state.customExpanded) }, enabled = !state.running, label = { Text("自定义…") }) }
            }

            if (state.customExpanded) {
                ElevatedCard { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(state.customTarget, vm::setCustomTarget, Modifier.fillMaxWidth(), singleLine = true, label = { Text("域名或 IP") })
                    Button(onClick = { vm.runTarget(cleanHost(state.customTarget), state.customTarget) }, enabled = !state.running && state.customTarget.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (state.mode == DiagnosticMode.PING) "立即 Ping" else "立即路由跟踪")
                    }
                } }
            }

            ElevatedCard {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(state.status, style = MaterialTheme.typography.titleMedium)
                        if (state.targetLabel.isNotBlank()) Text(state.targetLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.running) OutlinedButton(onClick = vm::stop) { Text("停止") }
                }
            }

            if (state.mode == DiagnosticMode.PING) state.pingStats?.let { PingSummaryCard(it) }

            if (resultCount == 0) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(if (state.mode == DiagnosticMode.PING) "等待 Ping 结果" else "逐跳结果会实时出现在这里", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (state.mode == DiagnosticMode.PING) {
                        items(state.pingRows, key = { it.seq }) { PingResultCard(it) }
                    } else {
                        items(state.hops, key = { it.number }) { HopCard(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PingSummaryCard(s: PingStats) {
    ElevatedCard {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("发送", "${s.sent}"); Stat("收到", "${s.received}"); Stat("丢包", String.format(Locale.US, "%.0f%%", s.lossPct)); Stat("平均", String.format(Locale.US, "%.1f ms", s.avgMs))
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("最低", String.format(Locale.US, "%.1f", s.minMs)); Stat("最高", String.format(Locale.US, "%.1f", s.maxMs)); Stat("抖动", String.format(Locale.US, "%.1f ms", s.jitterMs))
        }
    }
}

@Composable private fun Stat(label: String, value: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontWeight = FontWeight.SemiBold); Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable
private fun PingResultCard(row: PingRow) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("#${row.seq}  ${row.remote}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            Text(row.rttMs?.let { String.format(Locale.US, "%.1f ms", it) } ?: row.status,
                color = if (row.rttMs == null) MaterialTheme.colorScheme.error else latencyColor(row.rttMs))
        }
    }
}

@Composable
private fun HopCard(hop: HopUi) {
    val timeout = hop.ip == "*"
    Card(colors = CardDefaults.cardColors(containerColor = if (timeout) MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f) else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text("${hop.number}", fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (timeout) "*  *  *" else hop.ip, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    if (!timeout && hop.hostname.isNotBlank()) Text(hop.hostname, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                hop.rttAvgMs?.let { Text(String.format(Locale.US, "%.1f ms", it), color = latencyColor(it), fontWeight = FontWeight.SemiBold) }
            }
            if (timeout) {
                Text("未收到这一跳的 ICMP 回复 · 不代表数据没有经过", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (hop.isPrivate) {
                Text("◇ 私有 / 运营商内部地址", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (hop.enrichmentLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在识别 ASN、运营商和位置…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val identity = listOfNotNull(hop.asn.takeIf { it.isNotBlank() }, hop.provider.takeIf { it.isNotBlank() } ?: hop.asName.takeIf { it.isNotBlank() }).joinToString(" · ")
                val place = listOf(hop.city, hop.region, hop.countryCode).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                if (identity.isNotBlank()) Text("◆ $identity", style = MaterialTheme.typography.labelMedium)
                if (place.isNotBlank()) Text("位置数据库：$place", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (identity.isBlank() && place.isBlank()) Text("◇ 节点已响应，但公开数据库未能识别", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (hop.source.isNotBlank()) Text("来源：${hop.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (hop.probes > 1 && hop.rttAvgMs != null) {
                Text("RTT  min ${fmt(hop.rttMinMs)} · avg ${fmt(hop.rttAvgMs)} · max ${fmt(hop.rttMaxMs)} · ${hop.timeouts}/${hop.probes} 超时",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (hop.isLast) Text("✓ 已到达目标", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable private fun latencyColor(ms: Double) = when { ms < 50 -> MaterialTheme.colorScheme.primary; ms < 150 -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.error }
private fun fmt(v: Double?) = v?.let { String.format(Locale.US, "%.1f ms", it) } ?: "--"
private fun cleanHost(value: String) = value.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
