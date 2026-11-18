package com.limelight.arbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ARBS A1-3: 8 ms 절대 기한 스케줄(대기·따라잡기·재동기) 단위 테스트. 가상 시간. */
public class ApexSchedulerTest {
    private static final long MS = 1_000_000L;

    @Test
    public void onTimeWaitsUntilSlotThenIsDue() {
        ApexScheduler s = new ApexScheduler();
        ApexScheduler.Decision d = new ApexScheduler.Decision();
        s.start(0);
        s.poll(0, d);
        assertEquals(0, d.waitNs);               // 슬롯 0 은 즉시
        s.consume();
        s.poll(3 * MS, d);
        assertEquals(5 * MS, d.waitNs);          // 슬롯 1 기한 8 ms
        s.poll(8 * MS, d);
        assertEquals(0, d.waitNs);
        assertFalse(d.catchingUp);
    }

    /** 송신이 가끔 12 ms 걸려도 평균은 125/초(1000 ms 에 125 슬롯)를 유지한다. */
    @Test
    public void averageRateIsMaintainedDespiteSlowTransfers() {
        ApexScheduler s = new ApexScheduler();
        ApexScheduler.Decision d = new ApexScheduler.Decision();
        long now = 0;
        s.start(now);
        int sent = 0;
        int i = 0;
        while (now < 1000 * MS) {
            s.poll(now, d);
            if (d.waitNs > 0) {
                now += d.waitNs;
                continue;
            }
            s.consume();
            sent++;
            i++;
            now += (i % 5 == 0 ? 12 : 1) * MS;   // 5번에 한 번 12 ms 걸리는 송신
        }
        assertTrue("sent=" + sent, sent >= 123 && sent <= 126);
        assertEquals(0, s.getAndResetMissed());   // 지연이 24 ms 이내라 건너뜀 없음
    }

    @Test
    public void lateByOneToThreeSlotsCatchesUpWithoutSkipping() {
        ApexScheduler s = new ApexScheduler();
        ApexScheduler.Decision d = new ApexScheduler.Decision();
        s.start(0);
        s.consume();                              // 슬롯 1 기한 8 ms
        s.poll(8 * MS + 20 * MS, d);              // 20 ms 늦음 = 2 슬롯
        assertEquals(0, d.waitNs);
        assertTrue(d.catchingUp);
        assertEquals(0, d.skipped);
        s.consume();
        s.poll(28 * MS, d);                       // 슬롯 2 기한 16 ms -> 12 ms 늦음(1 슬롯)
        assertTrue(d.catchingUp);
        s.consume();
        s.poll(28 * MS, d);                       // 슬롯 3 기한 24 ms -> 4 ms 늦음(0 슬롯)
        assertEquals(0, d.waitNs);
        assertFalse(d.catchingUp);
        assertEquals(0, s.getAndResetResyncs());
    }

    @Test
    public void lateByMoreThanThreeSlotsSkipsAndResyncs() {
        ApexScheduler s = new ApexScheduler();
        ApexScheduler.Decision d = new ApexScheduler.Decision();
        s.start(0);
        s.poll(8 * MS * 1 + 50 * MS, d);          // 슬롯 0 기한 0 -> 58 ms 늦음 = 7 슬롯
        assertEquals(0, d.waitNs);
        assertEquals(7, d.skipped);
        assertEquals(7, s.getAndResetMissed());
        assertEquals(1, s.getAndResetResyncs());
        assertEquals(58 * MS, s.target());        // 기한이 현재로 재동기
        assertEquals(0, s.getAndResetMissed());   // 초기화됨
    }

    @Test
    public void exactlyThreeSlotsLateStillCatchesUp() {
        ApexScheduler s = new ApexScheduler();
        ApexScheduler.Decision d = new ApexScheduler.Decision();
        s.start(0);
        s.poll(24 * MS, d);                       // 정확히 3 슬롯 늦음
        assertEquals(0, d.skipped);
        assertTrue(d.catchingUp);
        s.poll(32 * MS + 1, d);                   // 4 슬롯 + 1 ns
        assertEquals(4, d.skipped);
    }
}
