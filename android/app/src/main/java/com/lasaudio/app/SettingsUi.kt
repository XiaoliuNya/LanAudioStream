package com.lasaudio.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lasaudio.core.Proto

/**
 * 设置项：每一项 = 标题 + 控件 + 说明文字（说明放在控件下方）。
 *
 * submit = true  ：点「保存设置」时提交给电脑端
 * immediate = true：改动立即在本机生效（本机设置）
 * allowCustom = true：数值型选项可让用户自行输入（列表里多一项「自定义…」）
 */
class SettingRow(
    val key: Byte,
    val submit: Boolean,
    val immediate: Boolean,
    val defaultValue: Long,
    val title: String,
    val desc: String,
    val kind: Kind,
    val labels: List<String> = emptyList(),
    val values: List<Long> = emptyList(),
    val min: Long = 0,
    val max: Long = 100,
    val allowCustom: Boolean = false,
    val unit: String = ""
) {
    enum class Kind { SWITCH, CHOICE, SLIDER }

    var view: View? = null
    var onValue: ((Long) -> Unit)? = null
    var customValue: Long? = null
    var adapter: ArrayAdapter<String>? = null
    var dynLabels: MutableList<String>? = null
    var suppress = false

    fun setValue(v: Long) {
        when (kind) {
            Kind.SWITCH -> (view as? Switch)?.isChecked = v != 0L
            Kind.CHOICE -> {
                val sp = view as? Spinner ?: return
                val idx = values.indexOf(v)
                if (idx >= 0) {
                    customValue = null
                    sp.setSelection(idx, false)
                } else if (allowCustom) {
                    setCustom(sp, v)
                }
            }
            Kind.SLIDER -> (view as? SeekBar)?.progress = v.toInt()
        }
    }

    /** 选中「自定义」并写好数值（不触发回调）。 */
    fun setCustom(sp: Spinner, v: Long) {
        customValue = v
        val list = dynLabels
        if (list != null && allowCustom) {
            val text = "自定义: " + v + (if (unit.isEmpty()) "" else " " + unit)
            if (list.size <= labels.size) list.add(text) else list[labels.size] = text
            adapter?.notifyDataSetChanged()
        }
        suppress = true
        sp.setSelection(labels.size, false)
    }

    fun currentValue(): Long = when (kind) {
        Kind.SWITCH -> if ((view as? Switch)?.isChecked == true) 1L else 0L
        Kind.CHOICE -> {
            val pos = (view as? Spinner)?.selectedItemPosition ?: 0
            if (allowCustom && pos >= labels.size) customValue ?: values.firstOrNull() ?: 0L
            else values.getOrElse(pos) { values.firstOrNull() ?: 0L }
        }
        Kind.SLIDER -> ((view as? SeekBar)?.progress ?: 0).toLong()
    }
}

object SettingsUi {

    fun build(activity: Activity, container: LinearLayout, rows: List<SettingRow>, onChange: (SettingRow) -> Unit) {
        container.removeAllViews()
        for (row in rows) {
            container.addView(buildRow(activity, row, onChange))
        }
    }

    private fun buildRow(activity: Activity, row: SettingRow, onChange: (SettingRow) -> Unit): View {
        val d = activity.resources.displayMetrics.density
        val wrap = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            setBackgroundColor(Color.parseColor("#141922"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * d).toInt() }
        }

