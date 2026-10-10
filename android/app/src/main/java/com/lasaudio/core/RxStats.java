package com.lasaudio.core;

/** Snapshot of receiver metrics, pushed to the UI every 500 ms. */
public final class RxStats {
    public long recv;
    public long lost;
    public long late;
    public long dup;
    public long recovered;
    public long plcFrames;
    public long underruns;
    public long decodedFrames;
    public long overflowDrops;
    public long silenceFrames;
    public long decodeErrors;
    public long seqMax;
    /** DTX 静音帧（本地补静音播放，不计欠载/丢包）。 */
    public long dtxSilenceFrames;
    /** 发送端空闲（PC 没在放声音/DTX）时补的静音帧数，不计入欠载。 */
    public long idleSilenceFrames;

    public double jitterUs;
    public double e2eUs = -1;
    public double bufferUs;
    public double outputBufferUs;
    public double targetBufferUs;
    public double recvBitrateBps;
    /** 实时速度：音频负载字节/秒（滑窗＝指标刷新周期，由接收线程统计，界面只负责显示）。 */
    public double speedBps;
    /** 最近一次速率结算实际使用的窗口（毫秒）；包太稀（DTX/静音）时会自动放宽，最大 1000。 */
    public int rateWindowMs;
    /** 该窗口内的包数：与 rateWindowMs 一起可判断读数是否已自动放宽。 */
    public int rateSamples;
    public long bytesReceived;

    public long rttUs;
    public long clockOffsetUs;

    /** 诊断：最近一次「包间隔 > 60ms」的时长（毫秒）。 */
    public long lastGapMs;
    /** 诊断：累计停顿次数。 */
    public int gapEvents;
    /** 诊断：停顿后 30ms 内到达的包数（多 = 射频缓冲一次性冲出来，少 = 真丢包）。 */
    public int lastBurstCount;

    public String state = "空闲";
    public String server = "-";
    public int sampleRate = 48000;
    public int channels = 2;
    public int frameMs = 10;
    public long sessionMs;

    public double lossPercent() {
        long total = recv + lost;
        return total == 0 ? 0 : lost * 100.0 / total;
    }
}
