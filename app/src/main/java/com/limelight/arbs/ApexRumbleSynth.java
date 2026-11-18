package com.limelight.arbs;

/**
 * ARBS A1-3: 일반 진동(rumble) -> 그립 사인 합성. ASB Apex6HapticBridge.cpp:84-92,931-944 방식:
 *   low(왼쪽 그립) = 65 Hz, high(오른쪽 그립) = 150 Hz 사인, 샘플 간격 1 ms(프레임당 8샘플),
 *   진폭 = (값 16bit 의 상위 8bit)/255 x 상한(ASB 는 x112, 여기서는 설정 상한 <= 60).
 * 값은 다음 갱신 또는 (0,0) 까지 유지하고, {@link #TIMEOUT_NS}(3초) 무갱신이면 자동으로 0 이 된다.
 * set()은 제어 스레드, 나머지는 엔진 스레드에서 호출한다.
 */
public final class ApexRumbleSynth {
    public static final double LOW_HZ = 65.0;
    public static final double HIGH_HZ = 150.0;
    public static final long TIMEOUT_NS = 3_000_000_000L;

    private volatile int packed;          // (low8 << 8) | high8
    private volatile long updatedNs;

    // 엔진 스레드 전용
    private double phaseL, phaseR;

    /** [low16]/[high16] = Moonlight rumble 값(부호 없는 16비트). */
    public void set(int low16, int high16, long nowNs) {
        int lo = (low16 >>> 8) & 0xFF;
        int hi = (high16 >>> 8) & 0xFF;
        updatedNs = nowNs;
        packed = (lo << 8) | hi;
    }

    public void clear() { packed = 0; }

    public int low8() { return (packed >>> 8) & 0xFF; }

    public int high8() { return packed & 0xFF; }

    public boolean isActive() { return packed != 0; }

    /** 3초 무갱신이면 0 으로 되돌리고 true. */
    public boolean checkTimeout(long nowNs) {
        if (packed != 0 && nowNs - updatedNs > TIMEOUT_NS) {
            packed = 0;
            return true;
        }
        return false;
    }

    /** 진폭 = round(값8/255 x cap), 0..min(cap, 60). */
    public static int amplitude(int value8, int cap) {
        int c = Math.max(0, Math.min(ApexProtocol.MAX_AMPLITUDE, cap));
        return (int) Math.round(Math.max(0, Math.min(255, value8)) / 255.0 * c);
    }

    /** 왼쪽 그립 다음 샘플(1 ms 간격). */
    public int nextLeft(int cap) {
        return next(true, low8(), cap);
    }

    public int nextRight(int cap) {
        return next(false, high8(), cap);
    }

    private int next(boolean left, int v8, int cap) {
        double ph = left ? phaseL : phaseR;
        int amp = amplitude(v8, cap);
        int s = (int) Math.round(Math.sin(ph * 2.0 * Math.PI) * amp);
        ph += (left ? LOW_HZ : HIGH_HZ) / 1000.0;
        ph -= Math.floor(ph);
        if (left) phaseL = ph; else phaseR = ph;
        return Math.max(-ApexProtocol.MAX_AMPLITUDE, Math.min(ApexProtocol.MAX_AMPLITUDE, s));
    }

    /**
     * 프레임 8샘플(또는 [count])을 섞는다: 채널별로 PCM 이 비무음이면 PCM 유지, 아니면 rumble 사인으로 대체.
     * @return 이 프레임에서 rumble 이 쓰인 채널 수(0..2)
     */
    public int mix(int[] l, int[] r, int count, int cap) {
        boolean pcmL = false, pcmR = false;
        for (int i = 0; i < count; i++) {
            if (l[i] != 0) pcmL = true;
            if (r[i] != 0) pcmR = true;
        }
        int used = 0;
        if (!pcmL && low8() != 0) {
            for (int i = 0; i < count; i++) l[i] = nextLeft(cap);
            used++;
        }
        if (!pcmR && high8() != 0) {
            for (int i = 0; i < count; i++) r[i] = nextRight(cap);
            used++;
        }
        return used;
    }
}
