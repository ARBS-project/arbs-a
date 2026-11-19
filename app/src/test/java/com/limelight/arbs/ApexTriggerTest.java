package com.limelight.arbs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** ARBS A1-4: 트리거 프레임 바이트(ASB 근거), 효과 디코드·렌더, Mix 중재/타임아웃/해제 단위 테스트. */
public class ApexTriggerTest {

    private static byte[] payload(int... v) {
        byte[] p = new byte[10];
        for (int i = 0; i < v.length; i++) p[i] = (byte) v[i];
        return p;
    }

    private static int peak(int[] a) {
        int m = 0;
        for (int v : a) m = Math.max(m, Math.abs(v));
        return m;
    }

    private static final long MS = 1_000_000L;

    // ---------------------------------------------------------------- 프레임 바이트

    @Test
    public void triggerEnableBytesMatchAsb() {
        byte[] f = ApexProtocol.triggerEnableBoth();
        assertEquals(0x53, f[2] & 0xFF);
        assertEquals(7, f[3] & 0xFF);                       // payload 5 + 2
        assertArrayEquals(new int[]{0x01, 0x02, 0x03, 0x00, 0x40}, new int[]{f[4] & 0xFF, f[5] & 0xFF, f[6] & 0xFF, f[7] & 0xFF, f[8] & 0xFF});
        assertEquals(ApexProtocol.checksum(f), f[31] & 0xFF);
        byte[][] left = ApexProtocol.triggerSideEnable(ApexProtocol.ROUTE_LEFT);
        assertArrayEquals(new int[]{0x01, 0x00, 0x03, 0x00, 0x40}, new int[]{left[0][4] & 0xFF, left[0][5] & 0xFF, left[0][6] & 0xFF, left[0][7] & 0xFF, left[0][8] & 0xFF});
        assertArrayEquals(new int[]{0x01, 0x01, 0x00}, new int[]{left[1][4] & 0xFF, left[1][5] & 0xFF, left[1][6] & 0xFF});
        byte[][] right = ApexProtocol.triggerSideEnable(ApexProtocol.ROUTE_RIGHT);
        assertEquals(0x01, right[0][5] & 0xFF);
        assertEquals(0x00, right[1][5] & 0xFF);
        try {
            ApexProtocol.triggerSideEnable(ApexProtocol.ROUTE_BOTH);
            fail();
        } catch (IllegalArgumentException expected) { }
        assertTrue(ApexProtocol.isSendable(f));
        assertTrue(ApexProtocol.isSendable(left[0]) && ApexProtocol.isSendable(left[1]));
    }

    @Test
    public void realtimeFrameWithTriggerSetsFlagRouteAndChannel() {
        int[] l = new int[8], r = new int[8], t = {1, -2, 3, -4, 5, -6, 127, -127};
        byte[] f = ApexProtocol.realtimeFrame(l, r, t, true, ApexProtocol.ROUTE_BOTH);
        assertEquals(0x57, f[2] & 0xFF);
        assertEquals(0x80 | 0x10 | 0x08 | 0x04 | 2, f[4] & 0xFF);
        assertEquals(128 + 1, f[5] & 0xFF);                 // 서브프레임0: 트리거, 좌, 우
        assertEquals(128, f[6] & 0xFF);
        assertEquals(128 + 127, f[5 + 18] & 0xFF);          // 서브프레임6 트리거
        assertEquals(128 - 127, f[5 + 21] & 0xFF);
        f = ApexProtocol.realtimeFrame(l, r, t, true, ApexProtocol.ROUTE_LEFT);
        assertEquals(0x80 | 0x10 | 0x08 | 0x04 | 0, f[4] & 0xFF);
        f = ApexProtocol.realtimeFrame(l, r, t, true, ApexProtocol.ROUTE_RIGHT);
        assertEquals(0x80 | 0x10 | 0x08 | 0x04 | 1, f[4] & 0xFF);
        // 트리거 미사용 = 이전 그립 전용 0x9B 와 바이트 단위로 동일
        assertArrayEquals(ApexProtocol.gripFrame(l, r), ApexProtocol.realtimeFrame(l, r, null, false, ApexProtocol.ROUTE_MUTE));
        assertEquals(0x9B, ApexProtocol.neutralFrame()[4] & 0xFF);
    }

