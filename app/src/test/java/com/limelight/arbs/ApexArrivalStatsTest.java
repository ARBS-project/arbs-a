package com.limelight.arbs;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** ARBS A1-3c: PCM 도착 간격 통계(평균/p95/최대/최대 무수신) 단위 테스트. */
public class ApexArrivalStatsTest {
    private static final long MS = 1_000_000L;

    @Test
    public void steadyFiveMsArrivals() {
        ApexArrivalStats s = new ApexArrivalStats();
        long t = 1000 * MS;
        for (int i = 0; i < 200; i++) {
            s.add(t);
            t += 5 * MS;
        }
        ApexArrivalStats.Snapshot r = s.snapshotAndReset(t - 5 * MS, new ApexArrivalStats.Snapshot());
        assertEquals(200, r.packets);
        assertEquals(5.0, r.avgMs, 1e-6);
        assertEquals(5.0, r.p95Ms, 1e-6);
        assertEquals(5.0, r.maxMs, 1e-6);
        assertEquals(5.0, r.maxSilenceMs, 1e-6);
    }

    @Test
    public void burstAndStallShowInP95AndMax() {
        ApexArrivalStats s = new ApexArrivalStats();
        long t = 1000 * MS;
        for (int i = 0; i < 100; i++) {          // 간격: 2 ms 98 개 + 120 ms 1 개
            s.add(t);
            t += (i == 50 ? 120 : 2) * MS;
        }
        ApexArrivalStats.Snapshot r = s.snapshotAndReset(t, new ApexArrivalStats.Snapshot());
        assertEquals(120.0, r.maxMs, 1e-6);
        assertEquals(2.0, r.p95Ms, 1e-6);        // 1% 가 120 이므로 p95 는 2
        assertEquals(120.0, r.maxSilenceMs, 1e-6);
        assertEquals(true, r.avgMs > 2.0 && r.avgMs < 4.0);
    }

    @Test
    public void tailSinceLastPacketCountsAsSilenceAndWindowResets() {
        ApexArrivalStats s = new ApexArrivalStats();
        s.add(1000 * MS);
        s.add(1005 * MS);
        ApexArrivalStats.Snapshot r = s.snapshotAndReset(1505 * MS, new ApexArrivalStats.Snapshot());
        assertEquals(5.0, r.maxMs, 1e-6);
        assertEquals(500.0, r.maxSilenceMs, 1e-6);       // 마지막 도착 후 500 ms 무수신
        r = s.snapshotAndReset(2005 * MS, r);            // 새 창: 패킷 없음
        assertEquals(0, r.packets);
        assertEquals(1000.0, r.maxSilenceMs, 1e-6);
        s.reset();
        r = s.snapshotAndReset(2000 * MS, r);
        assertEquals(0.0, r.maxSilenceMs, 1e-6);
    }
}
