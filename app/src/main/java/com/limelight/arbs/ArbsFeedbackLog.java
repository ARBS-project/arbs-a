package com.limelight.arbs;

import java.util.Locale;

/**
 * ARBS A1-1: common-c 콜백(제어 수신 스레드 → JNI)에서 온 햅틱/트리거 효과 이벤트를 로그로만 남긴다.
 * 콜백은 블로킹 금지이므로 여기서는 카운터 갱신과 1초 요약 줄만 쓴다(데이터 보관·출력 없음).
 */
public final class ArbsFeedbackLog {
    private ArbsFeedbackLog() { }

    // 햅틱 PCM 통계(모든 접근은 synchronized(LOCK)). 컨트롤러별이 아니라 전체 합산 + 마지막 컨트롤러 번호.
    private static final Object LOCK = new Object();
    private static long windowStartMs;
    private static int count;
    private static int frames;
    private static int peak;
    private static int gaps;
    private static boolean haveSeq;
    private static int lastSeq;
    private static int firstSeqInWindow;
    private static int lastController = -1;
    private static long totalPackets;
    private static boolean firstLogged;

    private static int cEntryTotal, cNoPcmTotal;

    public static void onHaptics(short controller, int sequence, short frameCount, short peakAbs, int cEntry, int cNoPcm) {
        ArbsLog log = ArbsLog.peek();
        if (log == null) return;
        long now = System.currentTimeMillis();
        String flush = null;
        synchronized (LOCK) {
            if (!firstLogged) {
                firstLogged = true;
                log.line(String.format(Locale.US, "햅틱 PCM 첫 수신: controller=%d seq=%d frames=%d peak=%d", controller & 0xFFFF, sequence, frameCount & 0xFFFF, peakAbs & 0xFFFF));
            }
            if (windowStartMs == 0) { windowStartMs = now; firstSeqInWindow = sequence; }
            if (haveSeq && sequence != lastSeq + 1) gaps++;
            haveSeq = true;
            cEntryTotal = cEntry;
            cNoPcmTotal = cNoPcm;
            lastSeq = sequence;
            lastController = controller & 0xFFFF;
            count++;
            totalPackets++;
            frames += frameCount & 0xFFFF;
            peak = Math.max(peak, peakAbs & 0xFFFF);
            if (now - windowStartMs >= 1000) {
                flush = summaryLocked(now);
            }
        }
        if (flush != null) log.line(flush);
    }

    /** 연결 종료·화면 종료 시 남은 구간을 내보낸다. */
    public static void flush(String why) {
        ArbsLog log = ArbsLog.peek();
        if (log == null) return;
        String s;
        synchronized (LOCK) {
            s = count > 0 ? summaryLocked(System.currentTimeMillis()) : null;
            haveSeq = false;
            firstLogged = false;
            long total = totalPackets;
            totalPackets = 0;
            if (s != null || total > 0) s = (s == null ? "" : s + "\n") + "햅틱 PCM 총 수신 " + total + "개 (" + why + ")";
        }
        if (s != null) log.line(s);
    }

    private static String summaryLocked(long now) {
        double sec = Math.max(0.001, (now - windowStartMs) / 1000.0);
        String s = String.format(Locale.US,
                "햅틱 PCM: ctrl=%d %.0f패킷/초(Java도착) seq %d..%d 프레임합=%d 시퀀스갭=%d peak=%d | C콜백 누계=%d PCM배열없음 누계=%d | %s",
                lastController, count / sec, firstSeqInWindow, lastSeq, frames, gaps, peak, cEntryTotal, cNoPcmTotal,
                ApexHaptics.get().statusLine());
        windowStartMs = now;
        count = 0; frames = 0; peak = 0; gaps = 0;
        firstSeqInWindow = lastSeq + 1;
        return s;
    }

    /** 트리거 효과(setAdaptiveTriggers): 이벤트마다 1줄. 플래그 0x04=우, 0x08=좌, 페이로드 각 10바이트. */
    public static void onAdaptiveTriggers(short controller, byte eventFlags, byte typeLeft, byte typeRight, byte[] left, byte[] right) {
        ArbsLog log = ArbsLog.peek();
        if (log == null) return;
        log.line(String.format(Locale.US, "트리거 효과: ctrl=%d flags=0x%02X typeL=0x%02X typeR=0x%02X L=%s R=%s",
                controller & 0xFFFF, eventFlags & 0xFF, typeLeft & 0xFF, typeRight & 0xFF, hex(left), hex(right)));
    }

    private static String hex(byte[] b) {
        if (b == null) return "-";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02X", x & 0xFF));
        return sb.toString();
    }
}
