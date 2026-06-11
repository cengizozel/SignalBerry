package com.example.signalberry;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * WSOLA boundary regression: at high speed the analysis position can overshoot
 * the buffered input. The bug shipped as a negative System.arraycopy length
 * that crashed playback ("Cannot play this audio"); these feed adversarial
 * sizes/speeds and assert it never throws and stays bounded.
 */
public class TimeStretcherTest {

    private static short[] tone(int n) {
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) (Math.sin(i * 0.05) * 8000);
        return s;
    }

    @Test public void doubleSpeedNeverThrows() {
        TimeStretcher ts = new TimeStretcher(44100);
        short[] in = tone(2048);
        for (int round = 0; round < 50; round++) {
            short[] out = ts.process(in, in.length, 2.0f);
            int len = ts.outLength();
            assertTrue("len in range", len >= 0 && len <= out.length);
        }
    }

    @Test public void variedSpeedsAndChunkSizesStayBounded() {
        float[] speeds = {0.5f, 1.0f, 1.5f, 2.0f};
        int[] chunks = {1, 7, 256, 4096};
        for (float sp : speeds) {
            TimeStretcher ts = new TimeStretcher(22050);
            for (int c : chunks) {
                short[] in = tone(c);
                short[] out = ts.process(in, in.length, sp);
                int len = ts.outLength();
                assertTrue("speed " + sp + " chunk " + c,
                        len >= 0 && len <= out.length);
            }
        }
    }

    @Test public void flushResetsState() {
        TimeStretcher ts = new TimeStretcher(44100);
        ts.process(tone(4096), 4096, 2.0f);
        ts.flush();
        short[] out = ts.process(tone(4096), 4096, 1.0f);
        assertTrue(ts.outLength() >= 0 && ts.outLength() <= out.length);
    }

    @Test public void slowSpeedProducesMoreOutputThanInput() {
        TimeStretcher ts = new TimeStretcher(44100);
        long total = 0;
        for (int i = 0; i < 20; i++) {
            ts.process(tone(4096), 4096, 0.5f);
            total += ts.outLength();
        }
        // 0.5x stretch should roughly double the sample count over many frames
        assertTrue("0.5x should expand audio, got " + total, total > 20L * 4096);
    }
}
