package com.lasaudio.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.lasaudio.R
import com.lasaudio.core.Proto
import com.lasaudio.core.RxStats
import com.lasaudio.core.StreamClient

/** Foreground service: keeps discovery + playback running while the screen is off / app in background. */
class StreamService : Service(), StreamClient.Listener {

    companion object {
        const val ACTION_START = "com.lasaudio.app.action.START"
        const val ACTION_STOP = "com.lasaudio.app.action.STOP"
        const val ACTION_CONNECT = "com.lasaudio.app.action.CONNECT"
        const val ACTION_SAVE = "com.lasaudio.app.action.SAVE"        // 手动保存：一次性下发设置
        const val ACTION_LOCAL = "com.lasaudio.app.action.LOCAL"      // 本机：播放相关，立即生效
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_KEY = "key"
        const val EXTRA_VALUE = "value"
        const val EXTRA_KEYS = "keys"
        const val EXTRA_VALUES = "values"
        private const val CHANNEL_ID = "las_audio"
        private const val NOTIFICATION_ID = 4711

        @Volatile
        var running: Boolean = false
            private set
    }

    private var client: StreamClient? = null
    private var output: AudioOutput? = null
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var lastNotificationMs = 0L
    private var notifyWithMetrics = true
    private var lastGapEvents = -1
    private var gapWindowStartMs = 0L
    private var gapWindowCount = 0
    private var gapWindowMaxMs = 0L
    private var gapWindowBurst = 0