    @Test
    public void realtimeFrameRejectsBadArguments() {
        int[] z = new int[8], big = {0, 0, 0, 0, 0, 0, 0, 128};
        try { ApexProtocol.realtimeFrame(z, z, big, true, ApexProtocol.ROUTE_LEFT); fail(); } catch (IllegalArgumentException expected) { }
        try { ApexProtocol.realtimeFrame(z, z, null, true, ApexProtocol.ROUTE_LEFT); fail(); } catch (IllegalArgumentException expected) { }
        try { ApexProtocol.realtimeFrame(z, z, z, false, ApexProtocol.ROUTE_LEFT); fail(); } catch (IllegalArgumentException expected) { }
        try { ApexProtocol.realtimeFrame(z, z, z, true, 4); fail(); } catch (IllegalArgumentException expected) { }
    }

    // ---------------------------------------------------------------- 디코드

    @Test
    public void decodeOffWeaponBowFeedbackVibrationAndUnsupported() {
        assertEquals(ApexTriggerEffect.Type.OFF, ApexTriggerEffect.decode(0x00, payload()).effect.type);
        assertEquals(ApexTriggerEffect.Type.OFF, ApexTriggerEffect.decode(0x05, payload()).effect.type);
        // Weapon(0x25): 존 2 와 6, 강도 바이트 7 -> strength 8
        ApexTriggerEffect.Decoded w = ApexTriggerEffect.decode(0x25, payload(0x44, 0x00, 7));
        assertEquals(ApexTriggerEffect.OK, w.code);
        assertEquals(ApexTriggerEffect.Type.WEAPON, w.effect.type);
        assertEquals(2, w.effect.startZone);
        assertEquals(6, w.effect.endZone);
        assertEquals(8, w.effect.strength);
        // 존이 하나뿐이면 형식 오류
        assertEquals(ApexTriggerEffect.INVALID, ApexTriggerEffect.decode(0x25, payload(0x04, 0x00, 7)).code);
        // 마스크가 10비트를 넘으면 형식 오류
        assertEquals(ApexTriggerEffect.INVALID, ApexTriggerEffect.decode(0x21, payload(0x00, 0x04, 1)).code);
        // Bow(0x22): 존 1,7, 강도 (3-1)|((5-1)<<3) = 2|32 = 34
        ApexTriggerEffect.Decoded b = ApexTriggerEffect.decode(0x22, payload(0x82, 0x00, 34));
        assertEquals(ApexTriggerEffect.Type.BOW, b.effect.type);
        assertEquals(3, b.effect.strength);
        assertEquals(5, b.effect.snapStrength);
        // Feedback(0x21): 존 1..9, 모든 존 강도 코드 3 -> 4
        long packed = 0;
        for (int z = 1; z < 10; z++) packed |= 3L << (z * 3);
        ApexTriggerEffect.Decoded f = ApexTriggerEffect.decode(0x21, payload(0xFE, 0x03, (int) (packed & 0xFF), (int) ((packed >> 8) & 0xFF), (int) ((packed >> 16) & 0xFF), (int) ((packed >> 24) & 0xFF)));
        assertEquals(ApexTriggerEffect.Type.FEEDBACK, f.effect.type);
        assertEquals(4, f.effect.zoneStrengths[5]);
        assertEquals(0, f.effect.zoneStrengths[0]);
        // Vibration(0x26): 주파수 130 -> 연속 진동, 주파수 20 -> 펄스열(Rhythm), 주파수 0 -> Off
        ApexTriggerEffect.Decoded v = ApexTriggerEffect.decode(0x26, payload(0xFE, 0x03, 0x49, 0x92, 0x24, 0x09, 0, 0, 130));
        assertEquals(ApexTriggerEffect.Type.VIBRATION, v.effect.type);
        assertEquals(130, v.effect.frequency);
        ApexTriggerEffect.Decoded p = ApexTriggerEffect.decode(0x26, payload(0xFE, 0x03, 0, 0, 0, 0, 0, 0, 20));
        assertEquals(ApexTriggerEffect.Type.RHYTHM, p.effect.type);
        assertEquals(50, p.effect.rhythmPeriod);
        assertEquals(ApexTriggerEffect.Type.OFF, ApexTriggerEffect.decode(0x26, payload(0xFE, 0x03, 0, 0, 0, 0, 0, 0, 0)).effect.type);
        // 주기 효과(0x23/0x27)·레거시는 미지원(ASB 변환을 이식하지 않음 -> 만들지 않음)
        assertEquals(ApexTriggerEffect.UNSUPPORTED, ApexTriggerEffect.decode(0x23, payload(1, 2, 3)).code);
        assertEquals(ApexTriggerEffect.UNSUPPORTED, ApexTriggerEffect.decode(0x01, payload(1, 2, 3)).code);
        assertEquals(ApexTriggerEffect.UNSUPPORTED, ApexTriggerEffect.decode(0x02, payload(1, 2, 3)).code);
    }

