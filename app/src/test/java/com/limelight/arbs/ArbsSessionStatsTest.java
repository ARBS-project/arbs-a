package com.limelight.arbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ArbsSessionStatsTest {
    private static final long S = 1_000_000_000L;

    @Test
    public void minuteLineShowsOnlyTheLastMinuteAndSessionLineShowsTotals() {
        ArbsSessionStats st = new ArbsSessionStats();
        st.reset(0);
        st.batteryStart(80);
        st.add(ArbsSessionStats.PCM_PKTS, 1000);
        st.add(ArbsSessionStats.GRIP_FRAMES, 7500);
        st.add(ArbsSessionStats.TRIG_FRAMES, 300);
        st.add(ArbsSessionStats.RUMBLE_TIMEOUTS, 1);
        st.addExtraDelay(10);
        st.addExtraDelay(12);
        st.addRtt(20);
        st.silenceMs(150);
        assertFalse(st.minuteDue(59 * S));
        assertTrue(st.minuteDue(60 * S));
        String m1 = st.minuteLine(60 * S, "연결됨", "READY/출력중");
        assertTrue(m1, m1.startsWith("ARBS 1분 요약: 경과 00:01:00"));
        assertTrue(m1, m1.contains("PCM 패킷 1000"));
        assertTrue(m1, m1.contains("그립 7500 트리거 300"));
        assertTrue(m1, m1.contains("평균 11.0ms"));
        assertTrue(m1, m1.contains("RTT 평균 20ms"));
        st.add(ArbsSessionStats.PCM_PKTS, 500);
        String m2 = st.minuteLine(120 * S, "연결됨", "READY/출력중");
        assertTrue(m2, m2.contains("PCM 패킷 500"));
        assertTrue(m2, m2.contains("추가지연 평균 n/a"));
        st.batteryEnd(78);
        String se = st.sessionLine(125 * S, "세션 종료(stopConnection)");
        assertTrue(se, se.startsWith("ARBS 세션 요약: 총 시간 00:02:05"));
        assertTrue(se, se.contains("PCM 패킷 1500"));
        assertTrue(se, se.contains("최대무수신 150ms"));
        assertTrue(se, se.contains("시작 80 종료 78"));
        assertTrue(se, se.endsWith("크래시 0"));
        st.markCrash();
        assertTrue(st.sessionLine(126 * S, "크래시").endsWith("크래시 1"));
    }

    @Test
    public void retriesAndReconnectsSurviveSessionReset() {
        ArbsSessionStats st = new ArbsSessionStats();
        st.add(ArbsSessionStats.RETRIES, 2);
        st.add(ArbsSessionStats.RECONNECTS, 1);
        st.add(ArbsSessionStats.PCM_PKTS, 99);
        st.reset(S);
        assertEquals(2, st.get(ArbsSessionStats.RETRIES));
        assertEquals(1, st.get(ArbsSessionStats.RECONNECTS));
        assertEquals(0, st.get(ArbsSessionStats.PCM_PKTS));
        assertTrue(st.sessionLine(2 * S, "x").contains("재시도 2 재연결 1"));
    }

    @Test
    public void unknownBatteryIsShownAsNa() {
        ArbsSessionStats st = new ArbsSessionStats();
        st.reset(0);
        assertTrue(st.sessionLine(S, "x").contains("시작 n/a 종료 n/a"));
    }

    @Test
    public void perSecondLinesAreSuppressedAbove20MbUnlessVerbose() {
        assertTrue(ArbsSessionStats.perSecondAllowed(0, false));
        assertTrue(ArbsSessionStats.perSecondAllowed(20L * 1024 * 1024, false));
        assertFalse(ArbsSessionStats.perSecondAllowed(20L * 1024 * 1024 + 1, false));
        assertTrue(ArbsSessionStats.perSecondAllowed(30L * 1024 * 1024, true));
    }
}
