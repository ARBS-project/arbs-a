package com.limelight.arbs;

/**
 * ARBS A1-3: 8 ms 슬롯 절대 기한(nanoTime) 스케줄러(순수 로직, 안드로이드 의존성 없음).
 * 슬롯 n 의 기한 = t0 + n x 8 ms. 늦으면 기다리지 않고 연속 송신(최대 {@link #MAX_CATCHUP} 프레임 따라잡기),
 * 그 이상 밀리면 밀린 슬롯을 건너뛰고(missed) 기한을 현재로 재동기한다(버스트 금지).
 */
public final class ApexScheduler {
    public static final long SLOT_NS = ApexProtocol.FRAME_MS * 1_000_000L;
    public static final int MAX_CATCHUP = 3;

    public static final class Decision {
        /** 기다릴 시간(ns). 0 이면 기한 도래. */
        public long waitNs;
        /** 이번 호출에서 건너뛴 슬롯 수(재동기 시). */
        public int skipped;
        /** 따라잡기 중(기한보다 늦게 송신)인지. */
        public boolean catchingUp;
    }

    private long t0;
    private long slot;
    private long missedTotal;
    private long resyncTotal;
    private long catchupTotal;

    public void start(long nowNs) {
        t0 = nowNs;
        slot = 0;
    }

    public long target() { return t0 + slot * SLOT_NS; }

    /**
     * [now] 시점의 결정. waitNs>0 이면 호출자가 그만큼 잔 뒤 다시 호출하고, 0 이면 {@link #consume()} 을 부른 뒤 송신한다.
     */
    public Decision poll(long nowNs, Decision out) {
        long diff = target() - nowNs;
        out.skipped = 0;
        out.catchingUp = false;
        if (diff > 0) {
            out.waitNs = diff;
            return out;
        }
        out.waitNs = 0;
        long lagSlots = (-diff) / SLOT_NS;
        if (lagSlots > MAX_CATCHUP) {
            // 너무 늦음: 밀린 슬롯을 건너뛰고 기한을 현재로
            out.skipped = (int) lagSlots;
            missedTotal += lagSlots;
            resyncTotal++;
            t0 = nowNs;
            slot = 0;
        } else if (lagSlots >= 1) {
            out.catchingUp = true;
            catchupTotal++;
        }
        return out;
    }

    /** 이번 슬롯을 소비(다음 슬롯으로). */
    public void consume() { slot++; }

    public long getAndResetMissed() { long v = missedTotal; missedTotal = 0; return v; }

    public long getAndResetResyncs() { long v = resyncTotal; resyncTotal = 0; return v; }

    public long getAndResetCatchups() { long v = catchupTotal; catchupTotal = 0; return v; }
}
