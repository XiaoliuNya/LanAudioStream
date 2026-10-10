package com.lasaudio.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.lasaudio.core.PcmSink
import kotlin.math.max

/**
 * AudioTrack based PCM sink.
 *
 * 只有一个播放路径：默认媒体路径 + 约 80 ms 缓冲。
 * （低延迟模式已取消：系统侧的延迟相比无线传输可以忽略，而 fast path 会丢掉系统音效。）
 */
class AudioOutput : PcmSink {

    private var track: AudioTrack? = null
    private var sampleRate = 48000
    private var channels = 2
    private var bytesPerFrame = 4
    private var framesWritten = 0L
    /** 已播放帧数（按播放头增量累加，跨 32 位回绕）。 */
    private var playedFrames = 0L
    /** 上次读到的播放头原始值（Int，回绕由增量计算处理）。 */
    private var lastHead = 0
    private var bufferMillis = 0

    @Synchronized
    override fun openAndGetBufferMillis(sampleRate: Int, channels: Int): Int {
        close()
        this.sampleRate = sampleRate
        this.channels = channels
        this.bytesPerFrame = channels * 2
        this.framesWritten = 0L
        this.playedFrames = 0L
        this.lastHead = 0

        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        // 音频设备缓冲本身就是接收端的抗抖动缓冲：太浅会导致周期性断音。
        val targetMillis = 80
        val wanted = sampleRate * bytesPerFrame * targetMillis / 1000
        val bufferSize = max(max(minBuffer, wanted), sampleRate * bytesPerFrame / 100)
        bufferMillis = (bufferSize * 1000L / (sampleRate * bytesPerFrame)).toInt()

        var built: AudioTrack? = null
        try {
            built = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
                .build()
            built.play()
        } catch (t: Throwable) {
            try {
                built?.release()
            } catch (ignored: Throwable) {
            }
            built = AudioTrack(
                AudioManager.STREAM_MUSIC, sampleRate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM
            )
            built.play()
        }
        track = built
        // 播放头基准：之后按「增量累加」统计已播帧数（避免 32 位回绕与无符号相减两类错误）
        lastHead = try {
            built.playbackHeadPosition
        } catch (ignored: Throwable) {
            0
        }
        playedFrames = 0L
        return bufferMillis
    }

    @Synchronized
    override fun write(pcm: ShortArray, samplesPerChannel: Int) {
        val t = track ?: return
        val count = samplesPerChannel * channels
        if (count <= 0 || count > pcm.size) return
        try {
            val written = t.write(pcm, 0, count, AudioTrack.WRITE_BLOCKING)
            if (written > 0) framesWritten += written / channels
            // playbackHeadPosition 是 32 位无符号且会回绕（48kHz 约 24.8 小时），
            // 旧实现 mask 成 long 后与累计写入量相减，一旦设备欠载或回绕就永久失真
            // （实测注入一次停顿时 device 从 30ms 变 0 并保持，使「等晚到帧」的保护失效）。
            // 改为对播放头取增量：差值按 32 位无符号解释，天然跨回绕。
            val head = t.playbackHeadPosition
            playedFrames += (head - lastHead).toLong() and 0xFFFFFFFFL
            lastHead = head
        } catch (t2: Throwable) {
            // device gone; ignore and let the service restart
        }
    }

    @Synchronized
    override fun bufferedMillis(): Int {
        val t = track ?: return 0
        return try {
            val head = t.playbackHeadPosition
            playedFrames += (head - lastHead).toLong() and 0xFFFFFFFFL
            lastHead = head
            val queued = framesWritten - playedFrames
            if (queued <= 0) 0 else (queued * 1000L / sampleRate).toInt()
        } catch (e: Throwable) {
            0
        }
    }

    @Synchronized
    override fun close() {
        val t = track ?: return
        track = null
        try {
            t.pause()
            t.flush()
            t.stop()
        } catch (ignored: Throwable) {
        }
        try {
            t.release()
        } catch (ignored: Throwable) {
        }
    }
}