    // ---------------------------------------------------------------- 렌더

    @Test
    public void weaponBurstFiresOncePerPullAndRearms() {
        ApexTriggerEffect e = ApexTriggerEffect.decode(0x25, payload(0x44, 0x00, 7)).effect;   // start 존2(=52), end 존6(=154)
        ApexTriggerEffect.State s = new ApexTriggerEffect.State();
        int[] out = new int[8];
        int burstPeak = 0;
        int[] positions = {0, 20, 60, 100, 140, 160, 200, 255, 255, 255};
        for (int pos : positions) {
            ApexTriggerEffect.render(e, pos, s, -1, out);
            burstPeak = Math.max(burstPeak, peak(out));
            assertTrue(peak(out) <= 127);
        }
        assertEquals(1, s.burstsStarted);
        assertTrue("burst peak=" + burstPeak, burstPeak >= 70);
        // 놓으면(<= start+4) 재무장, 다시 당기면 두 번째 burst
        for (int pos : new int[]{200, 100, 40, 0, 60, 160, 200}) ApexTriggerEffect.render(e, pos, s, -1, out);
        assertEquals(2, s.burstsStarted);
    }

    @Test
    public void burstLengthIsAttackHoldReleaseThen0() {
        ApexTriggerEffect e = ApexTriggerEffect.decode(0x25, payload(0x44, 0x00, 7)).effect;
        ApexTriggerEffect.State s = new ApexTriggerEffect.State();
        int[] out = new int[8];
        ApexTriggerEffect.render(e, 0, s, -1, out);
        ApexTriggerEffect.render(e, 200, s, -1, out);          // 교차: burst 시작(51 샘플)
        int nonZeroFrames = 0;
        for (int i = 0; i < 12; i++) {
            if (peak(out) > 0) nonZeroFrames++;
            ApexTriggerEffect.render(e, 200, s, -1, out);
        }
        assertTrue("frames with output=" + nonZeroFrames, nonZeroFrames >= 6 && nonZeroFrames <= 8);   // 51 샘플 ~ 7 프레임
        for (int i = 0; i < 4; i++) ApexTriggerEffect.render(e, 200, s, -1, out);
        assertEquals(0, peak(out));
    }

    @Test
    public void feedbackAmplitudeFollowsStrengthAndPosition() {
        long packed = 0;
        for (int z = 0; z < 10; z++) packed |= 3L << (z * 3);          // 모든 존 강도 4
        ApexTriggerEffect e = ApexTriggerEffect.decode(0x21, payload(0xFF, 0x03, (int) (packed & 0xFF), (int) ((packed >> 8) & 0xFF), (int) ((packed >> 16) & 0xFF), (int) ((packed >> 24) & 0xFF))).effect;
        ApexTriggerEffect.State s = new ApexTriggerEffect.State();
        int[] out = new int[8];
        int pk = 0;
        for (int i = 0; i < 40; i++) {
            ApexTriggerEffect.render(e, 255, s, i * 8L, out);
            if (i > 5) pk = Math.max(pk, peak(out));
        }
        assertTrue("feedback peak=" + pk, pk >= 28 && pk <= 33);      // 8 * 4 = 32
        for (int i = 0; i < 6; i++) ApexTriggerEffect.render(e, 0, s, (40 + i) * 8L, out);
        assertEquals("released below position 8 -> silent", 0, peak(out));
    }

    @Test
    public void vibrationNeverExceedsNativeDriveAndPulseTrainRuns() {
        ApexTriggerEffect e = ApexTriggerEffect.decode(0x26, payload(0xFE, 0x03, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 130)).effect;
        ApexTriggerEffect.State s = new ApexTriggerEffect.State();
        int[] out = new int[8];
        int pk = 0;
        for (int i = 0; i < 60; i++) {
            ApexTriggerEffect.render(e, 255, s, i * 8L, out);
            pk = Math.max(pk, peak(out));
        }
        assertTrue("vibration peak=" + pk, pk <= ApexTriggerEffect.NATIVE_MAX_DRIVE && pk > 40);
        ApexTriggerEffect train = ApexTriggerEffect.decode(0x26, payload(0xFE, 0x03, 0x49, 0x92, 0x24, 0x09, 0, 0, 10)).effect;
        ApexTriggerEffect.State ts = new ApexTriggerEffect.State();
        int tp = 0;
        for (int i = 0; i < 40; i++) {
            ApexTriggerEffect.render(train, 255, ts, -1, out);
            tp = Math.max(tp, peak(out));
        }
        assertTrue("train peak=" + tp, tp > 20 && tp <= 127);
    }

