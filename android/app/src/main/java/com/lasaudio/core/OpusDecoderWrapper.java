package com.lasaudio.core;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;

/** Thin wrapper around the pure-Java Opus decoder (no NDK / native libraries required). */
public final class OpusDecoderWrapper {

    private OpusDecoder decoder;
    private final int sampleRate;
    private final int channels;

    public OpusDecoderWrapper(int sampleRate, int channels) throws OpusException {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.decoder = new OpusDecoder(sampleRate, channels);
    }

    public int getChannels() {
        return channels;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    /** Decodes one packet into {@code pcm}; frameSamples is per channel. */
    public int decode(byte[] data, int offset, int length, short[] pcm, int frameSamples) throws OpusException {
        return decoder.decode(data, offset, length, pcm, 0, frameSamples, false);
    }

    /** Packet loss concealment: synthesises a replacement for a missing frame. */
    public int decodeLost(short[] pcm, int frameSamples) throws OpusException {
        return decoder.decode(null, 0, 0, pcm, 0, frameSamples, false);
    }

    public void reset() {
        decoder.resetState();
    }
}
