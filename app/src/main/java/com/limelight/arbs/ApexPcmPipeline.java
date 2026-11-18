package com.limelight.arbs;

/**
 * ARBS A1-2: 48 kHz S16LE 스테레오 PCM -> 저역통과(~400 Hz) + 48:1 감축 -> 1 kHz int8 샘플 큐(좌/우 그립).
 * 안드로이드 의존성 없음(단위 테스트 대상). 모든 공개 메서드는 synchronized(PCM 콜백 스레드 ↔ 송신 스레드).
 *
 * 필터: Hamming 창 sinc FIR, 641탭(군지연 320샘플 = 6.67 ms), 차단 400 Hz, DC 이득 1.
 * 스케일: int8 = round(y/32768 x G x 127), 상한 +-min(사용자 상한, 60). 큐 상한 24 샘플(= 24 ms), 넘치면 오래된 것부터 폐기.
 */
public final class ApexPcmPipeline {
    public static final int DECIMATION = 48;
    public static final int TAPS = 641;
    public static final double CUTOFF_HZ = 400.0;
    public static final double SAMPLE_RATE = 48000.0;
    public static final int QUEUE_LIMIT = 24;
    public static final int DEFAULT_CAP = 40;

    static final float[] H = design();

    private final float[] bufL = new float[TAPS * 2];
    private final float[] bufR = new float[TAPS * 2];
    private int pos;        // next write position (0..TAPS-1)
    private int phase;      // inputs since last output

    private float gain = 1.0f;
    private int cap = DEFAULT_CAP;

    private final int[] qL = new int[QUEUE_LIMIT];
    private final int[] qR = new int[QUEUE_LIMIT];
    private int head, size;
    private long dropped;
    private int peak;

    static float[] design() {
        float[] h = new float[TAPS];
        int m = (TAPS - 1) / 2;
        double wc = 2.0 * Math.PI * CUTOFF_HZ / SAMPLE_RATE;
        double sum = 0;
        double[] t = new double[TAPS];
        for (int i = 0; i < TAPS; i++) {
            int n = i - m;
            double ideal = n == 0 ? wc / Math.PI : Math.sin(wc * n) / (Math.PI * n);
            double w = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / (TAPS - 1));
            t[i] = ideal * w;
            sum += t[i];
        }
        for (int i = 0; i < TAPS; i++) h[i] = (float) (t[i] / sum);
        return h;
    }

    public synchronized void setGain(float g) { gain = g; }

    /** 사용자 상한(0..60). 하드 상한 60 을 넘을 수 없다. */
    public synchronized void setCap(int c) { cap = Math.max(0, Math.min(ApexProtocol.MAX_AMPLITUDE, c)); }

    public synchronized int getCap() { return cap; }

    /** 필터 이력 초기화(시퀀스 갭 등). 큐는 건드리지 않는다. */
    public synchronized void resetFilter() {
        java.util.Arrays.fill(bufL, 0f);
        java.util.Arrays.fill(bufR, 0f);
        pos = 0;
        phase = 0;
    }

    public synchronized void clearQueue() {
        head = 0;
        size = 0;
    }

    /** [pcm] = 인터리브 L,R S16 샘플 [frames]쌍. 반환 = 만들어진 1 kHz 샘플 수. */
    public synchronized int process(short[] pcm, int frames) {
        int outs = 0;
        int limit = Math.min(frames, pcm.length / 2);
        for (int f = 0; f < limit; f++) {
            float l = pcm[2 * f];
            float r = pcm[2 * f + 1];
            bufL[pos] = l;
            bufL[pos + TAPS] = l;
            bufR[pos] = r;
            bufR[pos + TAPS] = r;
            int newest = pos;
            pos = pos + 1 == TAPS ? 0 : pos + 1;
            if (++phase == DECIMATION) {
                phase = 0;
                // time-ordered window: buf[newest+1 .. newest+TAPS] (oldest -> newest). H is symmetric.
                float accL = 0f, accR = 0f;
                int base = newest + 1;
                for (int i = 0; i < TAPS; i++) {
                    float h = H[i];
                    accL += h * bufL[base + i];
                    accR += h * bufR[base + i];
                }
                push(scale(accL), scale(accR));
                outs++;
            }
        }
        return outs;
    }

    /** s16 영역 값 -> int8 그립 샘플(상한 적용). */
    public synchronized int scale(float y) {
        int lim = Math.min(cap, ApexProtocol.MAX_AMPLITUDE);
        int v = Math.round(y / 32768f * gain * 127f);
        return Math.max(-lim, Math.min(lim, v));
    }

    private void push(int l, int r) {
        if (size == QUEUE_LIMIT) {
            head = (head + 1) % QUEUE_LIMIT;
            size--;
            dropped++;
        }
        int idx = (head + size) % QUEUE_LIMIT;
        qL[idx] = l;
        qR[idx] = r;
        size++;
        int a = Math.max(Math.abs(l), Math.abs(r));
        if (a > peak) peak = a;
    }

    public synchronized int queueSize() { return size; }

    /** 최대 [max] 개를 앞에서부터 꺼내 [l]/[r] 앞쪽에 채운다. 반환 = 꺼낸 개수. */
    public synchronized int pop(int[] l, int[] r, int max) {
        int n = Math.min(max, size);
        for (int i = 0; i < n; i++) {
            l[i] = qL[head];
            r[i] = qR[head];
            head = (head + 1) % QUEUE_LIMIT;
        }
        size -= n;
        return n;
    }

    public synchronized long getAndResetDropped() {
        long d = dropped;
        dropped = 0;
        return d;
    }

    public synchronized int getAndResetPeak() {
        int p = peak;
        peak = 0;
        return p;
    }
}