    private val pollStats = object : Runnable {
        override fun run() {
            val c = client ?: return
            // 速率滑窗长度 = 指标刷新周期：让码率/速度的原始数据与指标刷新同频
            c.setRateWindowMs(AppState.metricsIntervalMs().toInt())
            val stats = c.getStats()
            AppState.stats = stats
            // 网络停顿诊断：不在每次停顿时刷屏，按 10 秒汇总成一行（仅在有停顿时才打印）
            if (stats.gapEvents != lastGapEvents) {
                val fresh = if (lastGapEvents < 0) 0 else stats.gapEvents - lastGapEvents
                lastGapEvents = stats.gapEvents
                if (fresh > 0) {
                    gapWindowCount += fresh
                    if (stats.lastGapMs > gapWindowMaxMs) gapWindowMaxMs = stats.lastGapMs
                    if (stats.lastBurstCount > gapWindowBurst) gapWindowBurst = stats.lastBurstCount
                }
            }
            val nowWall = System.currentTimeMillis()
            if (gapWindowStartMs == 0L) {
                gapWindowStartMs = nowWall
            } else if (nowWall - gapWindowStartMs >= 10000) {
                if (gapWindowCount > 0) {
                    AppState.log("网络停顿 " + gapWindowCount + " 次/10s（最大 " + gapWindowMaxMs + "ms，最多成串 " +
                            gapWindowBurst + " 包）")
                }
                gapWindowStartMs = nowWall
                gapWindowCount = 0
                gapWindowMaxMs = 0
                gapWindowBurst = 0
            }
            maybeNotify(stats)
            handler.postDelayed(this, AppState.metricsIntervalMs())
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            acquireLocks()
            output = AudioOutput()
            AppState.levelMeter.reset()
            client = StreamClient(output!!, this).also {
                it.setLevelMeter(AppState.levelMeter)
                it.setLinkTimeoutSec(AppState.linkTimeoutSec)
                it.setLogSink { msg -> AppState.log(msg) }
                it.start()
            }
            applyAllLocalSettings()
        } catch (t: Throwable) {
            AppState.log("服务初始化失败: " + t.message)
        }
        running = true
        handler.postDelayed(pollStats, 500)
        AppState.log("服务已启动，正在搜索桌面端…")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, buildNotification("正在搜索桌面端…", ""))
        } catch (t: Throwable) {
            AppState.log("前台服务启动失败: " + t.message)
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_LOCAL -> {
                val key = intent.getByteExtra(EXTRA_KEY, 0)
                val value = intent.getLongExtra(EXTRA_VALUE, 0)
                AppState.applyLocal(key, value)
                applyLocalSetting(key, value)
            }
            ACTION_SAVE -> {
                val keys = intent.getByteArrayExtra(EXTRA_KEYS)
                val values = intent.getLongArrayExtra(EXTRA_VALUES)
                if (keys != null && values != null && keys.size == values.size) {
                    val list = ArrayList<Proto.Setting>(keys.size)
                    for (i in keys.indices) list.add(Proto.Setting(keys[i], values[i]))
                    if (list.isNotEmpty()) {
                        client?.sendSettings(list)
                        AppState.log("已提交 " + list.size + " 项设置到电脑端")
                    }
                }
            }
            ACTION_CONNECT -> {
                val host = intent.getStringExtra(EXTRA_HOST)
                val port = intent.getIntExtra(EXTRA_PORT, Proto.DEFAULT_STREAM_PORT)
                if (host != null) client?.connectTo(host, port)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        AppState.log("服务停止中")
        running = false
        client?.stop()
        client = null
        output?.close()
        output = null
        releaseLocks()
        AppState.state = "已停止"
        AppState.stats = RxStats()
        AppState.log("服务已停止")
        super.onDestroy()
    }

    // ------------------------------------------------------------ listener
    override fun onStateChanged(state: String) {
        AppState.state = state
        AppState.log("状态: " + state)
        maybeNotify(AppState.stats)
    }

    override fun onLog(message: String) {
        AppState.log(message)
    }

    override fun onServersUpdated(servers: List<Proto.ServerInfo>) {
        AppState.servers = servers
    }

    override fun onFormatChanged(info: Proto.AudioFormatInfo) {
        AppState.log("音频格式: " + info.sampleRate + "Hz " + info.channels + "ch " + info.frameMs + "ms " + (info.bitrate / 1000) + "kbps")
    }

    override fun onSettingsChanged(settings: Map<Byte, Long>) {
        AppState.desktopSettings = LinkedHashMap(settings)
        AppState.settingsAckCount++
        // 电脑端也会回推本机播放设置
        for ((key, value) in settings) {
            when (key) {
                Proto.KEY_JITTER_MODE, Proto.KEY_AUTO_RECONNECT, Proto.KEY_LINK_TIMEOUT -> {
                    if (AppState.localValue(key) != value) {
                        AppState.applyLocal(key, value)
                        applyLocalSetting(key, value)
                    }
                }
            }
        }
        AppState.log("电脑端设置已同步")
    }

    /** 应用本机播放设置（抖动策略 / 自动重连 / 断连超时）。 */
    private fun applyLocalSetting(key: Byte, value: Long) {
        when (key) {
            Proto.KEY_JITTER_MODE -> client?.setJitterMode(value.toInt())
            Proto.KEY_AUTO_RECONNECT -> client?.setAutoReconnect(value != 0L)
            Proto.KEY_LINK_TIMEOUT -> client?.setLinkTimeoutSec(value.toInt())
        }
    }

    private fun applyAllLocalSettings() {
        val c = client ?: return
        c.setJitterMode(AppState.jitterMode)
        c.setAutoReconnect(AppState.autoReconnect)
        c.setLinkTimeoutSec(AppState.linkTimeoutSec)
    }

    // ---------------------------------------------------------- notification
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.description = getString(R.string.channel_desc)
                channel.setShowBadge(false)
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_audio)
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    private fun maybeNotify(stats: RxStats) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationMs < 1000) return
        lastNotificationMs = now
        if (!AppState.notifyEnabled) {
            if (notifyWithMetrics) {
                notifyWithMetrics = false
                try {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, buildNotification("LAN 音频串流 · " + AppState.state, "通知栏指标已关闭"))
                } catch (ignored: Throwable) {
                }
            }
            return
        }
        notifyWithMetrics = true
        val title = "LAN 音频串流 · " + AppState.state
        val text = buildString {
            append("延迟 ")
            append(if (stats.e2eUs < 0) "--" else String.format("%.0fms", stats.e2eUs / 1000.0))
            append(" · 码率 ")
            append(String.format("%.0fkbps", stats.recvBitrateBps / 1000.0))
            append(" · 丢包 ")
            append(String.format("%.2f%%", stats.lossPercent()))
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification(title, text))
        } catch (ignored: Throwable) {
        }
    }

    // ---------------------------------------------------------------- locks
    private fun acquireLocks() {
        val sb = StringBuilder()
        try {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "las:playback")
            wl.setReferenceCounted(false)
            wl.acquire(6 * 60 * 60 * 1000L)
            wakeLock = wl
            sb.append("Wake=").append(if (wl.isHeld) "OK" else "失败")
        } catch (t: Throwable) {
            sb.append("Wake=失败(").append(t.javaClass.simpleName).append(")")
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // WIFI_MODE_FULL_LOW_LATENCY (API 29+) 会让 WiFi 固件不进入省电模式，
            // 这是「音频周期性断续」最常见的根因；低版本退回 HIGH_PERF。
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            val wl = wifi.createWifiLock(mode, "las:wifi")
            wl.setReferenceCounted(false)
            wl.acquire()
            wifiLock = wl
            sb.append(" Wifi=").append(if (wl.isHeld) "OK" else "失败")
        } catch (t: Throwable) {
            sb.append(" Wifi=失败(").append(t.javaClass.simpleName).append(")")
        }
        try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ml = wifi.createMulticastLock("las:mcast")
            ml.setReferenceCounted(false)
            ml.acquire()
            multicastLock = ml
            sb.append(" Mcast=").append(if (ml.isHeld) "OK" else "失败")
        } catch (t: Throwable) {
            sb.append(" Mcast=失败(").append(t.javaClass.simpleName).append(")")
        }
        AppState.lockStatus = sb.toString()
        AppState.log("锁 " + sb)
        if (!sb.contains("Wifi=OK")) {
            AppState.log("提示: WiFi 高性能锁未拿到")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (ignored: Throwable) {
        }
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (ignored: Throwable) {
        }
        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        } catch (ignored: Throwable) {
        }
        wakeLock = null
        wifiLock = null
        multicastLock = null
    }
}