    @Test
    public void strengthAndCeilingFollowAsbFormulas() {
        assertEquals(50, ApexTriggerEffect.scaleStrength(100, 50));
        assertEquals(-50, ApexTriggerEffect.scaleStrength(-100, 50));
        assertEquals(0, ApexTriggerEffect.scaleStrength(1, 50));        // C++ 정수 나눗셈(0 방향)
        assertEquals(127, ApexTriggerEffect.scaleStrength(127, 100));
        assertEquals(127, ApexTriggerEffect.scaleStrength(127, 500));    // percent 는 100 으로 제한
        assertEquals(100, ApexTriggerEffect.limitCeiling(100, 100));     // 천장 100% 는 무변경
        int limited = ApexTriggerEffect.limitCeiling(127, 50);           // 천장 63.5, 무릎 31.75 -> 55.56+0.5 -> 56
        assertEquals(56, limited);
        assertEquals(-56, ApexTriggerEffect.limitCeiling(-127, 50));
        assertEquals(20, ApexTriggerEffect.limitCeiling(20, 50));        // 무릎 아래는 그대로
    }

    // ---------------------------------------------------------------- 브리지(수신/해제/타임아웃/중재)

    @Test
    public void weaponThroughBridgeRoutesLeftAndStrengthScales() {
        ApexTriggerBridge b = new ApexTriggerBridge();
        String log = b.onEffect(true, 0x25, payload(0x44, 0x00, 7), 0);
        assertNotNull(log);
        assertTrue(log, log.contains("Weapon"));
        ApexTriggerBridge.Out o = new ApexTriggerBridge.Out();
        long t = 0;
        int pk = 0;
        boolean sawLeft = false;
        for (int pos : new int[]{0, 100, 200, 255, 255, 255, 255, 255, 255, 255}) {
            b.setPosition(pos, 0);
            t += 8 * MS;
            b.render(t, o);
            if (o.enabled) {
                assertEquals(ApexProtocol.ROUTE_LEFT, o.route);
                sawLeft = true;
                pk = Math.max(pk, peak(o.samples));
            }
        }
        assertTrue(sawLeft);
        assertTrue("peak=" + pk, pk >= 70 && pk <= 127);
        // 세기 50% -> 절반
        ApexTriggerBridge half = new ApexTriggerBridge();
        half.setStrengthPercent(50);
        half.onEffect(true, 0x25, payload(0x44, 0x00, 7), 0);
        t = 0;
        int pk2 = 0;
        for (int pos : new int[]{0, 100, 200, 255, 255, 255, 255, 255}) {
            half.setPosition(pos, 0);
            t += 8 * MS;
            half.render(t, o);
            if (o.enabled) pk2 = Math.max(pk2, peak(o.samples));
        }
        assertTrue("half peak=" + pk2 + " full=" + pk, pk2 > 0 && pk2 <= pk / 2 + 1);
    }

    @Test
    public void rightOnlyEffectRoutesRightAndBothIdenticalRoutesBoth() {
        ApexTriggerBridge b = new ApexTriggerBridge();
        b.onEffect(false, 0x25, payload(0x44, 0x00, 7), 0);
        ApexTriggerBridge.Out o = new ApexTriggerBridge.Out();
        long t = 0;
        boolean right = false;
        for (int pos : new int[]{0, 100, 200, 255, 255, 255, 255}) {
            b.setPosition(0, pos);
            t += 8 * MS;
            b.render(t, o);
            if (o.enabled) {
                assertEquals(ApexProtocol.ROUTE_RIGHT, o.route);
                right = true;
            }
        }
        assertTrue(right);
        // 같은 효과를 양쪽에 같은 위치로 -> 동일 샘플 -> Both
        ApexTriggerBridge both = new ApexTriggerBridge();
        both.onEffect(true, 0x25, payload(0x44, 0x00, 7), 0);
        both.onEffect(false, 0x25, payload(0x44, 0x00, 7), 0);
        boolean sawBoth = false;
        t = 0;
        for (int pos : new int[]{0, 100, 200, 255, 255, 255, 255}) {
            both.setPosition(pos, pos);
            t += 8 * MS;
            both.render(t, o);
            if (o.enabled) {
                assertEquals(ApexProtocol.ROUTE_BOTH, o.route);
                sawBoth = true;
            }
        }
        assertTrue(sawBoth);
    }

