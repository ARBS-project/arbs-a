package com.limelight.arbs;

/**
 * ARBS A1-3b: 외부망 지터 버퍼(적응형 재생 지연). 순수 로직(엔진 스레드 전용).
 * 근거 EA-A1-U-07: 패킷이 몰려 왔다 끊기면 24 ms 큐가 넘치고(폐기) 비어(언더런) 프레임/초가 떨어진다.
 *
 * 동작(1 샘플 = 1 ms, 프레임 = 8 샘플):
 *  - 재생 전 대기: 큐가 [target] 샘플 이상 찰 때까지 보내지 않는다(그동안 무음 프레임).
 *  - 재생 중 큐 < 8 이면 언더런: 재생을 멈추고 다시 채운 뒤 재개, target += {@link #STEP_UP_MS}(최대 {@link #MAX_TARGET_MS}).
 *  - 큐 > target + {@link #OVERFLOW_MS} 이면 오래된 샘플을 버려 target + {@link #KEEP_MARGIN_MS} 로 줄인다(지연 상한).
 *  - 언더런 없이 1초가 지나면 target 을 1 ms 줄인다(최소 {@link #MIN_TARGET_MS}).
 * 시작 지연은 target(기본 16 ms)만큼 추가된다 — 체감 지연과 끊김의 교환이며 실기 확인 필요(UNVERIFIED).
 */
public final class ApexJitterBuffer {
    public static final int MIN_TARGET_MS = 16;
    public static final int MAX_TARGET_MS = 80;
    public static final int STEP_UP_MS = 8;
    public static final int OVERFLOW_MS = 24;
    public static final int KEEP_MARGIN_MS = 8;
    public static final long DECAY_NS = 1_000_000_000L;
    public static final int FRAME = 8;

    /** 이 버퍼가 필요로 하는 큐 용량(샘플). */
    public static final int REQUIRED_QUEUE = MAX_TARGET_MS + OVERFLOW_MS + FRAME;

    public static final class Result {
        /** true 면 큐에서 FRAME(8) 샘플을 꺼내 보낸다. false 면 무음 프레임. */
        public boolean play;
        /** >=0 이면 보내기 전에 큐를 이 개수로 줄인다(오래된 것 폐기). -1 이면 없음. */
        public int trimTo = -1;
        /** 이번 호출에서 언더런이 발생했는지. */
        public boolean underrun;
    }

    private int target = MIN_TARGET_MS;
    private boolean playing;
    private long lastUnderrunNs;
    private long lastDecayNs;
    private long underrunTotal;
    private long trimTotal;

    public int getTarget() { return target; }

    public boolean isPlaying() { return playing; }

    public long getAndResetUnderruns() { long v = underrunTotal; underrunTotal = 0; return v; }

    public long getAndResetTrims() { long v = trimTotal; trimTotal = 0; return v; }

    /** PCM 이 끊겨 큐를 비웠을 때(또는 활성화 때): 재생 상태를 초기화한다(target 은 유지). */
    public void reset(long nowNs) {
        playing = false;
        lastDecayNs = nowNs;
        lastUnderrunNs = nowNs;
    }

    /** 슬롯마다 한 번 호출. [queue] = 현재 큐 샘플 수. */
    public Result decide(int queue, long nowNs, Result out) {
        out.play = false;
        out.trimTo = -1;
        out.underrun = false;
        if (nowNs - lastDecayNs >= DECAY_NS) {
            lastDecayNs = nowNs;
            if (nowNs - lastUnderrunNs >= DECAY_NS && target > MIN_TARGET_MS) target--;
        }
        if (queue > target + OVERFLOW_MS) {
            out.trimTo = target + KEEP_MARGIN_MS;
            trimTotal++;
            queue = out.trimTo;
        }
        if (!playing) {
            if (queue >= target) playing = true;
            else return out;
        }
        if (queue < FRAME) {
            playing = false;
            out.underrun = true;
            underrunTotal++;
            lastUnderrunNs = nowNs;
            target = Math.min(MAX_TARGET_MS, target + STEP_UP_MS);
            return out;
        }
        out.play = true;
        return out;
    }
}
