package com.example.signalberry;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Minimal voice-note player: MediaCodec sync decode -> TimeStretcher (WSOLA,
 * pitch-preserving speed control) -> AudioTrack stream. Built this way because
 * MediaPlayer has no speed control before API 23 and the Q10 is API 18.
 * Output is always mono: stereo input is downmixed before stretching.
 */
final class VoiceNotePlayer {

    interface Listener {
        void onCompleted();
        void onError();
    }

    private final File file;
    private final Listener listener;
    private final android.os.Handler main =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private Thread thread;
    private volatile AudioTrack track;
    private volatile int sampleRate;

    private volatile boolean paused;
    private volatile boolean released;
    private volatile float speed;
    private volatile long posUs;
    private volatile long durUs;
    private volatile long seekUs = -1;
    private final Object lock = new Object();

    VoiceNotePlayer(File file, float speed, Listener l) {
        this.file = file;
        this.speed = speed;
        this.listener = l;
    }

    void start() {
        thread = new Thread(this::loop, "voice-note");
        thread.start();
    }

    boolean isPlaying() { return !paused && isAlive(); }
    boolean isAlive()   { return !released && thread != null && thread.isAlive(); }
    long getPosMs()     { return posUs / 1000; }
    long getDurMs()     { return durUs / 1000; }

    void pause() {
        paused = true;
        AudioTrack t = track;
        if (t != null) try { t.pause(); } catch (Exception ignored) {}
    }

    void resume() {
        synchronized (lock) { paused = false; lock.notifyAll(); }
    }

    void seekTo(float fraction) {
        if (durUs <= 0) return;
        synchronized (lock) {
            seekUs = (long) (durUs * Math.max(0f, Math.min(1f, fraction)));
            lock.notifyAll();
        }
    }

    void setSpeed(float s) { speed = s; }

    void release() {
        released = true;
        synchronized (lock) { lock.notifyAll(); }
    }

