package com.lasaudio.app

import android.content.Context
import android.content.SharedPreferences
import com.lasaudio.core.LevelMeter
import com.lasaudio.core.Proto
import com.lasaudio.core.RxStats

/** Shared, thread-safe state between the playback service and the UI. */
object AppState {

    const val DEFAULT_JITTER_MODE = 0
    const val DEFAULT_AUTO_RECONNECT = true
    const val DEFAULT_LINK_TIMEOUT_SEC = 8
    const val DEFAULT_METRICS_HZ = 2
    const val DEFAULT_NOTIFY = true

    @Volatile
    var state: String = "已停止"

    @Volatile
    var stats: RxStats = RxStats()

    @Volatile
    var servers: List<Proto.ServerInfo> = emptyList()

    /** 电脑端回传的权威设置（连接建立时 / 手机点保存后的回执）。 */
    @Volatile
    var desktopSettings: Map<Byte, Long> = emptyMap()

    /** 左右声道响度电平（本地从解码 PCM 统计，界面按屏幕刷新率读取）。 */
    val levelMeter = LevelMeter()

    /** WakeLock/WifiLock/MulticastLock 的获取结果，便于排查断流原因。 */
    @Volatile
    var lockStatus: String = "未获取"

    /** 电脑端回传设置的次数（用于界面显示「电脑端已确认设置」）。 */
    @Volatile
    var settingsAckCount: Int = 0

    private var prefs: SharedPreferences? = null

    private val logLines = ArrayDeque<String>()

    @Volatile
    var logText: String = ""

    @Volatile
    var jitterMode: Int = DEFAULT_JITTER_MODE

    @Volatile
    var autoReconnect: Boolean = DEFAULT_AUTO_RECONNECT

    /** 断连超时（秒）：无音频多久后重连；与电脑端「客户端无响应」阈值同一个设置。 */
    @Volatile
    var linkTimeoutSec: Int = DEFAULT_LINK_TIMEOUT_SEC

    /** 实时指标刷新率（Hz）。 */
    @Volatile
    var metricsHz: Int = DEFAULT_METRICS_HZ

    /** 通知栏是否显示实时指标。 */
    @Volatile
    var notifyEnabled: Boolean = DEFAULT_NOTIFY

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("las_settings", Context.MODE_PRIVATE)
        prefs = p
        jitterMode = p.getInt("jitterMode", DEFAULT_JITTER_MODE)
        autoReconnect = p.getBoolean("autoReconnect", DEFAULT_AUTO_RECONNECT)
        linkTimeoutSec = p.getInt("linkTimeoutSec", DEFAULT_LINK_TIMEOUT_SEC)
        metricsHz = p.getInt("metricsHz", DEFAULT_METRICS_HZ)
        notifyEnabled = p.getBoolean("notifyEnabled", DEFAULT_NOTIFY)
    }

    fun persist() {
        prefs?.edit()
            ?.putInt("jitterMode", jitterMode)
            ?.putBoolean("autoReconnect", autoReconnect)
            ?.putInt("linkTimeoutSec", linkTimeoutSec)
            ?.putInt("metricsHz", metricsHz)
            ?.putBoolean("notifyEnabled", notifyEnabled)
            ?.apply()
    }

    /** 本机设置统一入口（服务与界面共用）。 */
    fun applyLocal(key: Byte, value: Long) {
        when (key) {
            Proto.KEY_JITTER_MODE -> jitterMode = value.toInt()
            Proto.KEY_AUTO_RECONNECT -> autoReconnect = value != 0L
            Proto.KEY_LINK_TIMEOUT -> linkTimeoutSec = value.toInt().coerceIn(1, 600)
            Proto.KEY_METRICS_HZ -> metricsHz = value.toInt().coerceIn(1, 60)
            Proto.KEY_NOTIFY -> notifyEnabled = value != 0L
        }
        persist()
    }

    fun localValue(key: Byte): Long = when (key) {
        Proto.KEY_JITTER_MODE -> jitterMode.toLong()
        Proto.KEY_AUTO_RECONNECT -> if (autoReconnect) 1L else 0L
        Proto.KEY_LINK_TIMEOUT -> linkTimeoutSec.toLong()
        Proto.KEY_METRICS_HZ -> metricsHz.toLong()
        Proto.KEY_NOTIFY -> if (notifyEnabled) 1L else 0L
        else -> 0L
    }

    /** 指标刷新间隔（毫秒），1–60 Hz；下限 16ms 保证 60 Hz 设置能真正生效（原 50ms 会把 60 Hz 截成 20 Hz）。 */
    fun metricsIntervalMs(): Long = (1000L / metricsHz.coerceIn(1, 60)).coerceAtLeast(16L)

    fun log(message: String) {
        synchronized(logLines) {
            logLines.addLast(message)
            while (logLines.size > 30) logLines.removeFirst()
            logText = logLines.joinToString("\n")
        }
    }
}
