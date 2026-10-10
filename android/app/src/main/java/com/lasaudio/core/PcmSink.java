package com.lasaudio.core;

/** Destination for decoded PCM. Implemented by the Android AudioTrack wrapper. */
public interface PcmSink {

    /**
     * (Re)configures the output and returns the output device buffer size in milliseconds.
     * May be called again when the stream format changes.
     */
    int openAndGetBufferMillis(int sampleRate, int channels);

    /** Writes interleaved 16-bit PCM. Must block until the audio device consumed the data. */
    void write(short[] pcm, int samplesPerChannel);

    /** Number of milliseconds currently queued inside the output device. */
    int bufferedMillis();

    void close();
}
