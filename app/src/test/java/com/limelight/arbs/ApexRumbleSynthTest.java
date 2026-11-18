package com.limelight.arbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ARBS A1-3: rumble 합성(65/150 Hz 사인, 진폭, PCM 우선, 3초 타임아웃) 단위 테스트. */
public class ApexRumbleSynthTest {

    @Test
    public void amplitudeUsesUpper8BitsAndCap() {
        assertEquals(0, ApexRumbleSynth.amplitude(0, 60));
        assertEquals(60, ApexRumbleSynth.amplitude(255, 60));
        assertEquals(40, ApexRumbleSynth.amplitude(255, 40));
        assertEquals(30, ApexRumbleSynth.amplitude(128, 60));     // 128/255*60 = 30.1
        assertEquals(112, ApexRumbleSynth.amplitude(255, 127));   // ASB 사인 최댓값 112(Apex6HapticBridge.cpp:86)
        assertEquals(112, ApexRumbleSynth.amplitude(255, 112));
        assertEquals(100, ApexRumbleSynth.amplitude(255, 100));
        assertEquals(0, ApexRumbleSynth.amplitude(255, -3));
    }

    @Test
    public void upperByteOfSixteenBitValueIsUsed() {
        ApexRumbleSynth s = new ApexRumbleSynth();
        s.set(0xFFFF, 0x8000, 0);
        assertEquals(255, s.low8());
        assertEquals(128, s.high8());
        s.set(0x00FF, 0x0100, 0);
        assertEquals(0, s.low8());          // 하위 8비트는 버림
        assertEquals(1, s.high8());
    }

    private static int[][] run(ApexRumbleSynth s, int cap, int frames) {
        int[] allL = new int[frames * 8];
        int[] allR = new int[frames * 8];
        int[] l = new int[8];
        int[] r = new int[8];
        for (int f = 0; f < frames; f++) {
            java.util.Arrays.fill(l, 0);
            java.util.Arrays.fill(r, 0);
            s.mix(l, r, 8, cap);
            System.arraycopy(l, 0, allL, f * 8, 8);
            System.arraycopy(r, 0, allR, f * 8, 8);
        }
        return new int[][]{allL, allR};
    }

    private static int peak(int[] a) {
        int p = 0;
        for (int v : a) p = Math.max(p, Math.abs(v));
        return p;
    }

    private static int zeroCrossings(int[] a) {
        int c = 0;
        for (int i = 1; i < a.length; i++) if ((a[i - 1] < 0 && a[i] >= 0) || (a[i - 1] > 0 && a[i] <= 0)) c++;
        return c;
    }

    @Test
    public void lowIs65HzLeftAndHighIs150HzRight() {
        ApexRumbleSynth s = new ApexRumbleSynth();
        s.set(0xFF00, 0xFF00, 0);
        int[][] o = run(s, 60, 125);                  // 1초 = 1000 샘플
        assertTrue("peak L=" + peak(o[0]), peak(o[0]) >= 58 && peak(o[0]) <= 60);
        assertTrue(peak(o[1]) >= 58 && peak(o[1]) <= 60);
        int zl = zeroCrossings(o[0]);                 // 65 Hz -> 약 130 번 부호 변화
        int zr = zeroCrossings(o[1]);                 // 150 Hz -> 약 300
        assertTrue("L crossings=" + zl, zl >= 120 && zl <= 140);
        assertTrue("R crossings=" + zr, zr >= 285 && zr <= 315);
    }

    @Test
    public void onlyLowSetLeavesRightSilent() {
        ApexRumbleSynth s = new ApexRumbleSynth();
        s.set(0x8000, 0, 0);
        int[][] o = run(s, 60, 50);
        assertTrue(peak(o[0]) >= 28);
        assertEquals(0, peak(o[1]));
    }

    @Test
    public void neverExceedsHardCap() {
        ApexRumbleSynth s = new ApexRumbleSynth();
        s.set(0xFFFF, 0xFFFF, 0);
        int[][] o = run(s, 127, 200);
        assertTrue(peak(o[0]) <= 112 && peak(o[0]) >= 108);
        assertTrue(peak(o[1]) <= 112 && peak(o[1]) >= 108);
    }

    @Test
    public void pcmNonZeroChannelWinsPerChannel() {
        ApexRumbleSynth s = new ApexRumbleSynth();
        s.set(0xFF00, 0xFF00, 0);
        int[] l = {0, 0, 5, 0, 0, 0, 0, 0};      // 왼쪽만 PCM 비무음
        int[] r = new int[8];
        int used = s.mix(l, r, 8, 60);
        assertEquals(1, used);                     // 오른쪽만 rumble
        assertEquals(5, l[2]);
        assertEquals(0, l[0]);
        assertTrue(peak(r) > 0);
    }

    @Test
    public void silentPcmFallsBackToRumbleAndZeroRumbleLeavesPcm() {
        ApexRumbleSynth s = new ApexRumbleSynth();
        int[] l = new int[8];
        int[] r = new int[8];
        assertEquals(0, s.mix(l, r, 8, 60));       // rumble 0 -> 그대로
        assertEquals(0, peak(l));
        s.set(0xFF00, 0, 0);
        assertEquals(1, s.mix(l, r, 8, 60));
        assertTrue(peak(l) > 0);
    }

    @Test
    public void timesOutAfterThreeSecondsWithoutUpdate() {
        ApexRumbleSynth s = new ApexRumbleSynth();
        s.set(0xFF00, 0xFF00, 1_000_000_000L);
        assertFalse(s.checkTimeout(1_000_000_000L + 2_900_000_000L));
        assertTrue(s.isActive());
        assertTrue(s.checkTimeout(1_000_000_000L + 3_100_000_000L));
        assertFalse(s.isActive());
        assertFalse(s.checkTimeout(1_000_000_000L + 9_000_000_000L));   // 이미 정지
    }

    @Test
    public void refreshResetsTimeoutAndZeroStopsImmediately() {
        ApexRumbleSynth s = new ApexRumbleSynth();
        s.set(0xFF00, 0, 0);
        s.set(0xFF00, 0, 2_500_000_000L);
        assertFalse(s.checkTimeout(5_000_000_000L));
        s.set(0, 0, 5_100_000_000L);
        assertFalse(s.isActive());
    }
}
