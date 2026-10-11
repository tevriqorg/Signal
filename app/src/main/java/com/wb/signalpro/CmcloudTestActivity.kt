package com.wb.signalpro

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import me.impa.icmpenguin.trace.HopStatus
import me.impa.icmpenguin.trace.Response
import me.impa.icmpenguin.trace.SimpleTracer
import java.net.InetAddress
import java.util.Locale

class CmcloudTestActivity : Activity() {
    private val scope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var testJob: Job? = null
    private var sizeMiB = 20

    private lateinit var serverInput: EditText
    private lateinit var sizeStatus: TextView
    private lateinit var summary: TextView
    private lateinit var stageBox: LinearLayout
    private lateinit var pathBox: LinearLayout
    private lateinit var uploadButton: Button
    private lateinit var downloadButton: Button
    private lateinit var stopButton: Button

    private data class StageView(val title: TextView, val detail: TextView, val bar: ProgressBar)
    private val stages = linkedMapOf<Int, StageView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        root.addView(text("CMCloud 真实路径测试", 21f, GREEN, true))
        root.addView(text("大文件直接在手机和移动云盘之间传输；Tokyo 只负责签发临时通行证。", 11f, MUTED).apply {
            setPadding(0, dp(4), 0, dp(10))
        })

        serverInput = EditText(this).apply {
            hint = "Remote Probe HTTPS 地址（保存一次即可）"
            setText(ProbeSettings.getBaseUrl(this@CmcloudTestActivity))
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            setSingleLine(true)
            setBackgroundColor(PANEL)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        root.addView(serverInput, LinearLayout.LayoutParams(-1, -2))

        val controlRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controlRow.addView(button("保存并检查") { saveAndCheck() }, weighted())
        sizeStatus = text("20 MiB", 12f, GREEN, true).apply { gravity = Gravity.CENTER }
        controlRow.addView(sizeStatus, weighted())
        root.addView(controlRow)

        val sizeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(5, 20, 50, 100).forEach { value ->
            sizeRow.addView(button("$value") {
                sizeMiB = value
                sizeStatus.text = "$value MiB"
            }, weighted())
        }
        root.addView(sizeRow)

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        uploadButton = button("手机 → 云盘") { startUpload() }
        downloadButton = button("云盘 → 手机") { startDownload() }
        stopButton = button("停止") { stopTest() }
        actionRow.addView(uploadButton, weighted())
        actionRow.addView(downloadButton, weighted())
        actionRow.addView(stopButton, weighted())
        root.addView(actionRow)

        summary = text("等待测试", 12f, MUTED).apply { setPadding(0, dp(10), 0, dp(8)) }
        root.addView(summary)

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(section("本次实际在测什么"))
        content.addView(card().apply {
            addView(pathText("📱 这台手机", true))
            addView(arrow())
            val network = NetworkSnapshot.capture(this@CmcloudTestActivity)
            addView(pathText("${network.activeTransport} · ${network.interfaceName}", true))
            addView(arrow())
            addView(pathText("运营商接入 / 核心网", false))
            addView(arrow())
            addView(pathText("运营商骨干", false))
            addView(arrow())
            addView(pathText("☁️ CMCloud EOS", false))
            addView(note("Tokyo Probe 的控制请求很小，不计入下面的大文件测速链路。"))
        })

        content.addView(section("阶段进度"))
        stageBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(stageBox)
        resetStages("upload")

        content.addView(section("手机侧真实路由"))
        pathBox = card().apply { addView(note("拿到实际 EOS 地址后自动从手机做 traceroute。")) }
        content.addView(pathBox)

        setContentView(root)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun saveAndCheck() {
        val base = normalizedBase() ?: return
        ProbeSettings.setBaseUrl(this, base)
        summary.text = "正在检查 Remote Probe…"
        scope.launch {
            try {
                val health = withContext(Dispatchers.IO) { CmcloudClient(base).health() }
                val ok = health.optBoolean("ok", false)
                summary.text = if (ok) "Remote Probe 正常 · 配置已保存" else "Remote Probe 返回异常状态"
                summary.setTextColor(if (ok) GREEN else ORANGE)
            } catch (t: Throwable) {
                summary.text = "Remote Probe 不可用：${shortError(t)}"
                summary.setTextColor(ORANGE)
            }
        }
    }

    private fun startUpload() {
        val base = normalizedBase() ?: return
        ProbeSettings.setBaseUrl(this, base)
        resetStages("upload")
        startBusy("手机 → CMCloud") {
            val client = CmcloudClient(base)
            updateStage(1, "正在生成真实 WAV 测试文件", "0%", 0)
            val total = sizeMiB * 1024L * 1024L
            val started = System.nanoTime()
            val sample = withContext(Dispatchers.IO) {
                SyntheticMedia.createWav(cacheDir, total) { done, all ->
                    val pct = percent(done, all)
                    runOnUiThread {
                        updateStage(1, "生成 ${formatBytes(all)} WAV + SHA-256", "$pct% · ${formatBytes(done)}", pct)
                    }
                }
            }
            updateStage(1, "真实 WAV 已准备", "${sample.displayName} · ${formatBytes(sample.file.length())} · ${elapsed(started)} ms", 100)
            updateStage(2, "申请临时 capability", "控制请求 → Tokyo Probe", 10)
            try {
                val result = withContext(Dispatchers.IO) {
                    client.upload(sample, onCapability = { host ->
                        runOnUiThread { updateStage(2, "临时 capability 已签发", "Tokyo → $host · 只参与控制面", 100) }
                    }) { p ->
                        runOnUiThread {
                            updateStage(
                                3,
                                "手机 → CMCloud EOS",
                                "${percent(p.done, p.total)}% · 当前 ${fmtMbps(p.currentMbps)} · 平均 ${fmtMbps(p.averageMbps)} · ${formatBytes(p.done)}",
                                percent(p.done, p.total)
                            )
                        }
                    }
                }
                updateStage(3, "手机 → CMCloud EOS 完成", "平均 ${fmtMbps(result.averageMbps)} · ${formatBytes(result.bytes)} · ${result.elapsedMs} ms", 100)
                updateStage(4, "清理测试对象", "正在从云盘回收测试文件", 30)
                withContext(Dispatchers.IO) { result.sessionId?.let { client.trash(it) } }
                updateStage(4, "测试对象已清理", "不会把合成 WAV 留在云盘", 100)
                traceEos(result.eosHost)
                summary.text = "完成 · 手机 → CMCloud ${fmtMbps(result.averageMbps)}"
                summary.setTextColor(GREEN)
            } finally {
                sample.file.delete()
            }
        }
    }

    private fun startDownload() {
        val base = normalizedBase() ?: return
        ProbeSettings.setBaseUrl(this, base)
        resetStages("download")
        startBusy("CMCloud → 手机") {
            val client = CmcloudClient(base)
            updateStage(1, "Tokyo 准备 / 复用云盘样本", "控制面请求中", 15)
            val prepStarted = System.nanoTime()
            val prepared = withContext(Dispatchers.IO) { client.prepareDownload(sizeMiB) }
            updateStage(
                1,
                if (prepared.cached) "Tokyo 复用了已有云盘样本" else "Tokyo 已准备新的云盘样本",
                "${formatBytes(prepared.size)} · ${elapsed(prepStarted)} ms${if (prepared.cached) " · 无需重新上传" else ""}",
                100
            )
            updateStage(2, "申请下载 capability", "控制请求 → Tokyo Probe", 20)
            val result = withContext(Dispatchers.IO) {
                client.download(prepared, onCapability = { host ->
                    runOnUiThread { updateStage(2, "临时 capability 已签发", "Tokyo → $host · 只参与控制面", 100) }
                }) { p ->
                    runOnUiThread {
                        updateStage(
                            3,
                            "CMCloud EOS → 手机",
                            "${percent(p.done, p.total)}% · 当前 ${fmtMbps(p.currentMbps)} · 平均 ${fmtMbps(p.averageMbps)} · ${formatBytes(p.done)}",
                            percent(p.done, p.total)
                        )
                    }
                }
            }
            updateStage(
                3,
                "CMCloud EOS → 手机完成",
                "平均 ${fmtMbps(result.averageMbps)} · ${formatBytes(result.bytes)} · SHA-256 ${if (result.sha256Match == true) "✓" else "✗"}",
                100
            )
            updateStage(4, "完整性校验", if (result.sha256Match == true) "SHA-256 ✓ · 下载内容与云盘样本一致" else "SHA-256 ✗ · 内容不一致", 100)
            traceEos(result.eosHost)
            summary.text = "完成 · CMCloud → 手机 ${fmtMbps(result.averageMbps)}"
            summary.setTextColor(if (result.sha256Match == true) GREEN else ORANGE)
        }
    }

    private suspend fun traceEos(host: String) {
        updateStage(5, "手机侧路径分析", "正在 traceroute → $host", 10)
        pathBox.removeAllViews()
        pathBox.addView(pathText("● 手机", true))
        val hops = sortedMapOf<Int, HopStatus>()
        try {
            SimpleTracer(host = host, timeout = 2500, maxHops = 30, probesPerHop = 2).trace().collect { hop ->
                hops[hop.num] = hop
                renderTrace(host, hops.values.toList())
                updateStage(5, "手机侧路径分析", if (hop.isLast) "已到达 $host · ${hop.num} 跳" else "正在测第 ${hop.num} 跳", if (hop.isLast) 100 else minOf(95, hop.num * 4))
            }
            updateStage(5, "手机侧路径分析完成", "已记录 ${hops.size} 个 hop", 100)
        } catch (t: Throwable) {
            updateStage(5, "路径分析未完成", shortError(t), 100)
        }
    }

    private fun renderTrace(host: String, hops: List<HopStatus>) {
        pathBox.removeAllViews()
        pathBox.addView(pathText("● 手机", true))
        hops.forEach { hop ->
            pathBox.addView(arrow())
            val ips = if (hop.ips.isEmpty()) listOf("*") else hop.ips.toList()
            val times = hop.probes.mapNotNull { (it as? Response.Success)?.timeUsec }
            val delay = if (times.isEmpty()) "超时" else String.format(Locale.US, "%.1f ms", times.average() / 1000.0)
            val role = when {
                hop.isLast -> "目标 CMCloud EOS"
                ips.any { privateLike(it) } -> "◇ 接入 / 运营商内部（推断）"
                else -> "◇ 公网 / 骨干（待识别）"
            }
            pathBox.addView(pathText("${hop.num}. ${ips.joinToString(" / ")}", true))
            pathBox.addView(note("$delay · $role"))
        }
        if (hops.lastOrNull()?.isLast != true) pathBox.addView(note("目标：$host；部分运营商节点可能不回应 traceroute，这不代表数据没有经过。"))
    }

    private fun startBusy(label: String, block: suspend () -> Unit) {
        testJob?.cancel()
        setBusy(true)
        summary.text = "开始：$label"
        summary.setTextColor(TEXT)
        testJob = scope.launch {
            try {
                block()
            } catch (_: CancellationException) {
                summary.text = "已停止"
                summary.setTextColor(MUTED)
            } catch (t: Throwable) {
                summary.text = "测试失败：${shortError(t)}"
                summary.setTextColor(ORANGE)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun stopTest() {
        testJob?.cancel()
        testJob = null
        summary.text = "正在停止 / 已停止"
        summary.setTextColor(MUTED)
        setBusy(false)
    }

    private fun resetStages(mode: String) {
        stages.clear()
        stageBox.removeAllViews()
        val labels = if (mode == "download") listOf(
            "Tokyo 准备 / 复用样本",
            "申请临时 capability",
            "CMCloud EOS → 手机",
            "完整性校验",
            "手机侧路径分析"
        ) else listOf(
            "生成真实测试样本",
            "申请临时 capability",
            "手机 → CMCloud EOS",
            "清理测试对象",
            "手机侧路径分析"
        )
        labels.forEachIndexed { index, label ->
            val title = text("${index + 1}. $label", 12f, TEXT, true)
            val detail = text("等待", 10f, MUTED)
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
            }
            val item = card().apply {
                addView(title)
                addView(detail)
                addView(bar, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(5) })
            }
            stageBox.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            stages[index + 1] = StageView(title, detail, bar)
        }
    }

    private fun updateStage(index: Int, title: String, detail: String, progress: Int) {
        val view = stages[index] ?: return
        view.title.text = "$index. $title"
        view.title.setTextColor(if (progress >= 100) GREEN else TEXT)
        view.detail.text = detail
        view.bar.progress = progress.coerceIn(0, 100)
    }

    private fun setBusy(busy: Boolean) {
        uploadButton.isEnabled = !busy
        downloadButton.isEnabled = !busy
        stopButton.isEnabled = busy
    }

    private fun normalizedBase(): String? {
        val value = serverInput.text.toString().trim().trimEnd('/')
        if (!value.startsWith("https://")) {
            summary.text = "Remote Probe 必须使用 HTTPS"
            summary.setTextColor(ORANGE)
            return null
        }
        return value
    }

    private fun privateLike(ip: String): Boolean = try {
        InetAddress.getByName(ip).let { it.isSiteLocalAddress || it.isLinkLocalAddress || it.isLoopbackAddress }
    } catch (_: Throwable) { false }

    private fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else ((done * 100L) / total).toInt().coerceIn(0, 100)
    private fun elapsed(startedNs: Long): Long = (System.nanoTime() - startedNs) / 1_000_000L
    private fun fmtMbps(value: Double) = String.format(Locale.US, "%.2f Mbps", value)
    private fun formatBytes(value: Long): String = when {
        value >= 1024L * 1024L -> String.format(Locale.US, "%.2f MiB", value / 1024.0 / 1024.0)
        value >= 1024L -> String.format(Locale.US, "%.1f KiB", value / 1024.0)
        else -> "$value B"
    }
    private fun shortError(t: Throwable) = (t.message ?: t.javaClass.simpleName).replace('\n', ' ').take(180)

    private fun section(value: String) = text(value, 13f, SECTION, true).apply { setPadding(0, dp(15), 0, dp(6)) }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(PANEL); setPadding(dp(12), dp(10), dp(12), dp(10)) }
    private fun pathText(value: String, measured: Boolean) = text(value, 12f, if (measured) GREEN else TEXT, measured)
    private fun arrow() = text("    ↓", 12f, MUTED).apply { setPadding(0, dp(2), 0, dp(2)) }
    private fun note(value: String) = text(value, 10f, MUTED).apply { setPadding(0, dp(3), 0, dp(3)) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(5), dp(2), 0) }
    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = sp; setTextColor(color); typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BG = Color.rgb(11, 14, 20)
        private val PANEL = Color.rgb(15, 21, 32)
        private val GREEN = Color.rgb(0, 229, 160)
        private val TEXT = Color.rgb(181, 212, 244)
        private val MUTED = Color.rgb(95, 107, 122)
        private val SECTION = Color.rgb(133, 183, 235)
        private val ORANGE = Color.rgb(239, 159, 39)
    }
}
