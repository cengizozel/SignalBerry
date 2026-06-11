package com.example.signalberry;

/**
 * WSOLA time-stretch for speech: changes tempo without changing pitch.
 * Mono 16-bit PCM. Each output frame is taken from the input at
 * analysisHop = synthesisHop * speed, aligned within a small search window
 * by cross-correlation against the previous frame's tail, then cross-faded.
 * Costs are tuned for a BlackBerry Q10: correlation is computed on every
 * 4th sample over a ~5ms window.
 */
final class TimeStretcher {

    private final int frame;     // analysis/synthesis frame length
    private final int overlap;   // crossfade length
    private final int seek;      // alignment search window
    private final int hs;        // synthesis hop = frame - overlap

    private short[] inBuf = new short[8192];
    private int inLen;
    private double pos;          // fractional analysis position in inBuf

    private final short[] tail;  // held-back end of the previous frame
    private boolean hasTail;

    private short[] outBuf = new short[8192];
    private int outLen;

    TimeStretcher(int sampleRate) {
        frame   = Math.max(256, sampleRate / 50);   // ~20 ms
        overlap = frame / 4;
        seek    = frame / 4;
        hs      = frame - overlap;
        tail    = new short[overlap];
    }

    void flush() {
        inLen = 0;
        pos = 0;
        hasTail = false;
    }

    /** Feed PCM, get stretched PCM. Returned array is reused — copy length outLength(). */
    short[] process(short[] in, int n, float speed) {
        if (inLen + n > inBuf.length) {
            short[] g = new short[Math.max(inBuf.length * 2, inLen + n)];
            System.arraycopy(inBuf, 0, g, 0, inLen);
            inBuf = g;
        }
        System.arraycopy(in, 0, inBuf, inLen, n);
        inLen += n;
        outLen = 0;

        double ha = hs * (double) speed;
        while ((int) pos + frame + seek <= inLen) {
            int s = (int) pos;
            int best = 0;
            if (hasTail) {
                long bestScore = Long.MIN_VALUE;
                for (int o = 0; o < seek; o += 2) {
                    long score = 0;
                    for (int i = 0; i < overlap; i += 4)
                        score += (long) tail[i] * inBuf[s + o + i];
                    if (score > bestScore) { bestScore = score; best = o; }
                }
            }
            int start = s + best;
            ensureOut(hs);
            if (hasTail) {
                for (int i = 0; i < overlap; i++)
                    outBuf[outLen + i] = (short) ((tail[i] * (overlap - i)
                            + inBuf[start + i] * i) / overlap);
            } else {
                System.arraycopy(inBuf, start, outBuf, outLen, overlap);
            }
            System.arraycopy(inBuf, start + overlap, outBuf, outLen + overlap, hs - overlap);
            outLen += hs;
            System.arraycopy(inBuf, start + hs, tail, 0, overlap);
            hasTail = true;
            pos += ha;
        }

        // compact consumed input; at high speeds pos can overshoot the buffered
        // input — the un-consumed remainder carries into the next call via pos
        int keep = Math.min(inLen, Math.max(0, (int) pos));
        if (keep > 0) {
            System.arraycopy(inBuf, keep, inBuf, 0, inLen - keep);
            inLen -= keep;
            pos -= keep;
        }
        return outBuf;
    }

    int outLength() { return outLen; }

    private void ensureOut(int more) {
        if (outLen + more > outBuf.length) {
            short[] g = new short[Math.max(outBuf.length * 2, outLen + more)];
            System.arraycopy(outBuf, 0, g, 0, outLen);
            outBuf = g;
        }
    }
}
