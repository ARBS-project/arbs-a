package com.limelight.arbs;

/**
 * ARBS A1-2: 48 kHz S16LE 스테레오 PCM -> 저역통과(~400 Hz) + 48:1 감축 -> 1 kHz int8 샘플 큐(좌/우 그립).
 * 안드로이드 의존성 없음(단위 테스트 대상). 모든 공개 메서드는 synchronized(PCM 콜백 스레드 ↔ 송신 스레드).
 *
 * 필터: Hamming 창 sinc FIR, 161탭(A1-3d: 군지연 80샘플 = 1.67 ms, 기존 641탭 6.67 ms), 차단 400 Hz, DC 이득 1.
 * 스케일(A1-3c/d): out = y/32768 x 상한 x G, 상한의 80%부터 tanh 소프트 리미터. 노이즈 게이트(A1-3d): 5 ms 블록 RMS가
 * 임계 %FS 미만으로 50 ms 지속되면 10 ms 페이드로 출력 0. 큐 용량/상한은 {@link #setLimit}.
 */
public final class ApexPcmPipeline {
    public static final int DECIMATION = 48;
    /** 필터 탭 선택지(A1-3e): 161(1.7 ms) / 321(3.3 ms, 기본) / 641(6.7 ms). */
    public static final int[] TAP_CHOICES = {161, 321, 641};
    public static final int TAPS = 321;
    public static final int MAX_TAPS = 641;
    public static final double CUTOFF_HZ = 400.0;
    public static final double SAMPLE_RATE = 48000.0;
    public static final int QUEUE_LIMIT = 24;
    public static final int DEFAULT_CAP = 40;

    static final float[] H = design(TAPS);

    private static final float[][] CACHE = new float[TAP_CHOICES.length][];

    /** [taps] 탭 필터 계수(캐시). 지원하지 않는 값이면 기본 321. */
    static synchronized float[] designFor(int taps) {
        for (int i = 0; i < TAP_CHOICES.length; i++) {
            if (TAP_CHOICES[i] == taps) {
                if (CACHE[i] == null) CACHE[i] = design(taps);
                return CACHE[i];
            }
        }
        return designFor(TAPS);
    }

    private final float[] bufL = new float[MAX_TAPS * 2];
    private final float[] bufR = new float[MAX_TAPS * 2];
    private int taps = TAPS;
    private float[] hh = H;
    private int pos;        // next write position (0..taps-1)
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

