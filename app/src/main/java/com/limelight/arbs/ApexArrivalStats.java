package com.limelight.arbs;

/**
 * ARBS A1-3c: PCM 패킷 도착 간격 통계(평균 / p95 / 최대 / 1초 내 최대 무수신 구간). 순수 로직.
 * add()는 PCM 콜백 스레드, snapshot()은 엔진 스레드에서 호출하므로 synchronized.
 */
public final class ApexArrivalStats {
    public static final int MAX_SAMPLES = 1024;

    public static final class Snapshot {
        public int packets;
        public double avgMs, p95Ms, maxMs, maxSilenceMs;
    }

    private final long[] gaps = new long[MAX_SAMPLES];
    private int n;
    private long lastNs;      // 마지막 도착 시각(0 = 없음)
    private long sumNs, maxNs;
    private int total;

    public synchronized void add(long nowNs) {
        total++;
        if (lastNs != 0) {
            long g = nowNs - lastNs;
            sumNs += g;
            if (g > maxNs) maxNs = g;
            if (n < MAX_SAMPLES) gaps[n++] = g;
        }
        lastNs = nowNs;
    }

    /** [nowNs] = 창 끝. 마지막 도착 이후 경과도 무수신 구간에 포함한다. 호출 후 창을 초기화(마지막 도착 시각은 유지). */
    public synchronized Snapshot snapshotAndReset(long nowNs, Snapshot out) {
        out.packets = total;
        int cnt = n;
        out.avgMs = cnt == 0 ? 0 : sumNs / 1e6 / cnt;
        out.maxMs = maxNs / 1e6;
        long tail = lastNs == 0 ? 0 : Math.max(0, nowNs - lastNs);
        out.maxSilenceMs = Math.max(maxNs, tail) / 1e6;
        if (cnt == 0) {
            out.p95Ms = 0;
        } else {
            long[] c = java.util.Arrays.copyOf(gaps, cnt);
            java.util.Arrays.sort(c);
            int idx = (int) Math.min(cnt - 1, Math.ceil(cnt * 0.95) - 1);
            out.p95Ms = c[Math.max(0, idx)] / 1e6;
        }
        n = 0;
        sumNs = 0;
        maxNs = 0;
        total = 0;
        return out;
    }

    /** 새 세션/활성화: 이전 도착 시각을 잊는다. */
    public synchronized void reset() {
        n = 0;
        sumNs = 0;
        maxNs = 0;
        total = 0;
        lastNs = 0;
    }
}
