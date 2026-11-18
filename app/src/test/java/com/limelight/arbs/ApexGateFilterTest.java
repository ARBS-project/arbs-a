package com.limelight.arbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** ARBS A1-3d: 노이즈 게이트, 짧아진 필터(군지연 ≤2 ms) 주파수 응답/오버슈트, 큰 이득에서도 상한 불가침. */
public class ApexGateFilterTest {

    private static short[] tone(double hz, double amp, int start, int frames) {
        short[] pcm = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            short s = (short) Math.round(Math.sin(2 * Math.PI * hz * (start + i) / 48000.0) * amp);
            pcm[2 * i] = s;
            pcm[2 * i + 1] = s;
        }
        return pcm;
    }

    /** 5 ms 블록 [blocks]개를 밀어 넣으며 매 블록 큐를 비워 |출력| 최대(좌우)를 샘플 순서대로 모은다. */
    private static List<Integer> feed(ApexPcmPipeline p, double hz, double amp, int blocks, int[] startFrame) {
        List<Integer> out = new ArrayList<>();
        int[] l = new int[8];
        int[] r = new int[8];
        for (int b = 0; b < blocks; b++) {
            short[] pcm = amp == 0 ? new short[480] : tone(hz, amp, startFrame[0], 240);
            startFrame[0] += 240;
            p.process(pcm, 240);
            int n;
            while ((n = p.pop(l, r, 8)) > 0) {
                for (int i = 0; i < n; i++) out.add(Math.max(Math.abs(l[i]), Math.abs(r[i])));
            }
        }
        return out;
    }

    private static int maxFrom(List<Integer> v, int from) {
        int m = 0;
        for (int i = from; i < v.size(); i++) m = Math.max(m, v.get(i));
        return m;
    }

    @Test
    public void filterChoicesHaveExpectedDelayAndUnityDc() {
        double[] expect = {1.67, 3.33, 6.67};
        for (int i = 0; i < ApexPcmPipeline.TAP_CHOICES.length; i++) {
            int t = ApexPcmPipeline.TAP_CHOICES[i];
            double ms = (t - 1) / 2.0 / ApexPcmPipeline.SAMPLE_RATE * 1000.0;
            assertEquals(expect[i], ms, 0.01);
            assertEquals(1.0, sum(ApexPcmPipeline.designFor(t)), 1e-4);
        }
        assertEquals("default is 321", 321, ApexPcmPipeline.TAPS);
        ApexPcmPipeline p = new ApexPcmPipeline();
        assertEquals(321, p.getTaps());
        p.setTaps(161);
        assertEquals(161, p.getTaps());
        p.setTaps(641);
        assertEquals(641, p.getTaps());
        p.setTaps(999);
        assertEquals("unsupported -> default", 321, p.getTaps());
    }

    private static double sum(float[] h) {
        double s = 0;
        for (float v : h) s += v;
        return s;
    }

    private static double gainAt(int taps, double hz) {
        float[] h = ApexPcmPipeline.designFor(taps);
        double re = 0, im = 0;
        double w = 2 * Math.PI * hz / ApexPcmPipeline.SAMPLE_RATE;
        for (int i = 0; i < h.length; i++) {
            re += h[i] * Math.cos(w * i);
            im -= h[i] * Math.sin(w * i);
        }
        return Math.hypot(re, im);
    }

    private static double overshoot(int taps) {
        double c = 0, mx = 0;
        for (float v : ApexPcmPipeline.designFor(taps)) {
            c += v;
            mx = Math.max(mx, c);
        }
        return mx - 1;
    }

    /** 응답과 오버슈트를 표준 출력에 남긴다(테스트 XML system-out 에 보존 → EVIDENCE 근거). */
    @Test
    public void responseAndOvershootReport() {
        for (int taps : ApexPcmPipeline.TAP_CHOICES) {
            StringBuilder sb = new StringBuilder("FILTER taps=" + taps + " gain@Hz:");
            for (double f : new double[]{50, 100, 200, 300, 400, 500, 600, 700, 800, 1010, 3000, 5000}) {
                sb.append(String.format(java.util.Locale.US, " %.0f=%.3f", f, gainAt(taps, f)));
            }
            sb.append(String.format(java.util.Locale.US, " | step overshoot %.2f%%", overshoot(taps) * 100));
            System.out.println(sb);
            assertTrue("passband 100 Hz taps=" + taps, gainAt(taps, 100) > 0.9);
            assertTrue("passband 300 Hz taps=" + taps, gainAt(taps, 300) > 0.65);
            assertTrue("alias band >= 1010 Hz suppressed taps=" + taps, gainAt(taps, 1010) < 0.01 && gainAt(taps, 3000) < 0.001);
            assertTrue("overshoot <= 9% taps=" + taps, overshoot(taps) < 0.09);
        }
        assertTrue("321 taps 600 Hz better than 161", gainAt(321, 600) < gainAt(161, 600));
        assertTrue("641 taps 600 Hz better than 321", gainAt(641, 600) < gainAt(321, 600));
    }

    @Test
    public void gateClosesQuietInputAfter50msAndFadesOut() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        p.setCap(60);
        p.setGain(8f);
        p.setGateThreshold(2f);
        // 1% FS 신호(임계 2% 미만) 0.5초
        List<Integer> v = feed(p, 150, 328 * 1.414, 100, new int[]{0});   // RMS ≈ 1% FS
        assertTrue("outputs=" + v.size(), v.size() > 400);
        // 처음 ~50 ms 는 열려 있고(출력 있음), 50 ms + 페이드 10 ms 뒤(≥ 65번째 출력)는 0
        assertTrue("open at start: " + maxFrom(v.subList(0, 50), 0), maxFrom(v.subList(0, 50), 0) > 0);
        assertEquals("gate must silence quiet input after hold+fade", 0, maxFrom(v, 70));
        double[] lv = p.getAndResetLevels();
        assertTrue("closed% = " + lv[6], lv[6] > 70);
    }

    @Test
    public void gateReopensQuicklyOnLoudInputAndOffKeepsQuietSignal() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        p.setCap(60);
        p.setGateThreshold(2f);
        int[] st = {0};
        feed(p, 0, 0, 40, st);                                            // 200 ms 무음 -> 닫힘
        List<Integer> loud = feed(p, 100, 10000, 8, st);                  // 40 ms 큰 신호
        assertTrue("reopens within 10 outputs: " + maxFrom(loud, 10), maxFrom(loud, 10) >= 10);
        // 게이트 끔: 작은 신호도 그대로 통과
        ApexPcmPipeline q = new ApexPcmPipeline();
        q.setCap(60);
        q.setGain(8f);
        q.setGateThreshold(0f);
        List<Integer> quiet = feed(q, 150, 328 * 1.414, 100, new int[]{0});
        assertTrue("gate off keeps quiet signal: " + maxFrom(quiet, 100), maxFrom(quiet, 100) > 0);
    }

    @Test
    public void loudSignalPassesGateOpen() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        p.setCap(60);
        p.setGateThreshold(2f);
        List<Integer> v = feed(p, 100, 10000, 100, new int[]{0});
        int pk = maxFrom(v, 40);
        assertTrue("peak=" + pk, pk >= 15);
        assertEquals(0, p.getAndResetLevels()[6], 0.0001);
    }

    @Test
    public void highGainNeverExceedsCapEvenWithManyLimiterHits() {
        for (int cap : new int[]{20, 40, 60, 100, 127}) for (boolean asb : new boolean[]{true, false}) {
            for (float g : new float[]{1f, 2f, 3f, 4f, 6f, 8f}) {
                ApexPcmPipeline p = new ApexPcmPipeline();
                p.setAsbCurve(asb);
                p.setCap(cap);
                p.setGain(g);
                for (int v = -32768; v <= 32767; v += 31) {
                    int o = p.scale(v);
                    assertTrue("cap=" + cap + " g=" + g + " in=" + v + " out=" + o, Math.abs(o) <= cap);
                }
                double[] lv = p.getAndResetLevels();
                if (g >= 6f) assertTrue("limiter active% = " + lv[1], lv[1] > 50);
            }
        }
        ApexPcmPipeline p = new ApexPcmPipeline();
        p.setGain(100f);
        assertEquals(8.0f, p.getGain(), 1e-6);
    }

    @Test
    public void gainThreeGivesAboutThreeTimesForSmallSignals() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        p.setCap(40);
        int g1 = p.scale(2000f);
        p.setGain(3f);
        int g3 = p.scale(2000f);
        assertEquals(g1 * 3.0, g3, 1.0);                   // 2000/32768*40 = 2.4 -> 2, x3 = 7.3 -> 7
    }
}