    @Test
    public void offIsClearedAfter100msAndDuplicatesAreDeduplicated() {
        ApexTriggerBridge b = new ApexTriggerBridge();
        assertNotNull(b.onEffect(true, 0x25, payload(0x44, 0x00, 7), 0));
        assertNull("identical effect -> no log", b.onEffect(true, 0x25, payload(0x44, 0x00, 7), 10 * MS));
        assertTrue(b.anyEffect());
        String off = b.onEffect(true, 0x05, payload(), 20 * MS);
        assertNotNull(off);
        assertTrue(off, off.contains("해제 예약"));
        ApexTriggerBridge.Out o = new ApexTriggerBridge.Out();
        b.render(60 * MS, o);
        assertTrue("still pending at 40 ms", b.anyEffect());
        b.render(130 * MS, o);
        assertFalse("cleared after the 100 ms hold", b.anyEffect());
        long[] st = b.stats();
        assertEquals(3, st[0]);
        assertEquals(1, st[6]);
    }

    @Test
    public void effectWithoutRefreshTimesOutAfter3Seconds() {
        ApexTriggerBridge b = new ApexTriggerBridge();
        b.onEffect(true, 0x26, payload(0xFE, 0x03, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 130), 0);
        ApexTriggerBridge.Out o = new ApexTriggerBridge.Out();
        b.setPosition(255, 0);
        b.render(2_900 * MS, o);
        assertTrue(b.anyEffect());
        assertNull(o.timeoutNote);
        b.render(3_100 * MS, o);
        assertFalse(b.anyEffect());
        assertNotNull(o.timeoutNote);
        assertEquals(1, b.stats()[7]);
        // 갱신(중복 포함)이 오면 타이머가 새로 시작
        ApexTriggerBridge c = new ApexTriggerBridge();
        c.onEffect(true, 0x26, payload(0xFE, 0x03, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 130), 0);
        c.onEffect(true, 0x26, payload(0xFE, 0x03, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 130), 2_500 * MS);
        c.render(5_000 * MS, o);
        assertTrue(c.anyEffect());
    }

    @Test
    public void unsupportedEffectBecomesOffAndIsCountedNotSent() {
        ApexTriggerBridge b = new ApexTriggerBridge();
        b.onEffect(true, 0x23, payload(1, 2, 3), 0);
        assertFalse(b.anyEffect());
        assertEquals(1, b.stats()[4]);
        ApexTriggerBridge.Out o = new ApexTriggerBridge.Out();
        b.setPosition(255, 255);
        b.render(10 * MS, o);
        assertFalse(o.enabled);
        assertEquals(0, peak(o.samples));
    }

    @Test
    public void clearResetsEverything() {
        ApexTriggerBridge b = new ApexTriggerBridge();
        b.onEffect(true, 0x26, payload(0xFE, 0x03, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 130), 0);
        assertTrue(b.anyEffect());
        b.clear();
        assertFalse(b.anyEffect());
        ApexTriggerBridge.Out o = new ApexTriggerBridge.Out();
        b.setPosition(255, 255);
        b.render(8 * MS, o);
        assertFalse(o.enabled);
    }

    @Test
    public void allOutputsStayWithinHardCap() {
        ApexTriggerBridge b = new ApexTriggerBridge();
        b.onEffect(true, 0x25, payload(0x44, 0x00, 7), 0);
        b.onEffect(false, 0x26, payload(0xFE, 0x03, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 200), 0);
        ApexTriggerBridge.Out o = new ApexTriggerBridge.Out();
        long t = 0;
        for (int i = 0; i < 400; i++) {
            int pos = (i * 7) % 256;
            b.setPosition(pos, 255 - pos);
            t += 8 * MS;
            b.render(t, o);
            b.onEffect(true, 0x25, payload(0x44, 0x00, 7), t);
            b.onEffect(false, 0x26, payload(0xFE, 0x03, 0xFF, 0xFF, 0xFF, 0xFF, 0, 0, 200), t);
            for (int v : o.samples) assertTrue(Math.abs(v) <= 127);
            if (o.enabled) ApexProtocol.realtimeFrame(new int[8], new int[8], o.samples, true, o.route);   // 프레임 검증을 통과해야 함
        }
    }
}
