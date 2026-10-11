package com.wb.signalpro

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.impa.icmpenguin.ProbeResult
import me.impa.icmpenguin.ping.Pinger
import me.impa.icmpenguin.trace.HopStatus
import me.impa.icmpenguin.trace.Response
import me.impa.icmpenguin.trace.SimpleTracer
import java.net.InetAddress
import java.util.Locale

class DiagnosticsActivity : Activity() {
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var operation: Job? = null
    private lateinit var target: EditText
    private lateinit var status: TextView
    private lateinit var resultBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(18), dp(16), dp(18), dp(12))
        }
        root.addView(text("网络路径诊断", 22f, GREEN, true))
        root.addView(text("真正从这台手机发包；不是服务器替手机测。", 11f, MUTED).apply {
            setPadding(0, dp(4), 0, dp(10))
        })

        val network = NetworkSnapshot.capture(this)
        root.addView(text("● 实测出口：${network.summary()}", 12f, GREEN))
        root.addView(text("手机 → ${network.activeTransport} → 目标", 12f, TEXT).apply {
            setPadding(0, dp(3), 0, dp(10))
        })

        target = EditText(this).apply {
            hint = "目标域名或 IP，例如 1.1.1.1"
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            setSingleLine(true)
            setBackgroundColor(PANEL)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(target, LinearLayout.LayoutParams(-1, -2))

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttons.addView(button("Ping") { runPing() }, weighted())
        buttons.addView(button("路由跟踪") { runTrace() }, weighted())
        buttons.addView(button("DNS") { runDns() }, weighted())
        buttons.addView(button("停止") { stopOperation() }, weighted())
        root.addView(buttons)

        status = text("等待测试", 12f, MUTED).apply { setPadding(0, dp(10), 0, dp(8)) }
        root.addView(status)

        val legend = text("● 实测 = 手机实际收到的网络结果    ◇ 推断 = 对节点角色的解释", 10f, MUTED)
        root.addView(legend)

        val scroll = ScrollView(this)
        resultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(20))
        }
        scroll.addView(resultBox)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun runPing() {
        val host = cleanTarget() ?: return
        start("正在从手机 Ping $host") {
            var count = 0
            Pinger(host = host, maxPingCount = 6, timeout = 3000).ping().collect { result ->
                count++
                appendResult(formatPing(result))
                status.text = "Ping $count / 6"
            }
            status.text = "Ping 完成"
        }
    }

    private fun runTrace() {
        val host = cleanTarget() ?: return
        start("正在从手机逐跳寻找 $host") {
            val latest = sortedMapOf<Int, HopStatus>()
            SimpleTracer(host = host, timeout = 3000, maxHops = 30, probesPerHop = 3).trace().collect { hop ->
                latest[hop.num] = hop
                renderHops(latest.values.toList())
                status.text = if (hop.isLast) "已到达目标 · 共 ${hop.num} 跳" else "正在测第 ${hop.num} 跳…"
            }
            if (!status.text.toString().startsWith("已到达")) status.text = "路由跟踪结束"
        }
    }

    private fun runDns() {
        val host = cleanTarget() ?: return
        start("正在解析 $host") {
            val addresses = withContext(Dispatchers.IO) {
                InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct()
            }
            if (addresses.isEmpty()) {
                appendResult("没有解析到地址")
            } else {
                addresses.forEachIndexed { index, address -> appendResult("● DNS ${index + 1}  $address") }
            }
            status.text = "DNS 解析完成"
        }
    }

    private fun start(message: String, block: suspend () -> Unit) {
        operation?.cancel()
        resultBox.removeAllViews()
        status.text = message
        operation = scope.launch {
            try {
                block()
            } catch (_: CancellationException) {
                status.text = "已停止"
            } catch (t: Throwable) {
                status.text = "测试失败"
                appendResult("错误：${t.message ?: t.javaClass.simpleName}", ORANGE)
            }
        }
    }

    private fun stopOperation() {
        operation?.cancel()
        operation = null
        status.text = "已停止"
    }

    private fun cleanTarget(): String? {
        val host = target.text.toString().trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
        if (host.isBlank()) {
            status.text = "先输入目标域名或 IP"
            return null
        }
        return host
    }

    private fun formatPing(result: ProbeResult): String = when (result) {
        is ProbeResult.Success -> "● #${result.sequence}  ${result.remote}  ${ms(result.elapsedUsec)} ms"
        is ProbeResult.Timeout -> "● #${result.sequence}  超时"
        is ProbeResult.ConnectionRefused -> "● #${result.sequence}  ${result.offender}  ${ms(result.elapsedUsec)} ms · 目标拒绝连接"
        is ProbeResult.HostUnreachable -> "● #${result.sequence}  ${result.offender}  ${ms(result.elapsedUsec)} ms · 主机不可达"
        is ProbeResult.NetUnreachable -> "● #${result.sequence}  ${result.offender}  ${ms(result.elapsedUsec)} ms · 网络不可达"
        is ProbeResult.NetError -> "● #${result.sequence}  网络错误 ${result.errNo}/${result.errCode}"
        is ProbeResult.Unknown -> "● #${result.sequence}  ${result.error}"
    }

    private fun renderHops(hops: List<HopStatus>) {
        resultBox.removeAllViews()
        addPathStage("手机", "● 实测起点")
        hops.forEach { hop ->
            val ips = if (hop.ips.isEmpty()) listOf("*") else hop.ips.toList()
            val times = hop.probes.mapNotNull { response ->
                (response as? Response.Success)?.timeUsec
            }
            val avg = if (times.isEmpty()) "超时" else "${ms(times.average().toInt())} ms"
            val role = when {
                hop.isLast -> "目标"
                ips.any { isPrivateLike(it) } -> "◇ 接入 / 运营商内部节点"
                else -> "◇ 公网 / 骨干节点"
            }
            addPathStage("第 ${hop.num} 跳 · ${ips.joinToString(" / ")}", "$avg · $role")
        }
    }

    private fun addPathStage(title: String, detail: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PANEL)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        box.addView(text(title, 12f, TEXT, true))
        box.addView(text(detail, 11f, if (detail.startsWith("●")) GREEN else MUTED))
        resultBox.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
    }

    private fun isPrivateLike(ip: String): Boolean = try {
        val a = InetAddress.getByName(ip)
        a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress
    } catch (_: Throwable) {
        false
    }

    private fun appendResult(value: String, color: Int = TEXT) {
        resultBox.addView(text(value, 12f, color).apply { setPadding(0, dp(4), 0, dp(4)) })
    }

    private fun ms(usec: Int): String = String.format(Locale.US, "%.1f", usec / 1000.0)

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(6), dp(2), 0) }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sp
        setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BG = Color.rgb(11, 14, 20)
        private val PANEL = Color.rgb(15, 21, 32)
        private val GREEN = Color.rgb(0, 229, 160)
        private val TEXT = Color.rgb(181, 212, 244)
        private val MUTED = Color.rgb(95, 107, 122)
        private val ORANGE = Color.rgb(239, 159, 39)
    }
}
