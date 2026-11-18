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

    /** A1-3b: 지터 버퍼용 최대 용량(실제 상한은 {@link #setLimit}, 기본 {@link #QUEUE_LIMIT}). */
    public static final int CAPACITY = 128;
    private final int[] qL = new int[CAPACITY];
    private final int[] qR = new int[CAPACITY];
    private int limit = QUEUE_LIMIT;
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

    /** 이득 G(0.5~2.0 권장). 0.1..4.0 으로 제한. 상한(리미터)은 항상 적용된다. */
    public synchronized void setGain(float g) { gain = Math.max(0.1f, Math.min(4.0f, g)); }

    public synchronized float getGain() { return gain; }

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

    /** 큐 상한(샘플 수, 1..CAPACITY). 넘으면 가장 오래된 샘플을 버린다. */
    public synchronized void setLimit(int n) { limit = Math.max(1, Math.min(CAPACITY, n)); }

    /** 큐가 [keep] 개를 넘으면 오래된 쪽부터 버려 [keep] 개로 만든다. 버린 개수 반환(폐기 통계에 포함). */
    public synchronized int trimTo(int keep) {
        int k = Math.max(0, keep);
        if (size <= k) return 0;
        int d = size - k;
        head = (head + d) % CAPACITY;
        size = k;
        dropped += d;
        return d;
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

    /** 부드러운 리미터 시작점(상한 대비). A1-3c. */
    public static final double KNEE = 0.8;

    // A1-3c 1초 통계(스케일 호출마다 갱신, synchronized)
    private long stN, stLimited, stClipped;
    private double stInSq, stOutSq;

    /**
     * s16 영역 값 -> 그립 샘플. A1-3c: out = s16/32768 x 상한(슬라이더, 최대 60) x 이득 G, 비례 축소.
     * 상한의 {@link #KNEE}(80%)부터 tanh 로 완만히 눌러 상한을 넘지 않는다(풀스케일 입력 = 상한 부근).
     */
    public synchronized int scale(float y) {
        int lim = Math.min(cap, ApexProtocol.MAX_AMPLITUDE);
        double in = y / 32768.0;
        double x = in * lim * gain;
        double ax = Math.abs(x);
        double knee = KNEE * lim;
        boolean limited = false;
        double o = ax;
        if (lim > 0 && ax > knee) {
            double room = lim - knee;
            o = knee + room * Math.tanh((ax - knee) / room);
            limited = true;
        }
        int v = (int) Math.round(x < 0 ? -o : o);
        v = Math.max(-lim, Math.min(lim, v));
        stN++;
        if (limited) stLimited++;
        if (lim > 0 && Math.abs(v) >= lim) stClipped++;
        stInSq += in * in;
        stOutSq += (double) v * v;
        return v;
    }

    /** [샘플 수, 리미터 동작 %, 상한 도달 %, 입력 RMS %FS, 출력 RMS(단위), 출력 RMS 상한 대비 %] 후 초기화. */
    public synchronized double[] getAndResetLevels() {
        double n = Math.max(1, stN);
        int lim = Math.min(cap, ApexProtocol.MAX_AMPLITUDE);
        double outRms = Math.sqrt(stOutSq / n);
        double[] r = {stN, 100.0 * stLimited / n, 100.0 * stClipped / n, 100.0 * Math.sqrt(stInSq / n),
                outRms, lim > 0 ? 100.0 * outRms / lim : 0};
        stN = stLimited = stClipped = 0;
        stInSq = stOutSq = 0;
        return r;
    }

    private void push(int l, int r) {
        while (size >= limit) {
            head = (head + 1) % CAPACITY;
            size--;
            dropped++;
        }
        int idx = (head + size) % CAPACITY;
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
            head = (head + 1) % CAPACITY;
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
