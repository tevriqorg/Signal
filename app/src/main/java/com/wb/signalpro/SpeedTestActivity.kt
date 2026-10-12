package com.wb.signalpro

import android.app.Application
import android.os.Build
import android.os.Bundle
import android.telephony.SubscriptionManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

enum class SpeedDirection { DOWNLOAD, UPLOAD }
enum class IpMode { IPV4, IPV6 }

data class IperfServer(
    val id: String,
    val label: String,
    val region: String,
    val host: String,
    val ports: IntRange,
    val ipv6: Boolean = false,
    val capacity: String = "",
    val source: String = "community"
)

data class SpeedResult(
    val at: Long,
    val label: String,
    val host: String,
    val port: Int,
    val direction: SpeedDirection,
    val ipMode: IpMode,
    val parallel: Int,
    val mbps: Double,
    val localAddress: String,
    val remoteAddress: String,
    val operator: String,
    val rat: String,
    val bands: String,
    val rsrp: Int,
    val rsrq: Int,
    val sinr: Int
)

data class SpeedUiState(
    val selectedId: String = "chengdu",
    val direction: SpeedDirection = SpeedDirection.DOWNLOAD,
    val ipMode: IpMode = IpMode.IPV4,
    val parallel: Int = 4,
    val customHost: String = "",
    val customPort: String = "5201",
    val running: Boolean = false,
    val status: String = "选择一个测速点",
    val result: SpeedResult? = null,
    val history: List<SpeedResult> = emptyList()
)

class SpeedTestViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("speed_test_history", 0)
    private val _ui = MutableStateFlow(SpeedUiState(history = loadHistory()))
    val ui: StateFlow<SpeedUiState> = _ui.asStateFlow()
    private var runJob: Job? = null
    private var process: Process? = null

    fun selectServer(id: String) { if (!_ui.value.running) _ui.value = _ui.value.copy(selectedId = id, result = null) }
    fun setDirection(v: SpeedDirection) { if (!_ui.value.running) _ui.value = _ui.value.copy(direction = v, result = null) }
    fun setIpMode(v: IpMode) { if (!_ui.value.running) _ui.value = _ui.value.copy(ipMode = v, result = null) }
    fun setParallel(v: Int) { if (!_ui.value.running) _ui.value = _ui.value.copy(parallel = v, result = null) }
    fun setCustomHost(v: String) { if (!_ui.value.running) _ui.value = _ui.value.copy(customHost = v.trim()) }
    fun setCustomPort(v: String) { if (!_ui.value.running) _ui.value = _ui.value.copy(customPort = v.filter(Char::isDigit).take(5)) }

    fun stop() {
        runJob?.cancel()
        runJob = null
        runCatching { process?.destroy() }
        process = null
        _ui.value = _ui.value.copy(running = false, status = "已停止")
    }

    fun start() {
        if (_ui.value.running) return
        val state = _ui.value
        val server = resolveServer(state) ?: run {
            _ui.value = state.copy(status = "请填写有效的自定义主机和端口")
            return
        }
        if (state.ipMode == IpMode.IPV6 && !server.ipv6 && server.id != "custom") {
            _ui.value = state.copy(status = "这个公共节点没有标记 IPv6 支持；请选择 IPv4、香港 Leaseweb，或自定义 IPv6 节点")
            return
        }
        runJob = viewModelScope.launch {
            _ui.value = state.copy(running = true, status = "准备 iPerf3 · ${server.label}", result = null)
            try {
                val result = withContext(Dispatchers.IO) { runIperf(server, state) }
                val history = listOf(result) + _ui.value.history.take(9)
                saveHistory(history)
                _ui.value = _ui.value.copy(running = false, status = "测速完成", result = result, history = history)
            } catch (_: CancellationException) {
                _ui.value = _ui.value.copy(running = false, status = "已停止")
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(running = false, status = "测速失败：${shortError(t)}")
            } finally {
                process = null
            }
        }
    }

    private fun resolveServer(state: SpeedUiState): IperfServer? {
        if (state.selectedId != "custom") return SPEED_SERVERS.firstOrNull { it.id == state.selectedId }
        val port = state.customPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        if (state.customHost.isBlank()) return null
        return IperfServer("custom", "自定义", "用户输入", state.customHost, port..port, ipv6 = true, source = "custom")
    }

    private fun runIperf(server: IperfServer, state: SpeedUiState): SpeedResult {
        val app = getApplication<Application>()
        val binary = File(app.applicationInfo.nativeLibraryDir, "libiperf3.so")
        if (!binary.exists()) error("当前设备 ABI 没有内置 iPerf3")
        val ports = server.ports.take(5).toList()
        var lastError = "无法连接测速点"
        for ((index, port) in ports.withIndex()) {
            _ui.value = _ui.value.copy(status = "${server.label} · 端口 $port · ${index + 1}/${ports.size} · 10 秒")
            val args = mutableListOf(binary.absolutePath, "-c", server.host, "-p", port.toString(), "-J", "-t", "10", "-P", state.parallel.toString())
            args += if (state.ipMode == IpMode.IPV6) "-6" else "-4"
            if (state.direction == SpeedDirection.DOWNLOAD) args += "-R"
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            process = p
            val output = p.inputStream.bufferedReader().use { it.readText() }
            val code = p.waitFor()
            if (code == 0) return parseResult(output, server, port, state)
            lastError = parseIperfError(output)
            if (!retryable(output)) break
        }
        error(lastError)
    }

    private fun parseResult(raw: String, server: IperfServer, port: Int, state: SpeedUiState): SpeedResult {
        val json = JSONObject(raw)
        json.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
        val end = json.getJSONObject("end")
        val sum = if (state.direction == SpeedDirection.DOWNLOAD) end.optJSONObject("sum_received") else end.optJSONObject("sum_sent")
            ?: error("iPerf3 没有返回汇总速度")
        val bps = sum.optDouble("bits_per_second", Double.NaN)
        if (!bps.isFinite() || bps <= 0) error("iPerf3 返回的速度无效")
        val connected = json.optJSONObject("start")?.optJSONArray("connected")?.optJSONObject(0)
        val radio = captureRadio()
        return SpeedResult(
            at = System.currentTimeMillis(), label = server.label, host = server.host, port = port,
            direction = state.direction, ipMode = state.ipMode, parallel = state.parallel, mbps = bps / 1_000_000.0,
            localAddress = connected?.optString("local_host").orEmpty(), remoteAddress = connected?.optString("remote_host").orEmpty(),
            operator = radio.operator, rat = radio.rat, bands = radio.bands, rsrp = radio.rsrp, rsrq = radio.rsrq, sinr = radio.sinr
        )
    }

    private fun captureRadio(): SignalInfo.Snapshot {
        val activeSub = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) SubscriptionManager.getActiveDataSubscriptionId()
        else SubscriptionManager.getDefaultDataSubscriptionId()
        return SignalInfo.capture(getApplication(), activeSub)
    }

    private fun retryable(raw: String): Boolean {
        val s = raw.lowercase(Locale.US)
        return s.contains("server is busy") || s.contains("unable to connect") || s.contains("connection refused") || s.contains("timed out")
    }

    private fun parseIperfError(raw: String): String {
        return runCatching { JSONObject(raw).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: raw.lineSequence().lastOrNull { it.isNotBlank() }?.take(180)
            ?: "iPerf3 未返回错误信息"
    }

    private fun saveHistory(history: List<SpeedResult>) {
        val arr = JSONArray()
        history.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun loadHistory(): List<SpeedResult> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> runCatching { speedResultFromJson(arr.getJSONObject(i)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun shortError(t: Throwable) = (t.message ?: t.javaClass.simpleName).replace('\n', ' ').take(180)
}

class SpeedTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SignalTheme { SpeedTestScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpeedTestScreen(vm: SpeedTestViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("网络测速") }) }) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("iPerf3 单节点测速 · 不做多节点压力测试", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            item { Text("测速点", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(SPEED_SERVERS + CUSTOM_SERVER, key = { it.id }) { s ->
                ServerCard(s, state.selectedId == s.id, !state.running) { vm.selectServer(s.id) }
            }
            if (state.selectedId == "custom") {
                item {
                    ElevatedCard {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(state.customHost, vm::setCustomHost, Modifier.fillMaxWidth(), singleLine = true, label = { Text("域名或 IP") })
                            OutlinedTextField(state.customPort, vm::setCustomPort, Modifier.fillMaxWidth(), singleLine = true, label = { Text("端口") })
                        }
                    }
                }
            }
            item { SpeedOptions(state, vm) }
            item {
                ElevatedCard {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(state.status, style = MaterialTheme.typography.titleMedium)
                        if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::start, enabled = !state.running, modifier = Modifier.weight(1f)) { Text("开始测速") }
                            if (state.running) OutlinedButton(onClick = vm::stop) { Text("停止") }
                        }
                    }
                }
            }
            state.result?.let { r -> item { ResultCard(r) } }
            if (state.history.isNotEmpty()) {
                item { Text("最近记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                items(state.history, key = { "${it.at}-${it.host}-${it.port}" }) { HistoryCard(it) }
            }
            item {
                Text(
                    "公共 iPerf3 节点由社区维护，可用性和容量随时可能变化。测速会真实消耗流量：4 并发 × 10 秒不是 4 倍时长，而是用多条 TCP 流尽量填满同一条链路。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ServerCard(s: IperfServer, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, enabled = enabled) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text("${s.label} · ${s.region}", fontWeight = FontWeight.SemiBold)
                if (s.id != "custom") Text("${s.host} · ${portText(s.ports)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                val tags = buildList { if (s.ipv6) add("IPv6"); if (s.capacity.isNotBlank()) add(s.capacity) }.joinToString(" · ")
                if (tags.isNotBlank()) Text(tags, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SpeedOptions(state: SpeedUiState, vm: SpeedTestViewModel) {
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("测试参数", fontWeight = FontWeight.SemiBold)
            Text("方向", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.direction == SpeedDirection.DOWNLOAD, { vm.setDirection(SpeedDirection.DOWNLOAD) }, { Text("下载") }, enabled = !state.running)
                FilterChip(state.direction == SpeedDirection.UPLOAD, { vm.setDirection(SpeedDirection.UPLOAD) }, { Text("上传") }, enabled = !state.running)
            }
            Text("IP", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(state.ipMode == IpMode.IPV4, { vm.setIpMode(IpMode.IPV4) }, { Text("IPv4") }, enabled = !state.running)
                FilterChip(state.ipMode == IpMode.IPV6, { vm.setIpMode(IpMode.IPV6) }, { Text("IPv6") }, enabled = !state.running)
            }
            Text("并发 TCP 流", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 4, 8).forEach { p -> FilterChip(state.parallel == p, { vm.setParallel(p) }, { Text(p.toString()) }, enabled = !state.running) }
            }
        }
    }
}

@Composable
private fun ResultCard(r: SpeedResult) {
    ElevatedCard {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(String.format(Locale.US, "%.1f Mbps", r.mbps), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("${r.label} · ${if (r.direction == SpeedDirection.DOWNLOAD) "下载" else "上传"} · ${if (r.ipMode == IpMode.IPV6) "IPv6" else "IPv4"} · ${r.parallel} 流")
            Text("${r.host}:${r.port}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            if (r.remoteAddress.isNotBlank()) Text("远端 ${r.remoteAddress}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            if (r.localAddress.isNotBlank()) Text("本机 ${r.localAddress}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Divider()
            Text("${r.operator} · ${r.rat} · ${r.bands}", style = MaterialTheme.typography.bodySmall)
            val signal = listOfNotNull(
                r.rsrp.takeIf { it > SignalInfo.MINV }?.let { "RSRP $it dBm" },
                r.rsrq.takeIf { it > SignalInfo.MINV }?.let { "RSRQ $it dB" },
                r.sinr.takeIf { it > SignalInfo.MINV }?.let { "SINR $it dB" }
            ).joinToString(" · ")
            if (signal.isNotBlank()) Text(signal, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HistoryCard(r: SpeedResult) {
    ElevatedCard {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${r.label} · ${if (r.direction == SpeedDirection.DOWNLOAD) "↓" else "↑"} ${if (r.ipMode == IpMode.IPV6) "IPv6" else "IPv4"}", fontWeight = FontWeight.SemiBold)
                Text("${r.operator} · ${r.rat} · ${r.parallel} 流", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(String.format(Locale.US, "%.1f Mbps", r.mbps), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

private fun SpeedResult.toJson() = JSONObject().apply {
    put("at", at); put("label", label); put("host", host); put("port", port)
    put("direction", direction.name); put("ipMode", ipMode.name); put("parallel", parallel); put("mbps", mbps)
    put("localAddress", localAddress); put("remoteAddress", remoteAddress); put("operator", operator); put("rat", rat); put("bands", bands)
    put("rsrp", rsrp); put("rsrq", rsrq); put("sinr", sinr)
}

private fun speedResultFromJson(o: JSONObject) = SpeedResult(
    at = o.optLong("at"), label = o.optString("label"), host = o.optString("host"), port = o.optInt("port"),
    direction = runCatching { SpeedDirection.valueOf(o.optString("direction")) }.getOrDefault(SpeedDirection.DOWNLOAD),
    ipMode = runCatching { IpMode.valueOf(o.optString("ipMode")) }.getOrDefault(IpMode.IPV4),
    parallel = o.optInt("parallel", 4), mbps = o.optDouble("mbps"), localAddress = o.optString("localAddress"), remoteAddress = o.optString("remoteAddress"),
    operator = o.optString("operator"), rat = o.optString("rat"), bands = o.optString("bands"), rsrp = o.optInt("rsrp", SignalInfo.MINV),
    rsrq = o.optInt("rsrq", SignalInfo.MINV), sinr = o.optInt("sinr", SignalInfo.MINV)
)

private fun portText(range: IntRange) = if (range.first == range.last) range.first.toString() else "${range.first}-${range.last}"

private val SPEED_SERVERS = listOf(
    IperfServer("chengdu", "成都", "中国大陆", "116.169.118.91", 5201..5220),
    IperfServer("fuzhou", "福州", "中国大陆", "59.56.22.40", 5201..5220),
    IperfServer("nanjing", "南京", "中国大陆", "114.230.93.99", 5201..5220),
    IperfServer("ningbo", "宁波", "中国大陆", "122.228.230.122", 5201..5220),
    IperfServer("zhengzhou", "郑州", "中国大陆", "123.53.44.221", 5201..5220),
    IperfServer("hongkong", "香港", "公共节点", "84.17.57.129", 5201..5201, capacity = "2×10G"),
    IperfServer("leaseweb-hkg", "香港 Leaseweb", "香港", "speedtest.hkg12.hk.leaseweb.net", 5201..5210, ipv6 = true, capacity = "10G")
)
private val CUSTOM_SERVER = IperfServer("custom", "自定义", "域名 / IP", "", 5201..5201, ipv6 = true)