    private void loop() {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        boolean completed = false;
        TimeStretcher stretcher = null;
        int channels = 1;
        short[] mono = new short[4096];
        byte[] outBytes = new byte[8192];
        try {
            ex.setDataSource(file.getAbsolutePath());
            int ti = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { ti = i; fmt = f; break; }
            }
            if (ti < 0) throw new IllegalStateException("no audio track");
            ex.selectTrack(ti);
            if (fmt.containsKey(MediaFormat.KEY_DURATION))
                durUs = fmt.getLong(MediaFormat.KEY_DURATION);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            // deprecated buffer arrays: required for API 18
            ByteBuffer[] ins  = codec.getInputBuffers();
            ByteBuffer[] outs = codec.getOutputBuffers();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            boolean inEos = false, outEos = false;

            while (!released && !outEos) {
                if (seekUs >= 0) {
                    long target;
                    synchronized (lock) { target = seekUs; seekUs = -1; }
                    ex.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    codec.flush();
                    if (stretcher != null) stretcher.flush();
                    inEos = false;
                    posUs = target;
                    AudioTrack t = track;
                    if (t != null) {
                        try { t.pause(); t.flush(); if (!paused) t.play(); } catch (Exception ignored) {}
                    }
                }
                synchronized (lock) {
                    while (paused && !released && seekUs < 0) {
                        try { lock.wait(); } catch (InterruptedException e) { return; }
                    }
                }
                if (released) break;
                if (seekUs >= 0) continue;
                AudioTrack t0 = track;
                if (t0 != null && t0.getPlayState() != AudioTrack.PLAYSTATE_PLAYING)
                    try { t0.play(); } catch (Exception ignored) {}

                if (!inEos) {
                    int ib = codec.dequeueInputBuffer(10_000);
                    if (ib >= 0) {
                        int n = ex.readSampleData(ins[ib], 0);
                        if (n < 0) {
                            codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            codec.queueInputBuffer(ib, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int ob = codec.dequeueOutputBuffer(bi, 10_000);
                if (ob == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    setupTrack(of);
                    stretcher = new TimeStretcher(sampleRate);
                } else if (ob == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outs = codec.getOutputBuffers();
                } else if (ob >= 0) {
                    if (bi.size > 0 && track != null) {
                        // bytes -> mono shorts (downmix stereo)
                        ByteBuffer bb = outs[ob];
                        bb.position(bi.offset);
                        int nSamples = bi.size / 2 / channels;
                        if (mono.length < nSamples) mono = new short[nSamples * 2];
                        for (int i = 0; i < nSamples; i++) {
                            int base = bi.offset + i * 2 * channels;
                            int s0 = (short) ((bb.get(base) & 0xFF) | (bb.get(base + 1) << 8));
                            if (channels == 2) {
                                int s1 = (short) ((bb.get(base + 2) & 0xFF) | (bb.get(base + 3) << 8));
                                s0 = (s0 + s1) / 2;
                            }
                            mono[i] = (short) s0;
                        }
                        bb.clear();
                        float sp = speed;
                        short[] play = mono;
                        int playLen = nSamples;
                        if (stretcher != null && Math.abs(sp - 1f) > 0.01f) {
                            play = stretcher.process(mono, nSamples, sp);
                            playLen = stretcher.outLength();
                        }
                        if (playLen > 0) {
                            if (outBytes.length < playLen * 2) outBytes = new byte[playLen * 2];
                            for (int i = 0; i < playLen; i++) {
                                outBytes[i * 2]     = (byte) (play[i] & 0xFF);
                                outBytes[i * 2 + 1] = (byte) (play[i] >> 8);
                            }
                            track.write(outBytes, 0, playLen * 2);
                        }
                        posUs = bi.presentationTimeUs;
                    }
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                    codec.releaseOutputBuffer(ob, false);
                }
            }
            completed = outEos && !released;
        } catch (Exception e) {
            android.util.Log.e("VoiceNote", "playback failed", e);
            if (!released) main.post(listener::onError);
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
            try { ex.release(); } catch (Exception ignored) {}
            AudioTrack t = track;
            track = null;
            if (t != null) {
                try { t.stop(); } catch (Exception ignored) {}
                t.release();
            }
            released = true;
        }
        if (completed) {
            posUs = durUs;
            main.post(listener::onCompleted);
        }
    }

    private void setupTrack(MediaFormat of) {
        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        // output is mono regardless of source (stereo is downmixed pre-stretch)
        int minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                Math.max(minBuf * 2, 16384), AudioTrack.MODE_STREAM);
        t.play();
        track = t;
    }

    // ---------- waveform + duration (decode once, cache in a sidecar) ----------

    static final class WaveResult {
        final float[] levels;
        final long durMs;
        WaveResult(float[] levels, long durMs) { this.levels = levels; this.durMs = durMs; }
    }

    /** Amplitude bars in 0..1 plus duration. Decodes the whole clip once;
     *  result is cached next to the audio file so it never repeats. */
    static WaveResult waveform(File f, int bars) {
        File side = new File(f.getAbsolutePath() + ".wf");
        if (side.exists() && side.length() == bars + 8) {
            try (java.io.DataInputStream in =
                         new java.io.DataInputStream(new java.io.FileInputStream(side))) {
                byte[] b = new byte[bars];
                in.readFully(b);
                long dur = in.readLong();
                float[] out = new float[bars];
                for (int i = 0; i < bars; i++) out[i] = (b[i] & 0xFF) / 255f;
                return new WaveResult(out, dur);
            } catch (Exception ignored) {}
        }
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(f.getAbsolutePath());
            int ti = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat t = ex.getTrackFormat(i);
                String mime = t.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { ti = i; fmt = t; break; }
            }
            if (ti < 0) return null;
            ex.selectTrack(ti);
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION)
                    ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            if (durUs <= 0) return null;
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            ByteBuffer[] ins  = codec.getInputBuffers();
            ByteBuffer[] outs = codec.getOutputBuffers();
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            double[] sum = new double[bars];
            long[] cnt = new long[bars];
            boolean inEos = false, outEos = false;
            while (!outEos) {
                if (!inEos) {
                    int ib = codec.dequeueInputBuffer(10_000);
                    if (ib >= 0) {
                        int n = ex.readSampleData(ins[ib], 0);
                        if (n < 0) {
                            codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            codec.queueInputBuffer(ib, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int ob = codec.dequeueOutputBuffer(bi, 10_000);
                if (ob == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outs = codec.getOutputBuffers();
                } else if (ob >= 0) {
                    if (bi.size > 0) {
                        int bucket = (int) Math.min(bars - 1,
                                bi.presentationTimeUs * bars / durUs);
                        ByteBuffer ob2 = outs[ob];
                        ob2.position(bi.offset);
                        // every 4th 16-bit sample is plenty for an envelope
                        for (int i = 0; i + 1 < bi.size; i += 8) {
                            short s = (short) ((ob2.get(bi.offset + i) & 0xFF)
                                    | (ob2.get(bi.offset + i + 1) << 8));
                            sum[bucket] += Math.abs(s);
                            cnt[bucket]++;
                        }
                        ob2.clear();
                    }
                    if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                    codec.releaseOutputBuffer(ob, false);
                }
            }
            float[] levels = new float[bars];
            double max = 1;
            for (int i = 0; i < bars; i++) {
                levels[i] = cnt[i] > 0 ? (float) (sum[i] / cnt[i]) : 0f;
                if (levels[i] > max) max = levels[i];
            }
            for (int i = 0; i < bars; i++) levels[i] = (float) Math.min(1.0, levels[i] / max);
            long durMs = durUs / 1000;
            try (java.io.DataOutputStream out =
                         new java.io.DataOutputStream(new java.io.FileOutputStream(side))) {
                for (int i = 0; i < bars; i++) out.writeByte((int) (levels[i] * 255));
                out.writeLong(durMs);
            } catch (Exception ignored) {}
            return new WaveResult(levels, durMs);
        } catch (Exception e) {
            return null;
        } finally {
            try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
            try { ex.release(); } catch (Exception ignored) {}
        }
    }
}
