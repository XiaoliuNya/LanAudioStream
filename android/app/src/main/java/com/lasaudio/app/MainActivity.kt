package com.lasaudio.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import com.lasaudio.R
import com.lasaudio.core.Proto
import com.lasaudio.core.RxStats
import java.util.Locale

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvState: TextView
    private lateinit var tvServer: TextView
    private lateinit var tvBitrate: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvLatency: TextView
    private lateinit var tvJitter: TextView
    private lateinit var tvLoss: TextView
    private lateinit var tvLossCount: TextView
    private lateinit var tvBuffer: TextView
    private lateinit var tvOutBuffer: TextView
    private lateinit var tvDecoded: TextView
    private lateinit var tvPlc: TextView
    private lateinit var tvUnderrun: TextView
    private lateinit var tvRtt: TextView
    private lateinit var tvFormat: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvSettingHint: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnConnect: Button
    private lateinit var btnSaveSettings: Button
    private lateinit var btnResetSettings: Button
    private lateinit var settingsRemote: android.widget.LinearLayout
    private lateinit var settingsLocal: android.widget.LinearLayout
    private val remoteRows = SettingsUi.remoteRows()
    private val localRows = SettingsUi.localRows()
    private lateinit var spServers: Spinner
    private lateinit var chart: ChartView
    private lateinit var meterView: LevelMeterView

    private var lastServerCount = -1
    private var formDirty = false
    private var lastChartIntervalMs = -1L
    private var lastAckCount = 0
    private var pendingAckAtMs = 0L

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, AppState.metricsIntervalMs())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyWindowInsets()

        tvState = findViewById(R.id.tvState)
        tvServer = findViewById(R.id.tvServer)
        tvBitrate = findViewById(R.id.tvBitrate)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvLatency = findViewById(R.id.tvLatency)
        tvJitter = findViewById(R.id.tvJitter)
        tvLoss = findViewById(R.id.tvLoss)
        tvLossCount = findViewById(R.id.tvLossCount)
        tvBuffer = findViewById(R.id.tvBuffer)
        tvOutBuffer = findViewById(R.id.tvOutBuffer)
        tvDecoded = findViewById(R.id.tvDecoded)
        tvPlc = findViewById(R.id.tvPlc)
        tvUnderrun = findViewById(R.id.tvUnderrun)
        tvRtt = findViewById(R.id.tvRtt)
        tvFormat = findViewById(R.id.tvFormat)
        tvLog = findViewById(R.id.tvLog)
        tvSettingHint = findViewById(R.id.tvSettingHint)
        btnToggle = findViewById(R.id.btnToggle)
        settingsRemote = findViewById(R.id.settingsRemote)
        settingsLocal = findViewById(R.id.settingsLocal)
        btnConnect = findViewById(R.id.btnConnect)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        btnResetSettings = findViewById(R.id.btnResetSettings)
        spServers = findViewById(R.id.spServers)
        chart = findViewById(R.id.chart)
        meterView = findViewById(R.id.levelMeter)
        meterView.meter = AppState.levelMeter

        chart.addSeries("端到端延迟", "ms", 0xFFFF8A65.toInt())
        chart.addSeries("网络抖动", "ms", 0xFF81D4FA.toInt())
        chart.addSeries("接收码率", "kbps", 0xFFA5D6A7.toInt())
        chart.addSeries("实时速度", "KB/s", 0xFF80CBC4.toInt())
        chart.addSeries("丢包率", "%", 0xFFFFD54F.toInt())

        buildSettings()

        btnToggle.setOnClickListener {
            if (StreamService.running) sendAction(StreamService.ACTION_STOP) else startPlayback()
        }

        btnSaveSettings.setOnClickListener { saveSettings() }
        btnResetSettings.setOnClickListener { resetAllSettings() }

        btnConnect.setOnClickListener {
            val servers = AppState.servers
            val index = spServers.selectedItemPosition
            if (index in servers.indices) {
                val s = servers[index]
                sendAction(StreamService.ACTION_CONNECT) {
                    it.putExtra(StreamService.EXTRA_HOST, s.host)
                    it.putExtra(StreamService.EXTRA_PORT, s.port)
                }
                AppState.log("手动连接 " + s.host + ":" + s.port)
            }
        }

        requestNotificationPermission()
        tvSettingHint.text = "音频设置改动后请点“保存设置”，才会下发到电脑端。"
        AppState.log("就绪。请确保桌面端已点击“开始串流”。")
    }

    /**
     * targetSdk 35 起系统强制边到边显示，内容会画到状态栏/导航栏下面。
     * 这里把系统栏与刘海的安全区作为额外内边距加在根布局上，避免标题被状态栏盖住。
     */
    @Suppress("DEPRECATION")
    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.rootScroll)
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = bars.top
                bottom = bars.bottom
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            v.setPadding(baseLeft, baseTop + top, baseRight, baseBottom + bottom)
            insets
        }
        root.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        // 曲线与电平条按屏幕刷新率绘制（60/90/120Hz），进入前台才启动，省电
        chart.setPushIntervalMs(AppState.metricsIntervalMs().toFloat())
        chart.setActive(true)
        meterView.setActive(true)
        handler.postDelayed(poll, 300)
    }

    override fun onPause() {
        super.onPause()
        chart.setActive(false)
        meterView.setActive(false)
        handler.removeCallbacks(poll)
    }

    // ------------------------------------------------------------ 设置界面
    private fun buildSettings() {
        SettingsUi.build(this, settingsRemote, remoteRows) { row -> onRemoteChanged(row) }
        SettingsUi.build(this, settingsLocal, localRows) { row -> onLocalChanged(row) }
        for (row in localRows) row.setValue(AppState.localValue(row.key))
        applyDesktopSettings()
    }

    private fun onRemoteChanged(row: SettingRow) {
        markDirty("已修改「" + row.title + "」，点“保存设置”后下发到电脑端")
    }

    private fun onLocalChanged(row: SettingRow) {
        val value = row.currentValue()
        AppState.applyLocal(row.key, value)
        if (StreamService.running) {
            sendAction(StreamService.ACTION_LOCAL) {
                it.putExtra(StreamService.EXTRA_KEY, row.key)
                it.putExtra(StreamService.EXTRA_VALUE, value)
            }
        }
        AppState.log("本机设置: " + row.title + " = " + labelOf(row, value))
        if (row.submit) {
            markDirty("本机设置已生效，「保存设置」会一并同步给电脑端")
        }
    }

    private fun labelOf(row: SettingRow, value: Long): String = when (row.kind) {
        SettingRow.Kind.SWITCH -> if (value != 0L) "开" else "关"
        SettingRow.Kind.CHOICE -> row.labels.getOrElse(row.values.indexOf(value)) { value.toString() }
        SettingRow.Kind.SLIDER -> value.toString()
    }

    private fun markDirty(hint: String) {
        formDirty = true
        btnSaveSettings.text = "保存设置 ●"
        tvSettingHint.text = hint
    }

    private fun clearDirty(hint: String) {
        formDirty = false
        btnSaveSettings.text = "保存设置"
        tvSettingHint.text = hint
    }

    /** 电脑端回传的权威设置：刷新界面（此时不认为用户改过，不触发下发）。 */
    private fun applyDesktopSettings() {
        if (formDirty) return
        val map = AppState.desktopSettings
        if (map.isEmpty()) return
        for (row in remoteRows) {
            val v = map[row.key] ?: continue
            if (row.currentValue() != v) row.setValue(v)
        }
        for (row in localRows) {
            val v = map[row.key] ?: continue
            if (row.currentValue() != v) {
                row.setValue(v)
                if (row.immediate) {
                    AppState.applyLocal(row.key, v)
                    if (StreamService.running) {
                        sendAction(StreamService.ACTION_LOCAL) {
                            it.putExtra(StreamService.EXTRA_KEY, row.key)
                            it.putExtra(StreamService.EXTRA_VALUE, v)
                        }
                    }
                }
            }
        }
    }

    /** 「保存设置」：把当前界面值一次性提交给电脑端（本机项已即时生效）。 */
    private fun saveSettings() {
        val keys = ArrayList<Byte>()
        val values = ArrayList<Long>()
        for (row in remoteRows) {
            if (row.submit) {
                keys.add(row.key)
                values.add(row.currentValue())
            }
        }
        for (row in localRows) {
            if (row.submit) {
                keys.add(row.key)
                values.add(row.currentValue())
            }
        }
        if (!StreamService.running) {
            AppState.log("请先点“开始接收”，连接后再点“保存设置”下发")
            markDirty("请先点“开始接收”，连接后再点“保存设置”下发")
            return
        }
        sendAction(StreamService.ACTION_SAVE) {
            it.putExtra(StreamService.EXTRA_KEYS, keys.toByteArray())
            it.putExtra(StreamService.EXTRA_VALUES, values.toLongArray())
        }
        pendingAckAtMs = System.currentTimeMillis()
        clearDirty("设置已提交，等待电脑端确认…")
        AppState.log("已提交 " + keys.size + " 项设置到电脑端")
    }

    /** 「重置所有设置」：只恢复界面与默认本机行为，点保存后才同步到电脑端。 */
    private fun resetAllSettings() {
        for (row in remoteRows) row.setValue(row.defaultValue)
        for (row in localRows) {
            row.setValue(row.defaultValue)
            AppState.applyLocal(row.key, row.defaultValue)
            if (StreamService.running) {
                sendAction(StreamService.ACTION_LOCAL) {
                    it.putExtra(StreamService.EXTRA_KEY, row.key)
                    it.putExtra(StreamService.EXTRA_VALUE, row.defaultValue)
                }
            }
        }
        markDirty("所有设置已恢复默认值，点“保存设置”后同步到电脑端")
        AppState.log("已重置所有设置（尚未下发到电脑端）")
    }

    private fun startPlayback() {
        val intent = Intent(this, StreamService::class.java).setAction(StreamService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun sendAction(action: String, extras: (Intent) -> Unit = {}) {
        if (!StreamService.running) {
            AppState.log("请先点击“开始接收”")
            return
        }
        val intent = Intent(this, StreamService::class.java).setAction(action)
        extras(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun refresh() {
        tvState.text = AppState.state
        tvState.setTextColor(if (StreamService.running) 0xFF7FD4FF.toInt() else 0xFF8A97A8.toInt())
        btnToggle.text = if (StreamService.running) "停止接收" else "开始接收"

        val stats: RxStats = AppState.stats
        tvServer.text = "服务器: " + stats.server
        tvLatency.text = if (stats.e2eUs < 0) "--" else String.format(Locale.US, "%.0f ms", stats.e2eUs / 1000.0)
        tvJitter.text = String.format(Locale.US, "%.2f ms", stats.jitterUs / 1000.0)

        // 接收码率 / 实时速度：接收线程按「指标刷新周期」滑窗结算的真实值（不做平滑；
        // 窗口内包太少时会自动放宽，见 RxStats.rateWindowMs）
        tvBitrate.text = String.format(Locale.US, "%.0f kbps", stats.recvBitrateBps / 1000.0)
        tvSpeed.text = String.format(Locale.US, "%.1f KB/s", stats.speedBps / 1024.0)

        tvLoss.text = String.format(Locale.US, "%.2f %%", stats.lossPercent())
        tvLossCount.text = String.format(Locale.US, "%d / %d", stats.lost, stats.recv + stats.lost)
        tvBuffer.text = String.format(Locale.US, "%.0f/%.0f ms", stats.bufferUs / 1000.0, stats.targetBufferUs / 1000.0)
        tvOutBuffer.text = String.format(Locale.US, "%.0f ms", stats.outputBufferUs / 1000.0)
        tvDecoded.text = stats.decodedFrames.toString()
        tvPlc.text = stats.plcFrames.toString()
        tvUnderrun.text = stats.underruns.toString()
        tvRtt.text = if (stats.rttUs <= 0) "--" else String.format(Locale.US, "%.1f ms", stats.rttUs / 1000.0)
        tvFormat.text = String.format(
            Locale.US,
            "音频格式: %dHz %dch %dms | 会话 %ds",
            stats.sampleRate, stats.channels, stats.frameMs,
            stats.sessionMs / 1000
        )

        val intervalMs = AppState.metricsIntervalMs()
        if (intervalMs != lastChartIntervalMs) {
            lastChartIntervalMs = intervalMs
            chart.setPushIntervalMs(intervalMs.toFloat())
        }
        chart.push(0, stats.e2eUs / 1000.0)
        chart.push(1, stats.jitterUs / 1000.0)
        chart.push(2, stats.recvBitrateBps / 1000.0)
        chart.push(3, stats.speedBps / 1024.0)
        chart.push(4, stats.lossPercent())

        tvLog.text = AppState.logText
        applyDesktopSettings()

        // 电脑端确认回执
        if (AppState.settingsAckCount != lastAckCount) {
            lastAckCount = AppState.settingsAckCount
            if (pendingAckAtMs != 0L) {
                pendingAckAtMs = 0L
                clearDirty("电脑端已确认设置 ✓")
            }
        } else if (pendingAckAtMs != 0L && System.currentTimeMillis() - pendingAckAtMs > 4000) {
            pendingAckAtMs = 0L
            tvSettingHint.text = "已提交，但 4 秒内未收到电脑端确认（请确认已连接并正在串流）"
        }

        syncServerSpinner()
    }

    private fun syncServerSpinner() {
        val servers = AppState.servers
        if (servers.size == lastServerCount) return
        lastServerCount = servers.size
        val labels = servers.map { s ->
            s.name + "  " + s.host + ":" + s.port + (if (s.streaming) " ●串流中" else "")
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spServers.adapter = adapter
        btnConnect.isEnabled = servers.isNotEmpty()
        if (servers.isEmpty()) {
            tvServer.text = "未发现桌面端（请确认桌面端已启动串流且处于同一局域网）"
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }
}
