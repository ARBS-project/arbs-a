package com.limelight.arbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ARBS A1-3b: 지터 버퍼(프리버퍼·언더런 재충전·적응·초과 폐기·감쇠) 단위 테스트. 가상 시간. */
public class ApexJitterBufferTest {
    private static final long MS = 1_000_000L;

    private static ApexJitterBuffer.Result d(ApexJitterBuffer b, int q, long nowMs) {
        return b.decide(q, nowMs * MS, new ApexJitterBuffer.Result());
    }

    @Test
    public void waitsUntilTargetFilledThenPlays() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        assertFalse(d(b, 5, 0).play);
        assertFalse(d(b, 15, 8).play);
        assertFalse(b.isPlaying());
        assertTrue(d(b, 16, 16).play);
        assertTrue(b.isPlaying());
    }

    @Test
    public void underrunStopsPlaybackRaisesTargetAndRefills() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        assertTrue(d(b, 20, 0).play);
        ApexJitterBuffer.Result r = d(b, 7, 8);        // < 8 샘플
        assertFalse(r.play);
        assertTrue(r.underrun);
        assertEquals(ApexJitterBuffer.MIN_TARGET_MS + ApexJitterBuffer.STEP_UP_MS, b.getTarget());
        assertFalse("must refill to the new target (24)", d(b, 20, 16).play);
        assertTrue(d(b, 24, 24).play);
        assertEquals(1, b.getAndResetUnderruns());
        assertEquals(0, b.getAndResetUnderruns());
    }

    @Test
    public void targetIsCappedAtMax() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        long t = 0;
        for (int i = 0; i < 30; i++) {
            d(b, 200 > 0 ? b.getTarget() : 0, t);       // 채워서 재생 시작
            d(b, 0, t + 1);                              // 언더런
            t += 2;
        }
        assertEquals(ApexJitterBuffer.MAX_TARGET_MS, b.getTarget());
    }

    @Test
    public void overflowTrimsOldestToTargetPlusMargin() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        int target = b.getTarget();
        ApexJitterBuffer.Result r = d(b, target + ApexJitterBuffer.OVERFLOW_MS, 0);
        assertEquals("exactly at the limit is not trimmed", -1, r.trimTo);
        r = d(b, target + ApexJitterBuffer.OVERFLOW_MS + 1, 8);
        assertEquals(target + ApexJitterBuffer.KEEP_MARGIN_MS, r.trimTo);
        assertTrue(r.play);
        assertEquals(1, b.getAndResetTrims());
    }

    @Test
    public void targetDecaysOneMsPerQuietSecondDownToMin() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        d(b, 20, 0);
        d(b, 0, 8);                                       // 언더런 -> 24
        assertEquals(24, b.getTarget());
        d(b, 30, 500);                                    // 언더런 후 1초 안 지남
        assertEquals(24, b.getTarget());
        d(b, 30, 1100);                                   // lastDecay=0 부터 1초 경과, 언더런은 8ms -> 1초+ 지남
        assertEquals(23, b.getTarget());
        for (long t = 2200; t < 20000; t += 1000) d(b, 30, t);
        assertEquals(ApexJitterBuffer.MIN_TARGET_MS, b.getTarget());
    }

    @Test
    public void resetRestartsPrebufferButKeepsTarget() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        d(b, 20, 0);
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

    /** 몰려 오는 도착(40 ms 정지 후 버스트)을 흡수해 재생이 끊기지 않는 시나리오. */
    @Test
    public void burstyArrivalsPlaySmoothlyAfterAdapting() {
        ApexJitterBuffer b = new ApexJitterBuffer();
        b.reset(0);
        int queue = 0;
        int plays = 0, underruns = 0;
        for (int slot = 0; slot < 500; slot++) {         // 8 ms 슬롯 4 초
            long now = slot * 8L;
            // 도착: 64 ms 마다 64 샘플이 한꺼번에(평균 1000 샘플/초 = 슬롯당 8)
            if (slot % 8 == 0) queue += 64;
            ApexJitterBuffer.Result r = d(b, queue, now);
            if (r.trimTo >= 0) queue = r.trimTo;
            if (r.play) {
                queue -= 8;
                if (slot > 250) plays++;
            }
            if (r.underrun && slot > 250) underruns++;
        }
        // 적응 뒤(2초 이후)에는 거의 끊기지 않는다(느린 감쇠로 1~2회 재조정 허용).
        assertTrue("plays=" + plays, plays >= 220);
        assertTrue("underruns=" + underruns, underruns <= 2);
    }
}