    static float[] design(int TAPS) {
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

    /** 이득 G(1.0~8.0). 0.1..8.0 으로 제한. 상한(리미터)은 항상 적용된다. */
    public synchronized void setGain(float g) { gain = Math.max(0.1f, Math.min(8.0f, g)); }

    public synchronized float getGain() { return gain; }

    /** A1-3e: 필터 탭 수(161/321/641). 군지연 = (taps-1)/2 샘플(48 kHz). 필터 이력 초기화. */
    public synchronized void setTaps(int n) {
        int t = TAPS;
        for (int c : TAP_CHOICES) if (c == n) t = c;
        taps = t;
        hh = designFor(t);
        resetFilter();
    }

    public synchronized int getTaps() { return taps; }

    /** A1-3e 증폭 곡선: false = 기존 이득×tanh, true = ASB 곡선 g·x/(1+(g-1)|x|)(Apex6PcmGain.h:8-22). 기본 ASB. */
    private boolean asbCurve = true;

    public synchronized void setAsbCurve(boolean on) { asbCurve = on; }

    public synchronized boolean isAsbCurve() { return asbCurve; }

    /** 사용자 상한(0..60). 하드 상한 60 을 넘을 수 없다. */
    public synchronized void setCap(int c) { cap = Math.max(0, Math.min(ApexProtocol.MAX_AMPLITUDE, c)); }

    public synchronized int getCap() { return cap; }

    /** 필터 이력 초기화(시퀀스 갭 등). 큐는 건드리지 않는다. */
    public synchronized void resetFilter() {
        java.util.Arrays.fill(bufL, 0f);
        java.util.Arrays.fill(bufR, 0f);
        pos = 0;
        phase = 0;
        gateQuietMsL = gateQuietMsR = 0;
        gateClosedL = gateClosedR = false;
        gateGainL = gateGainR = 1f;
        silentMs = 0;
    }

    // ---- A1-3d 노이즈 게이트
    public static final double GATE_HOLD_MS = 50.0;
    public static final int GATE_FADE_OUTPUTS = 10;       // 1 kHz 출력 10개 = 10 ms
    private float gateThresholdPct = 0.0f;                // 0 = 끄기 (A1-3e: 기본 끔)
    private double gateQuietMsL, gateQuietMsR;
    private boolean gateClosedL, gateClosedR;
    private float gateGainL = 1f, gateGainR = 1f;
    private long stGateOut, stGateClosed;

    /** 게이트 임계값(%FS). 0 이하 = 끔. */
    public synchronized void setGateThreshold(float pctFs) {
        gateThresholdPct = Math.max(0f, Math.min(20f, pctFs));
        if (gateThresholdPct == 0f) {
            gateClosedL = gateClosedR = false;
            gateQuietMsL = gateQuietMsR = 0;
        }
    }

    public synchronized float getGateThreshold() { return gateThresholdPct; }

    /** A1-3e: 블록 RMS 가 이 %FS 미만이면 "무음"으로 센다(긴 무음 시 활성 해제 판단용). */
    public static final double SILENCE_PCT = 0.3;
    private double silentMs;

    /** 입력이 무음(RMS<0.3%FS, 또는 게이트 닫힘)으로 이어진 시간(ms). 큰 블록이 오면 0. */
    public synchronized double getSilentMs() { return silentMs; }

    private void gateUpdate(short[] pcm, int frames) {
        if (frames <= 0) return;
        double sl = 0, sr = 0;
        for (int f = 0; f < frames; f++) {
            double l = pcm[2 * f];
            double r = pcm[2 * f + 1];
            sl += l * l;
            sr += r * r;
        }
        double pl = 100.0 * Math.sqrt(sl / frames) / 32768.0;
        double pr = 100.0 * Math.sqrt(sr / frames) / 32768.0;
        double ms = frames * 1000.0 / SAMPLE_RATE;
        boolean quietAll = pl < SILENCE_PCT && pr < SILENCE_PCT;
        if (gateThresholdPct <= 0f) {
            if (quietAll) silentMs += ms; else silentMs = 0;
            return;
        }
        if (pl < gateThresholdPct) {
            gateQuietMsL += ms;
            if (gateQuietMsL >= GATE_HOLD_MS) gateClosedL = true;
        } else {
            gateQuietMsL = 0;
            gateClosedL = false;
        }
        if (pr < gateThresholdPct) {
            gateQuietMsR += ms;
            if (gateQuietMsR >= GATE_HOLD_MS) gateClosedR = true;
        } else {
            gateQuietMsR = 0;
            gateClosedR = false;
        }
        if (quietAll || (gateClosedL && gateClosedR)) silentMs += ms; else silentMs = 0;
    }

    private static float gateStep(float g, boolean closed) {
        if (closed) return Math.max(0f, g - 1f / GATE_FADE_OUTPUTS);       // 10 ms 페이드 아웃
        return Math.min(1f, g + 0.5f);                                      // 2 ms 안에 복귀(트랜지언트 보존)
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
        gateUpdate(pcm, limit);
        for (int f = 0; f < limit; f++) {
            float l = pcm[2 * f];
            float r = pcm[2 * f + 1];
            bufL[pos] = l;
            bufL[pos + taps] = l;
            bufR[pos] = r;
            bufR[pos + taps] = r;
            int newest = pos;
            pos = pos + 1 == taps ? 0 : pos + 1;
            if (++phase == DECIMATION) {
                phase = 0;
                // time-ordered window: buf[newest+1 .. newest+TAPS] (oldest -> newest). H is symmetric.
                float accL = 0f, accR = 0f;
                int base = newest + 1;
                for (int i = 0; i < taps; i++) {
                    float h = hh[i];
                    accL += h * bufL[base + i];
                    accR += h * bufR[base + i];
                }
                gateGainL = gateStep(gateGainL, gateClosedL);
                gateGainR = gateStep(gateGainR, gateClosedR);
                stGateOut++;
                if (gateClosedL || gateClosedR) stGateClosed++;
                push(scale(accL * gateGainL), scale(accR * gateGainR));
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
        double ax;
        double knee = KNEE * lim;
        boolean limited = false;
        double o;
        if (asbCurve) {
            // ASB 곡선(Apex6PcmGain.h:8-22): y = g*x/(1+(g-1)|x|), x = s16/32768 in [-1,1], 끝점 ±1 -> 상한
            double a = Math.min(1.0, Math.abs(in));
            double yn = gain * a / (1.0 + (gain - 1.0) * a);
            o = yn * lim;
            ax = o;
            limited = lim > 0 && o > knee;       // 상한의 80% 초과 = 압축 영역
        } else {
            double x = in * lim * gain;
            ax = Math.abs(x);
            o = ax;
            if (lim > 0 && ax > knee) {
                double room = lim - knee;
                o = knee + room * Math.tanh((ax - knee) / room);
                limited = true;
            }
        }
        double x = in < 0 ? -1 : 1;
        int v = (int) Math.round(x < 0 ? -o : o);
        v = Math.max(-lim, Math.min(lim, v));
        stN++;
        if (limited) stLimited++;
        if (lim > 0 && Math.abs(v) >= lim) stClipped++;
        stInSq += in * in;
        stOutSq += (double) v * v;
        return v;
    }

    /** [샘플 수, 리미터 동작 %, 상한 도달 %, 입력 RMS %FS, 출력 RMS(단위), 출력 RMS 상한 대비 %, 게이트 닫힘 %] 후 초기화. */
    public synchronized double[] getAndResetLevels() {
        double n = Math.max(1, stN);
        int lim = Math.min(cap, ApexProtocol.MAX_AMPLITUDE);
        double outRms = Math.sqrt(stOutSq / n);
        double[] r = {stN, 100.0 * stLimited / n, 100.0 * stClipped / n, 100.0 * Math.sqrt(stInSq / n),
                outRms, lim > 0 ? 100.0 * outRms / lim : 0, 100.0 * stGateClosed / Math.max(1, stGateOut)};
        stGateOut = stGateClosed = 0;
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
