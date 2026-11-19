package com.limelight.arbs;

import java.util.Locale;

/**
 * ARBS A1-5: 장시간 시험용 기록. 출력 동작에는 관여하지 않는다(숫자 누계 + 한 줄 문자열 만들기만).
 * "ARBS 1분 요약:" = 최근 1분 구간 값, "ARBS 세션 요약:" = 세션 누계. 안드로이드 의존성 없음(단위 테스트 가능).
 */
public final class ArbsSessionStats {
    /** 로그 파일이 이 크기를 넘으면 1초 줄은 생략하고 1분 요약만 남긴다(상세 로그 설정을 켜면 항상 기록). */
    public static final long PER_SECOND_LIMIT_BYTES = 20L * 1024 * 1024;
    public static final long MINUTE_NS = 60_000_000_000L;

    public static final int PCM_PKTS = 0, PCM_GAPS = 1, DROPPED = 2, UNDERRUNS = 3, GRIP_FRAMES = 4, TRIG_FRAMES = 5,
            RUMBLE_CALLS = 6, RUMBLE_TIMEOUTS = 7, TRIG_APPLIED = 8, TRIG_STOPS = 9, USB_ERRORS = 10, RETRIES = 11,
            RECONNECTS = 12, N = 13;

    private final long[] cum = new long[N];
    private final long[] minuteBase = new long[N];
    private long startNs, lastMinuteNs;
    private double maxSilenceMs;
    private double extraSum, minExtraSum;
    private long extraN, minExtraN;
    private double rttSum, minRttSum;
    private long rttN, minRttN;
    private int batteryStart = -1, batteryEnd = -1;
    private boolean crashed, started;

    public static boolean perSecondAllowed(long logFileBytes, boolean verbose) {
        return verbose || logFileBytes <= PER_SECOND_LIMIT_BYTES;
    }

    public synchronized void reset(long nowNs) {
        long keepRetries = cum[RETRIES], keepReconnects = cum[RECONNECTS]; // 세션 시작 전(USB 연결·GetInfo)에 센 값은 유지
        java.util.Arrays.fill(cum, 0);
        java.util.Arrays.fill(minuteBase, 0);
        cum[RETRIES] = minuteBase[RETRIES] = keepRetries;
        cum[RECONNECTS] = minuteBase[RECONNECTS] = keepReconnects;
        startNs = lastMinuteNs = nowNs;
        started = true;
        maxSilenceMs = 0;
        extraSum = minExtraSum = rttSum = minRttSum = 0;
        extraN = minExtraN = rttN = minRttN = 0;
        batteryStart = batteryEnd = -1;
        crashed = false;
    }

    /** 이전 세션 값을 지우지 않고 누적만 늘린다(재연결·재시도는 세션 밖에서도 센다). */
    public synchronized void add(int idx, long v) { cum[idx] += v; }

    /** 다른 곳에서 이미 누계를 가진 값(rumble 호출, 트리거 적용 수 등)을 그대로 설정. */
    public synchronized void set(int idx, long absolute) { cum[idx] = absolute; }

    public synchronized long get(int idx) { return cum[idx]; }

    public synchronized void silenceMs(double ms) { if (ms > maxSilenceMs) maxSilenceMs = ms; }

    public synchronized void addExtraDelay(double ms) { extraSum += ms; extraN++; minExtraSum += ms; minExtraN++; }

    public synchronized void addRtt(double ms) {
        if (ms < 0) return;
        rttSum += ms; rttN++; minRttSum += ms; minRttN++;
    }

    public synchronized void batteryStart(int v) { batteryStart = v; }

    public synchronized void batteryEnd(int v) { batteryEnd = v; }

    public synchronized void markCrash() { crashed = true; }

    public synchronized boolean minuteDue(long nowNs) { return started && nowNs - lastMinuteNs >= MINUTE_NS; }

    private static String hms(long ns) {
        long s = Math.max(0, ns / 1_000_000_000L);
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static String battery(int v) { return v < 0 ? "n/a" : String.valueOf(v); }

    /** 최근 1분(구간) 값. 호출하면 구간 기준점이 현재로 이동한다. */
    public synchronized String minuteLine(long nowNs, String session, String apex) {
        long[] d = new long[N];
        for (int i = 0; i < N; i++) { d[i] = cum[i] - minuteBase[i]; minuteBase[i] = cum[i]; }
        String s = String.format(Locale.US,
                "ARBS 1분 요약: 경과 %s 세션=%s APEX=%s | PCM 패킷 %d 갭 %d | 송신 그립 %d 트리거 %d 프레임 | 폐기 %d 언더런 %d | "
                        + "rumble 호출 %d 타임아웃 %d | 트리거 효과 적용 %d 해제 %d | ARBS추가지연 평균 %s | Moonlight RTT 평균 %s | USB오류 %d (최근 1분 구간)",
                hms(nowNs - startNs), session, apex, d[PCM_PKTS], d[PCM_GAPS], d[GRIP_FRAMES], d[TRIG_FRAMES], d[DROPPED], d[UNDERRUNS],
                d[RUMBLE_CALLS], d[RUMBLE_TIMEOUTS], d[TRIG_APPLIED], d[TRIG_STOPS],
                minExtraN == 0 ? "n/a" : String.format(Locale.US, "%.1fms", minExtraSum / minExtraN),
                minRttN == 0 ? "n/a" : String.format(Locale.US, "%.0fms", minRttSum / minRttN), d[USB_ERRORS]);
        minExtraSum = 0; minExtraN = 0; minRttSum = 0; minRttN = 0;
        lastMinuteNs = nowNs;
        return s;
    }

    /** 세션 누계. */
    public synchronized String sessionLine(long nowNs, String reason) {
        return String.format(Locale.US,
                "ARBS 세션 요약: 총 시간 %s (%s) | PCM 패킷 %d 갭 %d 최대무수신 %.0fms | 송신 그립 %d 트리거 %d 프레임 | 폐기 %d 언더런 %d | "
                        + "rumble 호출 %d 타임아웃 %d | 트리거 효과 적용 %d 해제 %d | ARBS추가지연 평균 %s | Moonlight RTT 평균 %s | USB오류 %d | "
                        + "재시도 %d 재연결 %d | APEX 배터리(GetInfo 원값) 시작 %s 종료 %s | 크래시 %d",
                hms(nowNs - startNs), reason, cum[PCM_PKTS], cum[PCM_GAPS], maxSilenceMs, cum[GRIP_FRAMES], cum[TRIG_FRAMES], cum[DROPPED], cum[UNDERRUNS],
                cum[RUMBLE_CALLS], cum[RUMBLE_TIMEOUTS], cum[TRIG_APPLIED], cum[TRIG_STOPS],
                extraN == 0 ? "n/a" : String.format(Locale.US, "%.1fms", extraSum / extraN),
                rttN == 0 ? "n/a" : String.format(Locale.US, "%.0fms", rttSum / rttN), cum[USB_ERRORS],
                cum[RETRIES], cum[RECONNECTS], battery(batteryStart), battery(batteryEnd), crashed ? 1 : 0);
    }
}
