package com.wb.signalpro

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.impa.icmpenguin.ProbeResult
import me.impa.icmpenguin.ping.Pinger
import me.impa.icmpenguin.trace.HopStatus
import me.impa.icmpenguin.trace.Response
import me.impa.icmpenguin.trace.SimpleTracer
import java.net.InetAddress
import java.util.Locale

data class DiagnosticUiState(
    val running: Boolean = false,
    val status: String = "选择一个常用目标即可开始",
    val lines: List<String> = emptyList(),
    val customExpanded: Boolean = false,
    val customTarget: String = ""
)

class DiagnosticsViewModel : ViewModel() {
    private val _ui = MutableStateFlow(DiagnosticUiState())
    val ui: StateFlow<DiagnosticUiState> = _ui.asStateFlow()
    private var operation: Job? = null

    fun setCustomExpanded(value: Boolean) { _ui.value = _ui.value.copy(customExpanded = value) }
    fun setCustomTarget(value: String) { _ui.value = _ui.value.copy(customTarget = value) }
    fun stop() { operation?.cancel(); operation = null; _ui.value = _ui.value.copy(running = false, status = "已停止") }

    fun ping(host: String, label: String) = launch("Ping · $label") {
        val lines = mutableListOf<String>()
        var count = 0
        Pinger(host = host, maxPingCount = 6, timeout = 3000).ping().collect { result ->
            count++
            lines += formatPing(result)
            _ui.value = _ui.value.copy(status = "Ping $label · $count / 6", lines = lines.toList())
        }
        _ui.value = _ui.value.copy(status = "Ping 完成 · $label", lines = lines)
    }

    fun trace(host: String, label: String) = launch("路由跟踪 · $label") {
        val latest = sortedMapOf<Int, HopStatus>()
        SimpleTracer(host = host, timeout = 3000, maxHops = 30, probesPerHop = 3).trace().collect { hop ->
            latest[hop.num] = hop
            _ui.value = _ui.value.copy(
                status = if (hop.isLast) "已到达 $label · ${hop.num} 跳" else "路由跟踪 $label · 第 ${hop.num} 跳",
                lines = formatHops(latest.values.toList())
            )
        }
    }

    fun dns(host: String, label: String) = launch("DNS · $label") {
        val addresses = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct()
        }
        _ui.value = _ui.value.copy(status = "DNS 完成 · $label", lines = addresses.mapIndexed { i, ip -> "● DNS ${i + 1}  $ip" })
    }

    private fun launch(initial: String, block: suspend () -> Unit) {
        operation?.cancel()
        operation = viewModelScope.launch {
            _ui.value = _ui.value.copy(running = true, status = initial, lines = emptyList())
            try { block() }
            catch (_: CancellationException) { _ui.value = _ui.value.copy(status = "已停止") }
            catch (t: Throwable) { _ui.value = _ui.value.copy(status = "测试失败：${t.message ?: t.javaClass.simpleName}") }
            finally { _ui.value = _ui.value.copy(running = false) }
        }
    }

    private fun formatPing(result: ProbeResult): String = when (result) {
        is ProbeResult.Success -> "● #${result.sequence}  ${result.remote}  ${ms(result.elapsedUsec)} ms"
        is ProbeResult.Timeout -> "● #${result.sequence}  超时"
        is ProbeResult.ConnectionRefused -> "● #${result.sequence}  ${result.offender} · 拒绝连接"
        is ProbeResult.HostUnreachable -> "● #${result.sequence}  ${result.offender} · 主机不可达"
        is ProbeResult.NetUnreachable -> "● #${result.sequence}  ${result.offender} · 网络不可达"
        is ProbeResult.NetError -> "● #${result.sequence}  网络错误 ${result.errNo}/${result.errCode}"
        is ProbeResult.Unknown -> "● #${result.sequence}  ${result.error}"
    }

    private fun formatHops(hops: List<HopStatus>) = hops.map { hop ->
        val ips = if (hop.ips.isEmpty()) listOf("*") else hop.ips.toList()
        val times = hop.probes.mapNotNull { (it as? Response.Success)?.timeUsec }
        val delay = if (times.isEmpty()) "超时" else "${ms(times.average().toInt())} ms"
        val role = when {
            hop.isLast -> "● 目标"
            ips.any { privateLike(it) } -> "◇ 接入 / 运营商内部（推断）"
            else -> "◇ 公网 / 骨干（待识别）"
        }
        "${hop.num}. ${ips.joinToString(" / ")}\n$delay · $role"
    }

    private fun privateLike(ip: String) = runCatching { InetAddress.getByName(ip).let { it.isSiteLocalAddress || it.isLinkLocalAddress || it.isLoopbackAddress } }.getOrDefault(false)
    private fun ms(usec: Int) = String.format(Locale.US, "%.1f", usec / 1000.0)
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

@Composable
private fun DiagnosticsScreen(network: String, eos: String, vm: DiagnosticsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val common = remember(eos) {
        buildList {
            add(QuickTarget("百度", "www.baidu.com", "国内"))
            add(QuickTarget("腾讯", "www.qq.com", "国内"))
            add(QuickTarget("Cloudflare", "1.1.1.1", "全球 DNS"))
            add(QuickTarget("Google", "8.8.8.8", "全球 DNS"))
            if (eos.isNotBlank()) add(QuickTarget("CMCloud EOS", eos, "上次实测目标"))
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("网络诊断") }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("● 当前实测出口：$network", color = MaterialTheme.colorScheme.primary) }
            item { QuickSection("Ping", "点目标立即从这台手机发起 Ping", common, state.running) { vm.ping(it.host, it.label) } }
            item { QuickSection("路由跟踪", "逐跳观察手机到目标的真实路径", common, state.running) { vm.trace(it.host, it.label) } }
            item {
                ElevatedCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.setCustomExpanded(!state.customExpanded) }, enabled = !state.running) { Text(if (state.customExpanded) "收起自定义目标" else "自定义目标") }
                    if (state.customExpanded) {
                        OutlinedTextField(state.customTarget, vm::setCustomTarget, Modifier.fillMaxWidth(), singleLine = true, label = { Text("域名或 IP") })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.ping(cleanHost(state.customTarget), state.customTarget) }, enabled = !state.running && state.customTarget.isNotBlank()) { Text("Ping") }
                            FilledTonalButton(onClick = { vm.trace(cleanHost(state.customTarget), state.customTarget) }, enabled = !state.running && state.customTarget.isNotBlank()) { Text("路由跟踪") }
                            TextButton(onClick = { vm.dns(cleanHost(state.customTarget), state.customTarget) }, enabled = !state.running && state.customTarget.isNotBlank()) { Text("DNS") }
                        }
                    }
                } }
            }
            item {
                ElevatedCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.status, style = MaterialTheme.typography.titleMedium)
                    if (state.running) { LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton(onClick = vm::stop) { Text("停止") } }
                } }
            }
            items(state.lines) { line -> ElevatedCard { Text(line, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) } }
        }
    }
}

@Composable
private fun QuickSection(title: String, subtitle: String, targets: List<QuickTarget>, disabled: Boolean, onPick: (QuickTarget) -> Unit) {
    ElevatedCard { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        targets.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { t -> FilledTonalButton(onClick = { onPick(t) }, enabled = !disabled, modifier = Modifier.weight(1f)) { Column { Text(t.label); Text(t.sub, style = MaterialTheme.typography.labelSmall) } } }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        } }
    } }
}

private fun cleanHost(value: String) = value.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
