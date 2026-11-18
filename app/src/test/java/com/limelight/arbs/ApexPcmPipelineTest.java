package com.limelight.arbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** ARBS A1-2: 필터·스케일·큐 단위 테스트(안드로이드 의존성 없음). */
public class ApexPcmPipelineTest {

    /** 48 kHz 스테레오 사인을 [frames] 프레임 만든다(위상 연속은 호출자가 [startFrame]으로 보장). */
    private static short[] tone(double hz, double amp, int startFrame, int frames, boolean left, boolean right) {
        short[] pcm = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            double v = Math.sin(2.0 * Math.PI * hz * (startFrame + i) / 48000.0) * amp;
            short s = (short) Math.round(v);
            pcm[2 * i] = left ? s : 0;
            pcm[2 * i + 1] = right ? s : 0;
        }
        return pcm;
    }

    /** 0.2초 사인을 5ms(240프레임) 단위로 밀어 넣고, 앞 [skip] 샘플을 버린 뒤 L/R 최대 절댓값을 돌려준다. */
    private static int[] peaks(ApexPcmPipeline p, double hz, double amp, boolean left, boolean right) {
        int[] l = new int[8];
        int[] r = new int[8];
        int skip = 40;      // 필터 과도(641탭 ≈ 13샘플) + 여유
        int seen = 0;
        int pl = 0, pr = 0;
        for (int blk = 0; blk < 40; blk++) {
            p.process(tone(hz, amp, blk * 240, 240, left, right), 240);
            int n;
            while ((n = p.pop(l, r, 8)) > 0) {
                for (int i = 0; i < n; i++) {
                    if (seen++ < skip) continue;
                    pl = Math.max(pl, Math.abs(l[i]));
                    pr = Math.max(pr, Math.abs(r[i]));
                }
            }
        }
        return new int[]{pl, pr};
    }

    @Test
    public void filterDcGainIsOne() {
        double sum = 0;
        for (float h : ApexPcmPipeline.H) sum += h;
        assertEquals(1.0, sum, 1e-4);
    }

    @Test
    public void decimationProduces48To1() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        assertEquals(10, p.process(new short[480 * 2], 480));
        assertEquals(0, p.process(new short[47 * 2], 47));
        assertEquals(1, p.process(new short[2], 1));   // 47+1 = 48번째 입력에서 출력
    }

    @Test
    public void passband100HzKeepsLevel() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        p.setCap(60);
        int[] pk = peaks(p, 100.0, 10000, true, true);
        double expect = 10000.0 / 32768 * 127;       // 38.75
        assertTrue("100Hz peak=" + pk[0], Math.abs(pk[0] - expect) <= 2.0);
        assertTrue(Math.abs(pk[1] - expect) <= 2.0);
    }

    @Test
    public void passband300HzMostlyKept() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        p.setCap(60);
        int[] pk = peaks(p, 300.0, 10000, true, true);
        double expect = 10000.0 / 32768 * 127;
        assertTrue("300Hz peak=" + pk[0], pk[0] >= expect * 0.7);
    }

    @Test
    public void stopbandAliasCandidatesAreSuppressed() {
        // 3000 Hz -> 감축 후 0 Hz(DC) 로 접히고, 1010 Hz -> 10 Hz 로 접힌다. 필터가 먼저 없애야 한다.
        for (double hz : new double[]{3000.0, 1010.0, 5000.0}) {
            ApexPcmPipeline p = new ApexPcmPipeline();
            p.setCap(60);
            int[] pk = peaks(p, hz, 20000, true, true);
            assertTrue(hz + "Hz leaked peak=" + pk[0], pk[0] <= 1);
        }
    }

    @Test
    public void channelsStayIndependent() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        p.setCap(60);
        int[] pk = peaks(p, 150.0, 10000, true, false);
        assertTrue(pk[0] >= 30);
        assertEquals(0, pk[1]);
        p = new ApexPcmPipeline();
        p.setCap(60);
        pk = peaks(p, 150.0, 10000, false, true);
        assertEquals(0, pk[0]);
        assertTrue(pk[1] >= 30);
    }

    @Test
    public void scaleFormulaAndCaps() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        p.setCap(60);
        assertEquals(19, p.scale(5000f));                // round(5000/32768*127)
        assertEquals(32, p.scale(8192f));                // 31.75 -> 32
        assertEquals(-19, p.scale(-5000f));
        assertEquals(60, p.scale(32767f));               // 127 -> 하드 상한 60
        assertEquals(-60, p.scale(-32768f));
        p.setCap(40);
        assertEquals(40, p.scale(32767f));
        assertEquals(-40, p.scale(-32768f));
        assertEquals(19, p.scale(5000f));                // 상한 아래는 그대로
        p.setCap(0);
        assertEquals(0, p.scale(32767f));
    }

    @Test
    public void capCannotExceedHardLimit() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        p.setCap(127);
        assertEquals(60, p.getCap());
        assertEquals(60, p.scale(32767f));
        p.setCap(-5);
        assertEquals(0, p.getCap());
        p.setCap(61);
        assertEquals(60, p.getCap());
    }

    @Test
    public void gainScalesBeforeCap() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        p.setCap(60);
        p.setGain(0.5f);
        assertEquals(16, p.scale(8192f));                // 31.75*0.5 = 15.9 -> 16
        p.setGain(2.0f);
        assertEquals(60, p.scale(32767f));               // 이득을 키워도 하드 상한
    }

    @Test
    public void queueDropsOldestBeyond24() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        for (int i = 0; i < 20; i++) p.process(tone(100.0, 10000, i * 240, 240, true, true), 240);   // 100 샘플
        assertEquals(ApexPcmPipeline.QUEUE_LIMIT, p.queueSize());
        assertEquals(100 - ApexPcmPipeline.QUEUE_LIMIT, p.getAndResetDropped());
        assertEquals(0, p.getAndResetDropped());
        int[] l = new int[8];
        int[] r = new int[8];
        assertEquals(8, p.pop(l, r, 8));
        assertEquals(ApexPcmPipeline.QUEUE_LIMIT - 8, p.queueSize());
        p.clearQueue();
        assertEquals(0, p.queueSize());
    }

    @Test
    public void resetFilterForgetsHistory() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        p.setCap(60);
        short[] dc = new short[960 * 2];
        java.util.Arrays.fill(dc, (short) 20000);
        p.process(dc, 960);
        p.clearQueue();
        p.resetFilter();
        p.process(new short[48 * 2], 48);                // 무음 48프레임 -> 출력 1개, 이전 DC 영향 없어야 함
        int[] l = new int[8];
        int[] r = new int[8];
        assertEquals(1, p.pop(l, r, 8));
        assertEquals(0, l[0]);
        assertEquals(0, r[0]);
    }

    @Test
    public void outputPeakTracksAndResets() {
        ApexPcmPipeline p = new ApexPcmPipeline();
        p.setCap(60);
        short[] dc = new short[960 * 2];
        java.util.Arrays.fill(dc, (short) 20000);
        p.process(dc, 960);
        assertEquals(60, p.getAndResetPeak());          // 20000/32768*127=77 -> 60
        assertEquals(0, p.getAndResetPeak());
    }
}
