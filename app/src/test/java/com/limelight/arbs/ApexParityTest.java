package com.limelight.arbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ARBS A1-3e: ASB 대조표(docs/ASB_PARITY.md) 항목 4·5·7·9의 수치 일치 테스트. */
public class ApexParityTest {

    /** ASB Apex6PcmGain.h:12-22 를 그대로 옮긴 참조 구현(gainPercent 100..200). */
    private static int asbScale(int sample, int gainPercent) {
        long gain = Math.max(100, Math.min(200, gainPercent));
        long fullScale = sample >= 0 ? 32767 : 32768;
        if (gain == 100) return (int) ((long) sample * 127 / fullScale);
        long magnitude = Math.abs((long) sample);
        long denominator = fullScale * 100 + (gain - 100) * magnitude;
        return (int) ((long) sample * 127 * gain / denominator);
    }

    private static ApexPcmPipeline asbLike(float g) {
        ApexPcmPipeline p = new ApexPcmPipeline();
        p.setAsbCurve(true);
        p.setCap(127);
        p.setGain(g);
        return p;
    }

    @Test
    public void gainOneCap127MatchesAsbDefaultWithin1() {
        ApexPcmPipeline p = asbLike(1.0f);
        for (int v = -32768; v <= 32767; v += 211) {
            assertEquals("sample=" + v, asbScale(v, 100), p.scale(v), 1.0);
        }
        assertEquals(127, p.scale(32767f));
        assertEquals(-127, p.scale(-32768f));
    }

    @Test
    public void asbCurveEndpointsAndSlopeMatchForGain2() {
        // ASB gain 200%: y = 2x/(1+|x|). ARBS g=2, cap 127 -> 같은 곡선(양자화 오차 1 이내).
        ApexPcmPipeline p = asbLike(2.0f);
        for (int v = -32768; v <= 32767; v += 173) {
            assertEquals("sample=" + v, asbScale(v, 200), p.scale(v), 1.0);
        }
        assertEquals(127, p.scale(32767f));
    }

    @Test
    public void asbCurveSmallSignalSlopeIsGainAndOutputNeverExceedsCap() {
        for (float g : new float[]{1f, 2f, 4f, 8f}) {
            ApexPcmPipeline p = asbLike(g);
            p.setCap(100);
            // x = 1% FS -> 약 g * 1% * cap
            double expect = g * 0.01 / (1 + (g - 1) * 0.01) * 100;
            assertEquals("g=" + g, expect, p.scale(327.68f), 0.6);
            for (int v = -32768; v <= 32767; v += 97) assertTrue(Math.abs(p.scale(v)) <= 100);
        }
    }

    @Test
    public void asbCurveIsMonotonicAndOdd() {
        ApexPcmPipeline p = asbLike(4.0f);
        int prev = -200;
        for (int v = -32768; v <= 32767; v += 64) {
            int o = p.scale(v);
            assertTrue("monotonic at " + v, o >= prev);
            prev = o;
        }
        assertEquals(-p.scale(9000f), p.scale(-9000f));
    }

    @Test
    public void defaultsFollowTheTaskSpec() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        assertTrue("ASB curve default", p.isAsbCurve());
        assertEquals("gate default off", 0f, p.getGateThreshold(), 0f);
        assertEquals(321, p.getTaps());
        assertEquals(ApexPcmPipeline.DEFAULT_CAP, p.getCap());
        assertEquals(40, ApexPcmPipeline.DEFAULT_CAP);
    }

    @Test
    public void silentMsCountsQuietBlocksAndResetsOnSignal() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        short[] quiet = new short[480];                         // 240 프레임 = 5 ms 무음
        for (int i = 0; i < 400; i++) p.process(quiet, 240);    // 2 s
        assertTrue("silent=" + p.getSilentMs(), p.getSilentMs() >= 1990);
        short[] loud = new short[480];
        java.util.Arrays.fill(loud, (short) 3000);
        p.process(loud, 240);
        assertEquals(0.0, p.getSilentMs(), 0.001);
        // 0.2% FS (임계 0.3% 미만) 는 무음으로 센다
        short[] faint = new short[480];
        java.util.Arrays.fill(faint, (short) 65);               // 65/32768 = 0.2%
        for (int i = 0; i < 20; i++) p.process(faint, 240);
        assertTrue(p.getSilentMs() >= 95);
    }

    @Test
    public void rumbleAmplitudeMatchesAsb112AtFullCap() {
        assertEquals(112, ApexRumbleSynth.amplitude(255, 127));
        assertEquals(56, ApexRumbleSynth.amplitude(128, 127));     // 128/255*112
    }
}
