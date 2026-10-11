package com.wb.signalpro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private lateinit var shizukuStatus: TextView
    private var running = false

    private val refresh = object : Runnable {
        override fun run() {
            if (!running) return
            render()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(18), dp(16), dp(18), dp(12))
        }

        root.addView(text("Signal", 24f, GREEN, true))
        root.addView(text("现在这台手机，究竟连着什么网络、数据从哪里走", 12f, MUTED).apply {
            setPadding(0, dp(4), 0, dp(12))
        })

        shizukuStatus = text("", 12f, MUTED).apply {
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                if (ShizukuHelper.isAvailable() && !ShizukuHelper.isGranted()) {
                    ShizukuHelper.request()
                    handler.postDelayed({ render() }, 1_000)
                }
            }
        }
        root.addView(shizukuStatus)

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val diag = Button(this).apply {
            text = "Ping / 路由跟踪"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java))
            }
        }
        val cmcloud = Button(this).apply {
            text = "CMCloud 实测"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@MainActivity, CmcloudTestActivity::class.java))
            }
        }
        actionRow.addView(diag, LinearLayout.LayoutParams(0, -2, 1f))
        actionRow.addView(cmcloud, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actionRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        requestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        running = true
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        running = false
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun requestRuntimePermissions() {
        val wanted = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 10)
    }

    private fun render() {
        val radio = SignalInfo.capture(this)
        val network = NetworkSnapshot.capture(this)
        content.removeAllViews()

        updateShizukuStatus()
        content.addView(section("当前实际网络出口"))
        content.addView(card().apply {
            addView(big(network.summary()))
            addView(kv("IPv4", network.ipv4))
            addView(kv("IPv6", network.ipv6))
            addView(kv("DNS", network.dns))
            addView(kv("MTU", if (network.mtu > 0) network.mtu.toString() else "--"))
            addView(kv("计费网络", if (network.metered) "是" else "否 / 未标记"))
            addView(kv("联网验证", if (network.validated) "已通过" else "未确认"))
            addView(note("这里显示的是 Android 当前实际默认网络，不是“手机有哪些网络”。"))
        })

        content.addView(section("无线接入"))
        content.addView(card().apply {
            val radioTitle = listOf(radio.operator, radio.rat, stripUnknown(radio.bands))
                .filter { it.isNotBlank() && it != "--" }
                .joinToString(" · ")
                .ifBlank { "蜂窝信息暂不可用" }
            addView(big(radioTitle))
            addView(kv("信号强度", radio.describeRsrp()))
            addView(kv("信号纯净度", radio.describeRsrq()))
            addView(kv("信号质量", radio.describeSinr()))
            addView(kv("PCI / 基站编号", valueOrDash(radio.pci)))
            addView(kv("TAC / 位置区", valueOrDash(radio.tac)))
            addView(kv("NCI / 小区编号", if (radio.nci > 0) radio.nci.toString() else "--"))
            addView(kv("ARFCN / 频点", valueOrDash(radio.nrarfcn)))
            addView(kv("MCC/MNC", radio.mccMnc))
            addView(note(radioPlainLanguage(radio)))
        })

        content.addView(section("手机当前还能看到的网络"))
        content.addView(card().apply {
            if (network.networks.isEmpty()) {
                addView(note("系统没有返回其他网络。"))
            } else {
                network.networks.forEach { addView(row(it)) }
            }
        })

        content.addView(section("接下来要观察的完整路径"))
        content.addView(card().apply {
            addView(pathLine("手机", true))
            addView(pathArrow())
            addView(pathLine(if (network.activeTransport == "--") "当前接入网络" else network.activeTransport, true))
            addView(pathArrow())
            addView(pathLine("运营商接入 / 核心网", false))
            addView(pathArrow())
            addView(pathLine("运营商骨干", false))
            addView(pathArrow())
            addView(pathLine("目标服务", false))
            addView(note("绿色圆点 = 手机已经实测到的信息；空心圆 = 还要通过路由跟踪/识别补全，不能冒充实测。"))
        })

        content.addView(section("真实业务测试"))
        content.addView(card().apply {
            addView(big("Provider 模块正在接入"))
            addView(note("CMCloud 会作为第一个真实 Provider。每一段传输都会单独显示进度、当前速度、平均速度和耗时。公开 APK 不保存长期云盘凭据。"))
        })
    }

    private fun updateShizukuStatus() {
        val available = ShizukuHelper.isAvailable()
        val granted = ShizukuHelper.isGranted()
        when {
            !available -> {
                shizukuStatus.text = "○ Shizuku 未运行 · 基础功能仍可用"
                shizukuStatus.setTextColor(MUTED)
            }
            !granted -> {
                shizukuStatus.text = "◉ Shizuku 可用 · 点这里授权增强参数"
                shizukuStatus.setTextColor(ORANGE)
            }
            else -> {
                shizukuStatus.text = "● Shizuku 已授权 · 系统级增强可用"
                shizukuStatus.setTextColor(GREEN)
            }
        }
    }

    private fun radioPlainLanguage(s: SignalInfo.Snapshot): String {
        if (s.rsrp == SignalInfo.MINV) return "没有拿到可靠的无线强度数据；这不代表网络一定有问题。"
        return when {
            s.rsrp >= -80 -> "当前基站信号很强。速度如果仍然不理想，应该继续看空口拥塞、核心网和后面的路径。"
            s.rsrp >= -95 -> "当前基站信号正常。"
            s.rsrp >= -110 -> "当前信号偏弱，可能开始影响稳定性和吞吐。"
            else -> "当前信号较弱，先把无线接入这一段列为重点观察对象。"
        }
    }

    private fun section(title: String) = text(title, 14f, SECTION, true).apply {
        setPadding(0, dp(18), 0, dp(7))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setBackgroundColor(PANEL)
    }

    private fun big(value: String) = text(value, 18f, GREEN, true).apply {
        setPadding(0, 0, 0, dp(8))
    }

    private fun kv(label: String, value: String): View {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        line.addView(text(label, 12f, TEXT), LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(text(value.ifBlank { "--" }, 12f, GREEN))
        return line
    }

    private fun row(value: String) = text("• $value", 12f, TEXT).apply {
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun note(value: String) = text(value, 11f, MUTED).apply {
        setPadding(0, dp(9), 0, 0)
    }

    private fun pathLine(label: String, measured: Boolean) = text(
        (if (measured) "● " else "○ ") + label,
        13f,
        if (measured) GREEN else TEXT,
        measured
    )

    private fun pathArrow() = text("    ↓", 13f, MUTED).apply { setPadding(0, dp(2), 0, dp(2)) }

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sp
        setTextColor(color)
        typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun valueOrDash(value: Int) = if (value > 0) value.toString() else "--"
    private fun stripUnknown(value: String?) = value?.takeUnless { it.contains("?") } ?: ""
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
