package com.limelight.arbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ARBS A1-3d: 지터 버퍼(프리버퍼·언더런 재충전·p95+4 적응·초과 정리·고정 지연) 단위 테스트. 가상 시간. */
public class ApexJitterBufferTest {
    private static final long MS = 1_000_000L;

    private static ApexJitterBuffer.Result d(ApexJitterBuffer b, int q, long nowMs) {
        return b.decide(q, nowMs * MS, new ApexJitterBuffer.Result());
    }

    @Test
    public void waitsUntilTargetFilledThenPlays() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        int t = b.getTarget();
        assertFalse(d(b, 5, 0).play);
        assertFalse(d(b, t - 1, 8).play);
        assertFalse(b.isPlaying());
        assertTrue(d(b, t, 16).play);
        assertTrue(b.isPlaying());
    }

    @Test
    public void underrunStopsPlaybackRaisesTargetAndRefills() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        int t0 = b.getTarget();
        assertTrue(d(b, t0, 0).play);
        ApexJitterBuffer.Result r = d(b, 7, 8);        // < 8 샘플
        assertFalse(r.play);
        assertTrue(r.underrun);
        assertEquals(t0 + ApexJitterBuffer.STEP_UP_MS, b.getTarget());
        assertFalse("must refill to the new target", d(b, t0, 16).play);
        assertTrue(d(b, t0 + ApexJitterBuffer.STEP_UP_MS, 24).play);
        assertEquals(1, b.getAndResetUnderruns());
        assertEquals(0, b.getAndResetUnderruns());
    }

    @Test
    public void targetIsCappedAtMax() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        long t = 0;
        for (int i = 0; i < 60; i++) {
            d(b, b.getTarget(), t);       // 채워서 재생 시작
            d(b, 0, t + 1);               // 언더런
            t += 2;
        }
        assertEquals(ApexJitterBuffer.MAX_TARGET_MS, b.getTarget());
    }

    @Test
    public void overflowTrimsOldestAboveTargetPlus8() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        int target = b.getTarget();
        ApexJitterBuffer.Result r = d(b, target + ApexJitterBuffer.OVERFLOW_MS, 0);
        assertEquals("exactly at target+8 is not trimmed", -1, r.trimTo);
        r = d(b, target + ApexJitterBuffer.OVERFLOW_MS + 1, 8);
        assertEquals(target + ApexJitterBuffer.KEEP_MARGIN_MS, r.trimTo);
        assertTrue(r.play);
        assertEquals(1, b.getAndResetTrims());
        assertEquals(8, ApexJitterBuffer.OVERFLOW_MS);
    }

    @Test
    public void autoTargetRisesImmediatelyAndLowersSlowlyWithinBounds() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        b.adaptFromP95(60.0, 200, 10_000 * MS);
        assertEquals(64, b.getTarget());                   // 올릴 때는 즉시
        b.adaptFromP95(500.0, 200, 11_000 * MS);
        assertEquals("capped", ApexJitterBuffer.MAX_TARGET_MS, b.getTarget());
        b.adaptFromP95(5.0, 200, 20_000 * MS);
        assertEquals(78, b.getTarget());                   // 초당 2 ms
        for (int i = 0; i < 100; i++) b.adaptFromP95(5.0, 200, (21_000L + i * 1000) * MS);
        assertEquals("floor", ApexJitterBuffer.MIN_TARGET_MS, b.getTarget());
        assertEquals(12, ApexJitterBuffer.MIN_TARGET_MS);
    }

    @Test
    public void p95FormulaUsesCeilPlus4() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        b.adaptFromP95(30.0, 200, 10_000 * MS);            // 34
        assertEquals(34, b.getTarget());
        b.adaptFromP95(34.2, 200, 11_000 * MS);            // ceil(34.2)+4 = 39
        assertEquals(39, b.getTarget());
    }

    @Test
    public void tooFewPacketsDoesNotAdaptAndRecentUnderrunBlocksLowering() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        int t = b.getTarget();
        b.adaptFromP95(80.0, 5, 1_000 * MS);               // 패킷 5개: 무시
        assertEquals(t, b.getTarget());
        d(b, t, 1_000);                                    // 재생 시작
        d(b, 0, 1_008);                                    // 언더런 @1.008s
        int after = b.getTarget();
        b.adaptFromP95(5.0, 200, 2_000 * MS);              // 2초 안 지남: 내리지 않음
        assertEquals(after, b.getTarget());
        b.adaptFromP95(5.0, 200, 3_100 * MS);
        assertEquals(after - ApexJitterBuffer.DOWN_STEP_MS, b.getTarget());
    }

    @Test
    public void resetRestartsPrebufferButKeepsTarget() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        d(b, b.getTarget(), 0);
        d(b, 0, 8);
        int t = b.getTarget();
        b.reset(100 * MS);
        assertFalse(b.isPlaying());
        assertEquals(t, b.getTarget());
    }

    @Test
    public void queueCapacityCoversWorstCase() {
        assertTrue(ApexJitterBuffer.REQUIRED_QUEUE <= ApexPcmPipeline.CAPACITY);
    }

    /** 40 ms 마다 40 샘플이 한꺼번에 오는 도착을 p95 기반 목표가 흡수해 거의 끊기지 않는 시나리오. */
    @Test
    public void burstyArrivalsPlaySmoothlyWithAdaptedTarget() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        int queue = 0;
        int plays = 0, underruns = 0;
        for (int slot = 0; slot < 500; slot++) {           // 8 ms 슬롯 4 초
            long now = slot * 8L;
            if (slot % 5 == 0) queue += 40;                // 평균 1000 샘플/초 = 슬롯당 8
            if (slot % 125 == 124) b.adaptFromP95(40.0, 200, now * MS);   // 1초마다 p95=40 ms 반영 -> 목표 44
            ApexJitterBuffer.Result r = d(b, queue, now);
            if (r.trimTo >= 0) queue = r.trimTo;
            if (r.play) {
                queue -= 8;
                if (slot > 250) plays++;
            }
            if (r.underrun && slot > 250) underruns++;
        }
        assertTrue("plays=" + plays, plays >= 235);
        assertEquals(0, underruns);
    }

    @Test
    public void fixedDelayNeverAdaptsButStillRefills() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.setFixed(48);
        assertEquals(48, b.getTarget());
        b.reset(0);
        assertFalse(d(b, 47, 0).play);
        assertTrue(d(b, 48, 8).play);
        ApexJitterBuffer.Result r = d(b, 0, 16);            // 언더런
        assertTrue(r.underrun);
        assertEquals("fixed: no step up", 48, b.getTarget());
        b.adaptFromP95(5.0, 200, 30_000 * MS);
        b.adaptFromP95(90.0, 200, 31_000 * MS);
        assertEquals("fixed: no p95 adaptation", 48, b.getTarget());
        b.setFixed(5);                                      // 최소 12 로
        assertEquals(12, b.getTarget());
        b.setFixed(500);
        assertEquals(80, b.getTarget());
        b.setFixed(0);
        assertEquals(0, b.getFixed());
    }
}
