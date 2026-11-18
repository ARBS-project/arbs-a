package com.limelight.arbs;

/**
 * ARBS A1-3b/3d: 외부망 지터 버퍼(적응형 재생 지연). 순수 로직(엔진 스레드 전용).
 *
 * 동작(1 샘플 = 1 ms, 프레임 = 8 샘플):
 *  - 재생 전 대기: 큐가 [target] 샘플 이상 찰 때까지 보내지 않는다(그동안 무음 프레임).
 *  - 재생 중 큐 < 8 이면 언더런: 재생을 멈추고 다시 채운 뒤 재개, target += {@link #STEP_UP_MS}(자동일 때만).
 *  - 큐 > target + {@link #OVERFLOW_MS}(8) 이면 오래된 샘플을 버려 target + {@link #KEEP_MARGIN_MS} 로 줄인다(지연 상한).
 *  - A1-3d 자동 목표 = PCM 도착 간격 p95 + 4 ms (최소 {@link #MIN_TARGET_MS}=12, 최대 {@link #MAX_TARGET_MS}=80).
 *    올릴 때는 즉시, 내릴 때는 최근 2초 언더런이 없을 때 초당 2 ms씩. 고정 지연이면 적응 없음.
 * 근거: 도착이 늦은 만큼(p95 - 5 ms 공칭 간격) + 프레임 8 샘플 단위 소비를 커버하면 큐가 8 미만으로 떨어지지 않는다.
 */
public final class ApexJitterBuffer {
    public static final int MIN_TARGET_MS = 12;
    public static final int MAX_TARGET_MS = 80;
    public static final int INITIAL_TARGET_MS = 24;
    public static final int STEP_UP_MS = 4;
    public static final int OVERFLOW_MS = 8;
    public static final int KEEP_MARGIN_MS = 2;
    public static final int P95_MARGIN_MS = 4;
    public static final int DOWN_STEP_MS = 2;
    public static final long QUIET_BEFORE_DOWN_NS = 2_000_000_000L;
    public static final int MIN_PACKETS_FOR_ADAPT = 20;
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

    private int target = INITIAL_TARGET_MS;
    private int fixedMs;
    private boolean playing;
    private long lastUnderrunNs;
    private long underrunTotal;
    private long trimTotal;

    /** 0 = 자동(p95 적응), 16..80 = 고정 지연(적응 없음, 언더런 시 재충전은 유지). */
    public void setFixed(int ms) {
        fixedMs = ms <= 0 ? 0 : Math.max(MIN_TARGET_MS, Math.min(MAX_TARGET_MS, ms));
        if (fixedMs > 0) target = fixedMs;
    }

    public int getFixed() { return fixedMs; }

    public int getTarget() { return target; }

    public boolean isPlaying() { return playing; }

    public long getAndResetUnderruns() { long v = underrunTotal; underrunTotal = 0; return v; }

    public long getAndResetTrims() { long v = trimTotal; trimTotal = 0; return v; }

    /** PCM 이 끊겨 큐를 비웠을 때(또는 활성화 때): 재생 상태를 초기화한다(target 은 유지). */
    public void reset(long nowNs) {
        playing = false;
        lastUnderrunNs = nowNs;
    }

    /** 1초마다: 도착 간격 p95(ms)와 그 창의 패킷 수로 자동 목표를 갱신. 고정이면 무시. */
    public void adaptFromP95(double p95Ms, int packets, long nowNs) {
        if (fixedMs > 0 || packets < MIN_PACKETS_FOR_ADAPT) return;
        int desired = (int) Math.max(MIN_TARGET_MS, Math.min(MAX_TARGET_MS, Math.ceil(p95Ms) + P95_MARGIN_MS));
        if (desired > target) {
            target = desired;
        } else if (desired < target && nowNs - lastUnderrunNs >= QUIET_BEFORE_DOWN_NS) {
            target = Math.max(desired, target - DOWN_STEP_MS);
        }
    }

    /** 슬롯마다 한 번 호출. [queue] = 현재 큐 샘플 수. */
    public Result decide(int queue, long nowNs, Result out) {
        out.play = false;
        out.trimTo = -1;
        out.underrun = false;
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
            if (fixedMs == 0) target = Math.min(MAX_TARGET_MS, target + STEP_UP_MS);
            return out;
        }
        out.play = true;
        return out;
    }
}
