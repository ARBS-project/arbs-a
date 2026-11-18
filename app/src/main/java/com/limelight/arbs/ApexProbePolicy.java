package com.limelight.arbs;

/**
 * ARBS A1-3b: APEX 준비(GetInfo) 재시도 정책(순수 로직).
 *  - 첫 IF0 입력 리포트를 최대 {@link #INPUT_WAIT_MS} 기다린 뒤 GetInfo(없으면 그래도 송신).
 *  - 무응답/형식 불일치는 {@link #RETRY_INTERVAL_MS} 간격으로 최대 {@link #MAX_ATTEMPTS}회. 모두 실패해야 UNSUPPORTED.
 *  - UNSUPPORTED 후: 수동 재시도, 또는 입력 리포트가 {@link #RESTART_GAP_MS} 이상 끊겼다 다시 오면 처음부터 재시도.
 */
public final class ApexProbePolicy {
    public static final int MAX_ATTEMPTS = 10;
    public static final long INPUT_WAIT_MS = 3000;
    public static final long RETRY_INTERVAL_MS = 1000;
    public static final long RESTART_GAP_MS = 2000;

    private int attempt;
    private boolean unsupported;
    private boolean ready;
    private long lastInputMs = -1;
    private boolean inputSeen;

    /** 새 사이클 시작(attach 또는 재시도). */
    public synchronized void begin() {
        attempt = 0;
        unsupported = false;
        ready = false;
    }

    /** 새 컨트롤러(재연결)용: 입력 수신 이력까지 지운다. */
    public synchronized void resetAll() {
        begin();
        lastInputMs = -1;
        inputSeen = false;
    }

    /** 다음 시도 번호(1..MAX). */
    public synchronized int nextAttempt() { return ++attempt; }

    public synchronized int attempt() { return attempt; }

    /** 시도 실패(무응답/형식 불일치). true=재시도, false=포기(UNSUPPORTED). */
    public synchronized boolean onAttemptFailed() {
        if (attempt >= MAX_ATTEMPTS) {
            unsupported = true;
            return false;
        }
        return true;
    }

    /** 응답은 정상이나 Pro/그립 햅틱이 아님 등 재시도해도 의미 없는 경우. */
    public synchronized void onDefinitiveUnsupported() { unsupported = true; }

    public synchronized void onReady() {
        ready = true;
        unsupported = false;
    }

    public synchronized boolean isUnsupported() { return unsupported; }

    public synchronized boolean isReady() { return ready; }

    public synchronized boolean inputSeen() { return inputSeen; }

    /** 수동 재시도: UNSUPPORTED 일 때만 처음부터 다시 시작. */
    public synchronized boolean manualRetry() {
        if (!unsupported) return false;
        begin();
        return true;
    }

    /** IF0 입력 리포트 수신. UNSUPPORTED 이고 직전 리포트와 간격이 {@link #RESTART_GAP_MS} 이상이면 재시작(true). */
    public synchronized boolean onInput(long nowMs) {
        boolean restart = unsupported && lastInputMs >= 0 && nowMs - lastInputMs >= RESTART_GAP_MS;
        lastInputMs = nowMs;
        inputSeen = true;
        if (restart) begin();
        return restart;
    }
}