        val control = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(activity).apply {
            text = row.title
            setTextColor(Color.parseColor("#7FD4FF"))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        control.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        when (row.kind) {
            SettingRow.Kind.SWITCH -> {
                val sw = Switch(activity)
                sw.setOnCheckedChangeListener { _, checked ->
                    if (!sw.isPressed) return@setOnCheckedChangeListener
                    onChange(row)
                }
                row.view = sw
                control.addView(sw)
            }
            SettingRow.Kind.CHOICE -> {
                val labels = ArrayList(row.labels)
                if (row.allowCustom) labels.add("自定义…")
                row.dynLabels = labels
                val spinner = Spinner(activity)
                val adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                row.adapter = adapter
                spinner.adapter = adapter
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    var first = true
                    override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                        if (first) {
                            first = false
                            return
                        }
                        if (row.suppress) {
                            row.suppress = false
                            return
                        }
                        if (row.allowCustom && position >= row.labels.size) {
                            promptCustom(activity, row, spinner, onChange)
                        } else {
                            row.customValue = null
                            onChange(row)
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
                row.view = spinner
                control.addView(spinner, LinearLayout.LayoutParams((150 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            SettingRow.Kind.SLIDER -> {
                val sb = SeekBar(activity)
                sb.max = (row.max - row.min).toInt()
                sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) onChange(row)
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
                row.view = sb
                control.addView(sb, LinearLayout.LayoutParams((170 * d).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }

        val descView = TextView(activity).apply {
            text = row.desc
            setTextColor(Color.parseColor("#8A97A8"))
            textSize = 11f
            setPadding(0, (6 * d).toInt(), 0, 0)
        }

        wrap.addView(control)
        wrap.addView(descView)
        return wrap
    }

    /** 数值型选项的「自定义」输入框。 */
    private fun promptCustom(activity: Activity, row: SettingRow, spinner: Spinner, onChange: (SettingRow) -> Unit) {
        val d = activity.resources.displayMetrics.density
        val previous = row.customValue ?: row.defaultValue
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(previous.toString())
            setSelection(text.length)
        }
        val box = FrameLayout(activity).apply {
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
            addView(input, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val suffix = if (row.unit.isEmpty()) "" else "，单位 " + row.unit + "（" + row.min + "–" + row.max + "）"
        AlertDialog.Builder(activity)
            .setTitle(row.title + " · 自定义" + suffix)
            .setView(box)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().trim().toLongOrNull()
                if (v == null) {
                    Toast.makeText(activity, "请输入数字", Toast.LENGTH_SHORT).show()
                    row.setValue(previous)
                } else {
                    val clamped = v.coerceIn(row.min, row.max)
                    if (clamped != v) {
                        Toast.makeText(activity, "已取值到 " + clamped, Toast.LENGTH_SHORT).show()
                    }
                    row.setCustom(spinner, clamped)
                    onChange(row)
                }
            }
            .setNegativeButton("取消") { _, _ -> row.setValue(previous) }
            .setCancelable(false)
            .show()
    }

    // ------------------------------------------------------------ 定义
    // 电脑端生效项：改完点「保存设置」才会下发。
    fun remoteRows(): List<SettingRow> = listOf(
        SettingRow(
            Proto.KEY_CODEC, true, false, 1L, "音频格式", "Opus 压缩：码率可控、抗抖动强（推荐）。PCM 无损：不压缩、几乎零编码延迟、音质无损，但 48kHz 立体声约 1.5 Mbps，只建议稳定的千兆/5GHz 局域网（该模式自动关闭冗余，帧长上限 10 ms）。",
            SettingRow.Kind.CHOICE, listOf("Opus 压缩", "PCM 无损"), listOf(1L, 2L)
        ),
        SettingRow(
            Proto.KEY_SAMPLE_RATE, true, false, 48000L, "采样率", "每秒采样次数。Opus 只支持 8/12/16/24/48 kHz，所以这里只提供合法档位；48 kHz 覆盖人耳全部频段（推荐），降到 24/16 kHz 可明显省带宽。",
            SettingRow.Kind.CHOICE, listOf("48 kHz", "24 kHz", "16 kHz", "12 kHz", "8 kHz"),
            listOf(48000L, 24000L, 16000L, 12000L, 8000L)
        ),
        SettingRow(
            Proto.KEY_BITRATE_KBPS, true, false, 128L, "编码码率", "音频压缩后的目标码率，可直接在「自定义…」里输入 32–320 之间任意值。越高音质越好、占用带宽越大；局域网建议 128–192 kbps。",
            SettingRow.Kind.CHOICE, listOf("64 kbps", "96 kbps", "128 kbps", "160 kbps", "192 kbps", "256 kbps", "320 kbps"),
            listOf(64L, 96L, 128L, 160L, 192L, 256L, 320L), min = 32, max = 320, allowCustom = true, unit = "kbps"
        ),
        SettingRow(
            Proto.KEY_FRAME_MS, true, false, 10L, "帧长", "每包承载的音频时长，可自定义 5–40 ms。越短延迟越低、包越密（丢包更敏感）；推荐 10 ms；PCM 无损模式上限 10 ms。",
            SettingRow.Kind.CHOICE, listOf("5 ms", "10 ms", "20 ms", "40 ms"), listOf(5L, 10L, 20L, 40L),
            min = 5, max = 40, allowCustom = true, unit = "ms"
        ),
        SettingRow(
            Proto.KEY_CHANNELS, true, false, 2L, "声道", "立体声保留左右声场；单声道把左右混合，带宽约省一半，适合语音或小音箱。",
            SettingRow.Kind.CHOICE, listOf("立体声", "单声道"), listOf(2L, 1L)
        ),
        SettingRow(
            Proto.KEY_REDUNDANCY, true, false, 1L, "抗丢包冗余", "每个包里额外带上上一帧。丢一个包时能立刻恢复、不爆音，代价是带宽约翻倍；WiFi 稳定时可关掉。",
            SettingRow.Kind.SWITCH
        ),
        SettingRow(
            Proto.KEY_MUTE_SPEAKER, true, false, 0L, "静音电脑扬声器", "关掉电脑本机外放，但采集与串流照常进行——适合只想用手机听的场景。电脑端会显示真实状态。",
            SettingRow.Kind.SWITCH
        ),
        SettingRow(
            Proto.KEY_CAPTURE_BUFFER_MS, true, false, 20L, "采集缓冲", "电脑端采集音频的缓冲长度，可自定义 5–200 ms。越小延迟越低，过小在低配电脑上可能爆音；网络或负载较差时选 30–50 ms。",
            SettingRow.Kind.CHOICE, listOf("10 ms", "20 ms", "30 ms", "50 ms"), listOf(10L, 20L, 30L, 50L),
            min = 5, max = 200, allowCustom = true, unit = "ms"
        ),
        SettingRow(
            Proto.KEY_DTX, true, false, 0L, "静音省流 (DTX)", "电脑端没有声音时只发极少量「静音标记包」（约 10 包/秒、每包 24 字节），带宽从上百 kbps 降到几 kbps，手机端自动补静音、不计丢包。音乐/连续声音场景可关闭。",
            SettingRow.Kind.SWITCH
        ),
    )

    // 本机生效项：改动立即生效；其中抖动策略/自动重连/断连超时会随「保存设置」同步给电脑端。
    fun localRows(): List<SettingRow> = listOf(
        SettingRow(
            Proto.KEY_LINK_TIMEOUT, true, true, 8L, "断连超时", "多久收不到音频就重连（电脑端也用它判断「客户端无响应」）。3–60 秒可选，也可自定义 1–600 秒。时间调长更能扛住 WiFi 瞬时卡顿；调短则掉线后恢复更快。",
            SettingRow.Kind.CHOICE, listOf("3 秒", "5 秒", "8 秒", "15 秒", "30 秒", "60 秒"),
            listOf(3L, 5L, 8L, 15L, 30L, 60L), min = 1, max = 600, allowCustom = true, unit = "秒"
        ),
        SettingRow(
            Proto.KEY_JITTER_MODE, true, true, 0L, "抖动缓冲策略", "自动：按网络质量自适应（推荐）；延迟优先：固定最小缓冲（40 ms），网络抖动时会断音；稳定优先：固定最大缓冲（400 ms），几乎不断音但延迟更高。",
            SettingRow.Kind.CHOICE, listOf("自动", "延迟优先", "稳定优先"), listOf(0L, 1L, 2L)
        ),
        SettingRow(
            Proto.KEY_AUTO_RECONNECT, true, true, 1L, "断线自动重连", "与电脑断开后自动重新发现并连接（指数退避，最长 4 秒一次）。关闭后需要手动点“开始接收”。",
            SettingRow.Kind.SWITCH
        ),
        SettingRow(
            Proto.KEY_METRICS_HZ, false, true, 2L, "指标刷新率", "实时指标（文本、曲线、电平条）的刷新频率，1–10 Hz 可选，也可自定义 1–60 Hz。越高越跟手、越费电；曲线与电平条本身始终按屏幕刷新率绘制。",
            SettingRow.Kind.CHOICE, listOf("1 Hz", "2 Hz", "5 Hz", "10 Hz"), listOf(1L, 2L, 5L, 10L),
            min = 1, max = 60, allowCustom = true, unit = "Hz"
        ),
        SettingRow(
            Proto.KEY_NOTIFY, false, true, 1L, "通知栏显示指标", "关闭后通知栏不再刷新延迟/码率/丢包（后台播放服务本身不受影响）。",
            SettingRow.Kind.SWITCH
        ),
    )
}
