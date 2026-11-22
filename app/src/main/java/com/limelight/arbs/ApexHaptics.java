package com.limelight.arbs;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.Process;
import android.os.SystemClock;

import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/**
 * ARBS A1-2: PCM 햅틱 -> APEX 그립 출력 엔진(싱글톤). IF2(OUT 0x06 / IN 0x83)로만 0x53/0x57 을 보낸다. IF0 OUT 은 쓰지 않는다.
 *
 * 모든 USB I/O 는 엔진 스레드 하나가 소유한다. 다른 스레드는 플래그만 바꾼다(원자적).
 *   활성 조건 = 준비됨(GetInfo 통과) && 세션 진행 중 && !onPause && !사용자 정지
 *   활성화 = 0x53 그립 활성 1건 -> 94 ms -> 8 ms 마다 0x57(0x9B)
 *   비활성화(어떤 이유든) = 중립 0x57 x3(8 ms) -> 0x53 `01 02 00` -> `01 12 00`
 * PCM 이 100 ms 안 오면 큐를 비우고 중립 프레임만 보낸다(무음 구간은 50 ms 마다 keepalive).
 */
public final class ApexHaptics {
    private static final ApexHaptics INSTANCE = new ApexHaptics();

    public static ApexHaptics get() { return INSTANCE; }

    public enum State { NONE, PROBING, READY, UNSUPPORTED }

    private static final int IF_ID = 2;
    private static final int EP_IN = 0x83;
    private static final int EP_OUT = 0x06;
    private static final long NO_PCM_NEUTRAL_MS = 100;
    private static final long KEEPALIVE_MS = 50;
    private volatile long keepaliveInterval = KEEPALIVE_MS;
    private static final int IDLE_ENTER_FRAMES = 8;
    private static final int MAX_RX_LOG = 20;

    public final ApexPcmPipeline pipe = new ApexPcmPipeline();
    /** ARBS A1-3b: 외부망 지터 버퍼(적응형 재생 지연). 큐 용량은 버퍼 요구량으로 확장. */
    private final ApexJitterBuffer jitter = new ApexJitterBuffer();
    /** A1-3c: PCM 도착 간격 통계. */
    private final ApexArrivalStats arrival = new ApexArrivalStats();
    private final ApexArrivalStats.Snapshot arrSnap = new ApexArrivalStats.Snapshot();
    { pipe.setLimit(ApexJitterBuffer.REQUIRED_QUEUE); }
    /** ARBS A1-3: 일반 진동 -> 그립 사인(PCM 비무음 채널이 우선). */
    public final ApexRumbleSynth rumble = new ApexRumbleSynth();
    private final ApexScheduler sched = new ApexScheduler();
    /** ARBS A1-4: 트리거 효과 -> 트리거 채널(ASB Mix 경로 이식). */
    public final ApexTriggerBridge trig = new ApexTriggerBridge();
    /** A1-5: 장시간 시험용 요약(출력 동작과 무관). */
    public final ArbsSessionStats stats = new ArbsSessionStats();
    private volatile boolean summaryEmitted = true;
    private volatile int lastProbeBattery = -1;
    private int attachCount;
    private long rumbleBase;
    private long[] trigBase = new long[17];
    private final ApexTriggerBridge.Out trigOut = new ApexTriggerBridge.Out();
    private volatile boolean triggerOn;
    /** B5 D-B5-1: 기기 features 0x10(프로브 통과마다 엔진 스레드에서 정함). 트리거 사용 = 설정 AND 기기. */
    private volatile boolean hwTrigger = true;

    private boolean trigOn() { return triggerOn && hwTrigger; }
    private int trigTarget = ApexProtocol.ROUTE_BOTH, trigRelease;
    private static final int TRIGGER_RELEASE_FRAMES = 4;   // ASB kTriggerReleaseFrames (Apex6HapticBridge.cpp:21,1226-1232)
    private long trigRetargets, trigFramesWin;
    private final long[] trigPrev = new long[17];
    private int trigEffectLogged;

    private volatile State state = State.NONE;
    private volatile boolean sessionRunning, paused, userStopped, terminate, active;
    private volatile int apexNumber = -1;
    private volatile long lastPcmNs;

    private UsbDeviceConnection conn;
    private UsbEndpoint epIn, epOut;
    private Thread thread;

    // PCM 콜백 스레드 전용
    private boolean haveSeq;
    private int lastSeq;

    // 통계(콜백 스레드가 쓰고 엔진 스레드가 1초마다 읽음)
    private volatile int winPackets, winGaps, winForeign, winInactive;

    // ---------------------------------------------------------------- 외부 API

    public State getState() { return state; }

    public boolean isReady() { return state == State.READY; }

    public boolean isActive() { return active; }

    public boolean isUserStopped() { return userStopped; }

    public void setControllerNumber(int n) {
        apexNumber = n;
        ArbsLog.i("APEX 컨트롤러 번호 = " + n);
    }

    /** ApexController 가 IF0/IF2 claim 을 마친 뒤 호출. IF2 엔드포인트를 찾고 엔진 스레드를 시작한다. */
    public synchronized void attach(UsbDeviceConnection c, UsbDevice dev) {
        detach("재연결");
        if (attachCount++ > 0) stats.add(ArbsSessionStats.RECONNECTS, 1);
        UsbInterface usbIf = null;
        for (int i = 0; i < dev.getInterfaceCount(); i++) if (dev.getInterface(i).getId() == IF_ID) usbIf = dev.getInterface(i);
        UsbEndpoint in = null, out = null;
        if (usbIf != null) {
            for (int e = 0; e < usbIf.getEndpointCount(); e++) {
                UsbEndpoint ep = usbIf.getEndpoint(e);
                if (ep.getAddress() == EP_IN && ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep;
                if (ep.getAddress() == EP_OUT && ep.getDirection() == UsbConstants.USB_DIR_OUT) out = ep;
            }
        }
        if (in == null || out == null) {
            state = State.UNSUPPORTED;
            ArbsLog.i("APEX 햅틱 불가: IF2 의 IN 0x83/OUT 0x06 을 찾지 못함 - 아무것도 보내지 않음");
            return;
        }
        conn = c;
        epIn = in;
        epOut = out;
        policy.resetAll();
        retryRequested = false;
        startThread();
    }

    private void startThread() {
        terminate = false;
        active = false;
        state = State.PROBING;
        thread = new Thread(this::run, "arbs-apex");
        thread.setDaemon(true);
        thread.start();
    }

    // ---------------------------------------------------------------- A1-3b: 준비 재시도

    private final ApexProbePolicy policy = new ApexProbePolicy();
    private volatile boolean retryRequested;

    /** ApexController.handleRead 에서 IF0 입력 리포트마다 호출(가벼움). 끊겼다 다시 오면 UNSUPPORTED 재시도. */
    public void onInputReport() {
        if (policy.onInput(SystemClock.uptimeMillis())) {
            retryRequested = true;
            ArbsLog.i("APEX 입력 리포트 재개 감지 -> 준비 재시도 요청");
        }
    }

    /** 스트림 화면 [APEX 다시 연결]: UNSUPPORTED 면 재시도, 엔진 스레드가 죽어 있으면 다시 시작. 결과 문구 반환. */
    public synchronized String manualRetry() {
        Thread t = thread;
        if (t == null || !t.isAlive()) {
            if (conn == null || epIn == null || epOut == null) {
                ArbsLog.i("APEX 다시 연결: 동글 미연결(NONE) - 할 수 없음");
                return "동글 미연결";
            }
            policy.begin();
            retryRequested = false;
            ArbsLog.i("APEX 다시 연결(수동): 엔진 스레드 재시작");
            startThread();
            return "재시작";
        }
        if (state == State.UNSUPPORTED && policy.manualRetry()) {
            retryRequested = true;
            ArbsLog.i("APEX 다시 연결(수동): 준비 재시도 요청");
            return "재시도 요청";
        }
        ArbsLog.i("APEX 다시 연결: 상태=" + state + " - 재시도 불필요");
        return "상태 " + state;
    }

    /** 설정 화면 표시용: NONE / PROBING / READY / UNSUPPORTED / USER_STOP. */
    public String statusText() {
        State s = state;
        if (s == State.READY && userStopped) return "USER_STOP";
        if (s == State.PROBING) return "PROBING " + policy.attempt() + "/" + ApexProbePolicy.MAX_ATTEMPTS;
        return s.name();
    }

    /** USB 분리/컨트롤러 종료/앱 종료: 활성이었다면 정지 시퀀스를 보낸 뒤 스레드를 끝낸다. connection.close() 전에 호출. */
    public void detach(String reason) {
        Thread t;
        synchronized (this) {
            t = thread;
            thread = null;
        }
        if (t == null) {
            state = State.NONE;
            conn = null;
            return;
        }
        ArbsLog.i("APEX 엔진 종료 요청: " + reason);
        stopReason = reason;
        terminate = true;
        try {
            t.join(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        state = State.NONE;
        apexNumber = -1;
        conn = null;
    }

    private volatile String stopReason = "";

    /** 세션 시작(connectionStarted). [cap] = 설정 슬라이더(0..60). */
    /** 무음 시 처리(A1-3e): 1=중립 keepalive 50ms(현행) 2=keepalive 200ms 3=긴 무음 시 활성 해제 4=무음 중 송신 중단. */
    public static final int SILENCE_KEEPALIVE_50 = 1, SILENCE_KEEPALIVE_200 = 2, SILENCE_SUSPEND = 3, SILENCE_STOP_SENDING = 4;
    public static final long SUSPEND_AFTER_MS = 2000;
    private volatile int silenceMode = SILENCE_KEEPALIVE_50;

    public void onSessionStart(int cap, float gain, int jitterFixedMs, float gatePct, int silence, boolean asbCurve, int taps,
                               boolean triggerHaptics, int triggerStrength) {
        triggerOn = triggerHaptics && triggerStrength > 0;
        trig.setStrengthPercent(triggerStrength);
        trig.setCeilingPercent(100);   // ASB 기본 --apex6-trigger-ceiling 100 (BridgeOptions.h:57)
        trigEffectLogged = 0;
        pipe.setCap(cap);
        pipe.setGain(gain);
        pipe.setGateThreshold(gatePct);
        pipe.setAsbCurve(asbCurve);
        pipe.setTaps(taps);
        silenceMode = silence >= 1 && silence <= 4 ? silence : SILENCE_KEEPALIVE_50;
        keepaliveInterval = silenceMode == SILENCE_KEEPALIVE_200 ? 200 : KEEPALIVE_MS;
        suspendCount = resumeCount = 0;
        jitter.setFixed(jitterFixedMs);
        arrival.reset();
        userStopped = false;
        stats.reset(System.nanoTime());                 // A1-5
        stats.batteryStart(lastProbeBattery);
        rumbleBase = totRumbleCalls;
        trigBase = trig.stats();
        summaryEmitted = false;
        sessionRunning = true;
        ArbsLog.i(String.format(Locale.US, "APEX 세션 시작: 상태=%s 진폭 상한=%d(하드 상한 %d) 이득 G=%.2f 곡선=%s 필터=%d탭(%.1fms) 게이트=%s 무음 처리=모드%d(keepalive %dms) 지터버퍼=%s",
                state, pipe.getCap(), ApexProtocol.MAX_AMPLITUDE, gain, asbCurve ? "ASB" : "tanh", pipe.getTaps(),
                (pipe.getTaps() - 1) / 2.0 / ApexPcmPipeline.SAMPLE_RATE * 1000.0,
                gatePct > 0 ? String.format(Locale.US, "%.0f%%FS", gatePct) : "끔", silenceMode, keepaliveInterval,
                jitterFixedMs > 0 ? ("고정 " + jitterFixedMs + "ms") : "자동(p95+4ms)"));
        ArbsLog.i("APEX 설정 적용(세션 시작 시점): 트리거 햅틱=" + (triggerOn ? "켬 세기 " + triggerStrength + "%(상한 127, ASB 기준)" : "끔")
                + (triggerOn && !hwTrigger ? " (기기 features 0x10 없음 -> 실제 끔)" : ""));
    }

    /** IF0 입력 리포트마다(ApexController): 트리거 아날로그 0..255 - 효과 렌더 기준 위치. */
    public void onTriggerPosition(int left, int right) {
        if (trigOn()) trig.setPosition(left, right);
    }

    /** setAdaptiveTriggers 콜백(제어 수신 스레드). eventFlags 0x08=좌, 0x04=우(Limelight.h DS_EFFECT_*). 블로킹 없음. */
    public void onTriggerEffects(short controller, byte eventFlags, byte typeLeft, byte typeRight, byte[] left, byte[] right) {
        if (!trigOn() || !sessionRunning) return;
        int num = apexNumber;
        if (num >= 0 && (controller & 0xFFFF) != num) return;
        long now = System.nanoTime();
        if ((eventFlags & 0x08) != 0) logTriggerEvent(trig.onEffect(true, typeLeft, left, now));
        if ((eventFlags & 0x04) != 0) logTriggerEvent(trig.onEffect(false, typeRight, right, now));
    }

    private void logTriggerEvent(String s) {
        if (s == null) return;
        if (trigEffectLogged < 300) {
            trigEffectLogged++;
            ArbsLog.i("APEX " + s);
        }
    }

    public void onSessionEnd(String reason) {
        if (!sessionRunning && !active) return;
        sessionRunning = false;
        stopReason = "세션 종료(" + reason + ")";
        waitInactive(600);
        emitSessionSummary("세션 종료(" + reason + ")");   // 엔진이 활성이 아니었으면 여기서 출력(이미 출력했으면 무시)
    }

    /** A1-5: "상세 로그" 설정(세션 시작 전에 호출). */
    public void setVerboseLog(boolean v) { ArbsLog.verbose = v; }

    /** A1-5: Moonlight RTT 표본(Game 통계 스레드, 1초마다). */
    public void onMoonlightRtt(int ms) {
        if (sessionRunning && ms > 0) stats.addRtt(ms);
    }

    /** A1-5: 크래시 처리기에서 호출(최대한 안전하게). 진행 중인 세션의 요약 줄을 크래시 표시와 함께 남긴다. */
    public void onCrash() {
        stats.markCrash();
        if (sessionRunning) emitSessionSummary("크래시");
    }

    private void syncExternalCounters() {
        stats.set(ArbsSessionStats.RUMBLE_CALLS, totRumbleCalls - rumbleBase);
        long[] c = trig.stats();
        stats.set(ArbsSessionStats.TRIG_APPLIED, c[2] - trigBase[2]);
        stats.set(ArbsSessionStats.TRIG_STOPS, c[3] - trigBase[3]);
    }

    private synchronized void emitSessionSummary(String reason) {
        if (summaryEmitted) return;
        summaryEmitted = true;
        syncExternalCounters();
        ArbsLog.i(stats.sessionLine(System.nanoTime(), reason));
    }

    private String sessionText() { return sessionRunning ? "연결됨" : "없음"; }

    private String apexText() { return state + (active ? "/출력중" : "/대기") + (userStopped ? "/사용자정지" : ""); }

    /** A1-5: 종료 직전 배터리 값. GetInfo 1회(기존 허용 프레임), 실패·예외는 무시하고 -1. */
    private int queryBattery() {
        try {
            byte[] buf = rxBuf != null ? rxBuf : new byte[Math.max(epIn.getMaxPacketSize(), 64)];
            byte[] req = ApexProtocol.buildGetInfo();
            if (conn.bulkTransfer(epOut, req, req.length, 200) != req.length) return -1;
            long t0 = SystemClock.uptimeMillis();
            int tries = 0;
            while (SystemClock.uptimeMillis() - t0 < 300 && tries < 32) {
                tries++;
                int n = conn.bulkTransfer(epIn, buf, buf.length, 50);
                if (n > 0) {
                    ApexProtocol.Info info = ApexProtocol.parse(buf, n);
                    if (info != null && info.checksumOk) return info.battery;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    public void onPause() {
        paused = true;
        stopReason = "onPause";
        waitInactive(600);
    }

    public void onResume() {
        paused = false;
    }

    /** 스트림 화면의 [APEX 정지]. 정지 시퀀스를 보내고 다음 세션 시작까지(또는 재개 누를 때까지) 출력하지 않는다. */
    public void userStop() {
        userStopped = true;
        stopReason = "사용자 정지";
        waitInactive(600);
    }

    public void userResume() {
        userStopped = false;
        ArbsLog.i("APEX 재개 요청(사용자)");
    }

    private void waitInactive(long ms) {
        long end = SystemClock.uptimeMillis() + ms;
        Thread t = thread;
        while (active && t != null && t.isAlive() && SystemClock.uptimeMillis() < end) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * ARBS A1-3: rumble 값(부호 없는 16비트) 수신(제어 스레드, 블로킹 없음). 값은 항상 기록하며(활성화 직전 값도 유지),
     * 비활성 동안 엔진은 출력하지 않고 정지 시퀀스에서 값을 지운다. 3초 무갱신이면 엔진이 자동 정지. (0,0) 이면 즉시 정지.
     */
    public void onRumble(int low16, int high16) {
        totRumbleCalls++;
        if (!active) totRumbleInactive++;
        rumble.set(low16, high16, System.nanoTime());
    }

    private volatile long totRumbleCalls, totRumbleInactive;

    /** PCM 콜백(제어 수신 스레드)에서 호출. 블로킹 없음: 필터 + 큐 push 만. */
    public void onPcm(short controller, int sequence, short[] pcm, int frames) {
        totCalls++;
        arrival.add(System.nanoTime());
        if (!active) {
            winInactive++;
            totInactive++;
            return;
        }
        int num = apexNumber;
        if (num >= 0 && (controller & 0xFFFF) != num) {
            winForeign++;
            totForeign++;
            return;
        }
        totDelivered++;
        if (haveSeq && sequence != lastSeq + 1) {
            pipe.resetFilter();
            winGaps++;
        }
        haveSeq = true;
        lastSeq = sequence;
        lastPcmNs = System.nanoTime();
        winPackets++;
        pipe.process(pcm, frames);
    }

    // ARBS A1-2b: 누계(콜백 스레드가 쓰고 로그 스레드가 읽음 - 통계용이라 근사치 허용)
    private volatile long totCalls, totDelivered, totInactive, totForeign;

    /** 1초 햅틱 요약에 붙는 엔진 상태: 상태/활성/사용자정지/번호/onPcm 누계. */
    public String statusLine() {
        return String.format(Locale.US, "엔진=%s 활성=%s 사용자정지=%s APEX번호=%d onPcm호출=%d 전달=%d 비활성폐기=%d 타컨트롤러=%d",
                state, active, userStopped, apexNumber, totCalls, totDelivered, totInactive, totForeign);
    }

    // ---------------------------------------------------------------- 엔진 스레드

    private long sentFrames, driveFrames, neutralFrames, underruns, rxTotal, rumbleFrames;
    // A1-3 측정(엔진 스레드 전용, 1초마다 초기화): bulkTransfer(0x57) / rxPoll / 슬롯 대기 오차
    private long xferSum, xferMax, xferN, rxSum, rxMax, rxN, sleepErrSum, sleepErrMax, sleepErrN;
    private int rxLogged;
    private byte[] rxBuf;

    private void run() {
        try {
            if (!probeUntilReady()) return;
            state = State.READY;
            ArbsLog.i("APEX 준비됨(GetInfo 통과). 세션 진행 + 포그라운드일 때 활성화됩니다");
            try {
                // A1-3: 오디오급 우선순위(실패해도 무시)
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            } catch (Throwable ignored) { }
            long statsAt = System.nanoTime();
            long lastSendNs = 0;
            int zeroRun = 0;
            boolean timeoutLogged = false;
            int[] l = new int[8];
            int[] r = new int[8];
            ApexScheduler.Decision dec = new ApexScheduler.Decision();
            ApexJitterBuffer.Result jr = new ApexJitterBuffer.Result();
            while (!terminate) {
                boolean want = state == State.READY && sessionRunning && !paused && !userStopped;
                if (want && !active) {
                    if (!activate()) break;
                    sched.start(System.nanoTime());
                    lastSendNs = 0;
                    zeroRun = 0;
                    timeoutLogged = false;
                    statsAt = System.nanoTime();
                    jitter.reset(statsAt);
                } else if (!want && active) {
                    deactivate(currentStopReason());
                }
                if (!active) {
                    Thread.sleep(15);
                    continue;
                }
                if (suspended) {
                    // 모드 3: 그립 비활성 중. PCM 은 계속 받되 최신 16샘플만 유지(재개 직전 샘플 보존), 신호 재개/rumble 이면 재활성.
                    if (pipe.getSilentMs() < 50 || rumble.isActive() || (trigOn() && trig.anyEffect())) {
                        ArbsLog.i("APEX 무음 해제: 신호 재개 -> 그립 재활성(0x53 활성 + " + ApexProtocol.ENABLE_WAIT_MS + "ms 대기, 직전 샘플 보존)");
                        if (trigOn() && !sendFrame(ApexProtocol.triggerEnableBoth(), "0x53 트리거 재활성")) {
                            ArbsLog.i("APEX 트리거 재활성 전송 실패 -> 엔진 중단");
                            break;
                        }
                        trigTarget = ApexProtocol.ROUTE_BOTH;
                        trigRelease = 0;
                        if (!sendFrame(ApexProtocol.gripRealtimeEnable(), "0x53 재활성")) {
                            ArbsLog.i("APEX 재활성 전송 실패 -> 엔진 중단");
                            break;
                        }
                        Thread.sleep(ApexProtocol.ENABLE_WAIT_MS);
                        suspended = false;
                        resumeCount++;
                        sched.start(System.nanoTime());
                        lastSendNs = 0;
                        zeroRun = 0;
                        jitter.reset(System.nanoTime());
                        trimBlockedUntilNs = System.nanoTime() + 150_000_000L;
                    } else {
                        pipe.trimTo(16);
                        LockSupport.parkNanos(8_000_000L);
                    }
                    continue;
                }
                // ---- 한 틱 (8 ms 슬롯, 절대 기한)
                long now = System.nanoTime();
                long late = now - sched.target();
                sched.poll(now, dec);
                if (dec.waitNs > 0) {
                    LockSupport.parkNanos(dec.waitNs);
                    if (Thread.interrupted()) throw new InterruptedException();
                    continue;
                }
                if (dec.skipped == 0 && !dec.catchingUp) {
                    sleepErrSum += late;
                    sleepErrMax = Math.max(sleepErrMax, late);
                    sleepErrN++;
                }
                sched.consume();
                boolean catchingUp = dec.catchingUp;
                now = System.nanoTime();
                if (rumble.checkTimeout(now)) {
                    stats.add(ArbsSessionStats.RUMBLE_TIMEOUTS, 1);
                    ArbsLog.i("APEX rumble 타임아웃(3초 무갱신) -> 진동 정지");
                }
                if (now - lastPcmNs > NO_PCM_NEUTRAL_MS * 1_000_000L) {
                    if (pipe.queueSize() > 0) pipe.clearQueue();
                    jitter.reset(now);
                    if (!timeoutLogged && lastPcmNs != 0) {
                        timeoutLogged = true;
                        ArbsLog.i("APEX: PCM 100ms 무수신 -> 중립 프레임");
                    }
                } else {
                    timeoutLogged = false;
                }

                boolean idle = zeroRun > IDLE_ENTER_FRAMES;
                int avail = pipe.queueSize();
                int n = 0;
                // A1-3b: 지터 버퍼 — 채워질 때까지 대기, 넘치면 오래된 것 폐기, 모자라면 재충전
                jitter.decide(avail, now, jr);
                if (jr.trimTo >= 0 && now >= trimBlockedUntilNs) pipe.trimTo(jr.trimTo);
                if (jr.underrun) underruns++;
                if (jr.play) {
                    n = pipe.pop(l, r, 8);
                    qDepthSum += avail;      // A1-3d: 재생 시점 큐 깊이(실제 지연 계산용)
                    qDepthN++;
                }
                if (n < 8) {
                    for (int i = n; i < 8; i++) { l[i] = 0; r[i] = 0; }
                }
                if (catchingUp && n == 0 && !rumble.isActive() && !(trigOn() && trig.anyEffect())) {
                    // 따라잡는 중인데 보낼 샘플이 없음: 이 슬롯은 건너뜀(무음 구간 보충 프레임 불필요)
                    continue;
                }
                // 일반 진동: PCM 이 비무음인 채널은 PCM 우선, 아니면 rumble 사인
                if (rumble.mix(l, r, 8, pipe.getCap()) > 0) rumbleFrames++;
                boolean allZero = true;
                for (int i = 0; i < 8; i++) if (l[i] != 0 || r[i] != 0) { allZero = false; break; }
                // ARBS A1-4: 트리거 채널(ASB renderBlock/run 의 트리거 부분과 같은 순서)
                boolean trigEnabled = false;
                int trigRoute = ApexProtocol.ROUTE_MUTE;
                if (trigOn()) {
                    trig.render(now, trigOut);
                    trigEnabled = trigOut.enabled;
                    trigRoute = trigOut.route;
                    if (trigEnabled && trigRoute != trigTarget) {
                        if (!sendTriggerRoute(trigRoute)) {
                            ArbsLog.i("APEX 트리거 route 전환 전송 실패 -> 정지 시퀀스 후 엔진 중단");
                            break;
                        }
                        trigTarget = trigRoute;
                        trigRetargets++;
                    }
                    if (trigEnabled) {
                        trigRelease = TRIGGER_RELEASE_FRAMES;
                        trigFramesWin++;
                        stats.add(ArbsSessionStats.TRIG_FRAMES, 1);
                    } else if (trigRelease > 0) {
                        trigRelease--;
                        trigEnabled = true;       // 해제 지연 4프레임: 영(0) 샘플을 Both 로 보내 잔류를 막음
                        trigRoute = ApexProtocol.ROUTE_BOTH;
                    }
                    if (trigEnabled) allZero = false;
                }
                if (allZero) zeroRun++; else zeroRun = 0;

                if (silenceMode == SILENCE_SUSPEND && allZero && zeroRun > IDLE_ENTER_FRAMES && !rumble.isActive() && !(trigOn() && trig.anyEffect())
                        && pipe.getSilentMs() >= SUSPEND_AFTER_MS) {
                    ArbsLog.i("APEX 긴 무음(" + SUSPEND_AFTER_MS + "ms) -> 그립 비활성(정지 시퀀스, 세션·PCM 수신은 유지)");
                    sendStopSequence();
                    suspended = true;
                    suspendCount++;
                    continue;
                }
                boolean send = !allZero || zeroRun <= IDLE_ENTER_FRAMES || lastSendNs == 0
                        || (silenceMode != SILENCE_STOP_SENDING && (now - lastSendNs) >= keepaliveInterval * 1_000_000L);
                if (!send) silenceSkipped++;
                if (send) {
                    byte[] f = trigEnabled ? ApexProtocol.realtimeFrame(l, r, trigOut.samples, true, trigRoute) : ApexProtocol.gripFrame(l, r);
                    long x0 = System.nanoTime();
                    boolean ok = sendFrame(f, "0x57");
                    long xd = System.nanoTime() - x0;
                    xferSum += xd;
                    xferMax = Math.max(xferMax, xd);
                    xferN++;
                    if (!ok) {
                        ArbsLog.i("APEX 0x57 전송 실패 -> 정지 시퀀스 후 엔진 중단");
                        break;
                    }
                    lastSendNs = now;
                    sentFrames++;
                    if (allZero) {
                        neutralFrames++;
                        if (zeroRun > IDLE_ENTER_FRAMES) idleSent++;
                    } else {
                        driveFrames++;
                    }
                    if (!catchingUp) {
                        long r0 = System.nanoTime();
                        rxPoll();
                        long rd = System.nanoTime() - r0;
                        rxSum += rd;
                        rxMax = Math.max(rxMax, rd);
                        rxN++;
                    }
                }

                if (now - statsAt >= 1_000_000_000L) {
                    logStats(now - statsAt);
                    statsAt = now;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            ArbsLog.i("APEX 엔진 예외: " + t.getClass().getSimpleName());
        } finally {
            if (active) deactivate(terminate ? stopReason : "엔진 오류");
            if (state == State.PROBING || state == State.READY) state = State.NONE;
            ArbsLog.i("APEX 엔진 스레드 종료");
        }
    }

    private String currentStopReason() {
        if (!sessionRunning) return stopReason.isEmpty() ? "세션 종료" : stopReason;
        if (paused) return "onPause";
        if (userStopped) return "사용자 정지";
        return stopReason;
    }

    private long qDepthSum, qDepthN, silenceSkipped, idleSent;
    private volatile boolean suspended;
    private long suspendCount, resumeCount;
    private long trimBlockedUntilNs;

    private void logStats(long windowNs) {
        double sec = Math.max(0.001, windowNs / 1e9);
        // A1-3d: 도착 간격 p95 -> 지터 버퍼 자동 목표(p95 + 4 ms). 조용한 창에서도 계산한다.
        arrival.snapshotAndReset(System.nanoTime(), arrSnap);
        jitter.adaptFromP95(arrSnap.p95Ms, arrSnap.packets, System.nanoTime());
        int packets = winPackets;
        winPackets = 0;
        int gaps = winGaps;
        winGaps = 0;
        int foreign = winForeign;
        winForeign = 0;
        long drop = pipe.getAndResetDropped();
        int peak = pipe.getAndResetPeak();
        // A1-5: 누계(요약용). 조용한 구간에도 센다.
        stats.add(ArbsSessionStats.PCM_PKTS, packets);
        stats.add(ArbsSessionStats.PCM_GAPS, gaps);
        stats.add(ArbsSessionStats.DROPPED, drop);
        stats.add(ArbsSessionStats.UNDERRUNS, underruns);
        stats.add(ArbsSessionStats.GRIP_FRAMES, sentFrames);
        stats.silenceMs(arrSnap.maxSilenceMs);
        syncExternalCounters();
        long nowStats = System.nanoTime();
        if (stats.minuteDue(nowStats)) ArbsLog.i(stats.minuteLine(nowStats, sessionText(), apexText()));
        final boolean perSec = ArbsLog.perSecondAllowed();   // 20 MB 초과 + 상세 로그 꺼짐이면 1초 줄 생략
        if (packets == 0 && driveFrames == 0 && rumbleFrames == 0 && sentFrames < 120 && gaps == 0) {
            // 조용한 구간은 로그를 아낀다(5초마다만)
            if (++quietTicks < 5) { resetWindow(); return; }
        }
        quietTicks = 0;
        long missed = sched.getAndResetMissed();
        long resyncs = sched.getAndResetResyncs();
        long catchups = sched.getAndResetCatchups();
        if (perSec) ArbsLog.i(String.format(Locale.US,
                "APEX 송신: %.0f프레임/초(구동 %d, 중립 %d, rumble %d) 큐=%d 폐기=%d 언더런=%d PCM=%d패킷 갭리셋=%d 타컨트롤러=%d 출력peak=%d/127 | "
                        + "bulk(0x57) 평균/최대=%.2f/%.2fms rx폴 평균/최대=%.2f/%.2fms 슬립오차 평균/최대=%.2f/%.2fms 놓친슬롯=%d 재동기=%d 따라잡기=%d rumble호출=%d(L=%d H=%d) 지터버퍼 목표=%dms 재충전=%d 초과폐기=%d",
                sentFrames / sec, driveFrames, neutralFrames, rumbleFrames, pipe.queueSize(), drop, underruns, packets, gaps, foreign, peak,
                avgMs(xferSum, xferN), xferMax / 1e6, avgMs(rxSum, rxN), rxMax / 1e6, avgMs(sleepErrSum, sleepErrN), sleepErrMax / 1e6,
                missed, resyncs, catchups, totRumbleCalls, rumble.low8(), rumble.high8(),
                jitter.getTarget(), jitter.getAndResetUnderruns(), jitter.getAndResetTrims()));
        // A1-3c/3d: 레벨(클리핑·게이트) / 도착 간격 / 추가 지연(재생 지점까지의 실제 지연 단일 값)
        double[] lv = pipe.getAndResetLevels();
        double filterMs = (pipe.getTaps() - 1) / 2.0 / ApexPcmPipeline.SAMPLE_RATE * 1000.0;
        double avgQ = qDepthN == 0 ? pipe.queueSize() : (double) qDepthSum / qDepthN;
        // 실제 지연 = 필터 군지연 + 재생 시점 평균 큐 깊이(1 샘플 = 1 ms) + 프레임 8 ms(모아서 송신). 버퍼 목표는 더하지 않는다(이미 큐에 반영).
        double est = filterMs + avgQ + ApexProtocol.FRAME_MS;
        if (packets > 0) stats.addExtraDelay(est);
        if (perSec) ArbsLog.i(String.format(Locale.US,
                "APEX 레벨: 리미터동작 %.1f%% 상한도달 %.1f%% 게이트닫힘 %.1f%% 입력RMS %.1f%%FS 출력RMS %.1f(상한 대비 %.0f%%) 상한=%d 이득=%.2f 게이트=%.0f%% | "
                        + "PCM도착간격 평균/p95/최대=%.1f/%.1f/%.1fms 최대무수신=%.1fms | 무음 모드%d: 무음중 송신 %d 생략 %d(keepalive %dms) 비활성 %d회 재활성 %d회 | "
                        + "ARBS추가지연=%.1fms (필터 %.1f + 재생시점 큐 평균 %.1f + 프레임 %d) 버퍼목표=%dms%s",
                lv[1], lv[2], lv[6], lv[3], lv[4], lv[5], pipe.getCap(), pipe.getGain(), pipe.getGateThreshold(),
                arrSnap.avgMs, arrSnap.p95Ms, arrSnap.maxMs, arrSnap.maxSilenceMs,
                silenceMode, idleSent, silenceSkipped, keepaliveInterval, suspendCount, resumeCount,
                est, filterMs, avgQ, ApexProtocol.FRAME_MS, jitter.getTarget(), jitter.getFixed() > 0 ? "(고정)" : "(자동 p95+4)"));
        qDepthSum = 0;
        qDepthN = 0;
        silenceSkipped = 0;
        resetWindow();
        logTriggerStats(sec);
    }

    /** A1-4: 1초 요약 "APEX 트리거:" - 수신 효과 종류/수, 송신 트리거 프레임 수/초, 해제·타임아웃. 변화가 없으면 생략. */
    private void logTriggerStats(double sec) {
        if (!trigOn()) return;
        long[] c = trig.stats();
        long frames = trigFramesWin;
        trigFramesWin = 0;
        boolean changed = frames > 0;
        for (int i = 0; i < c.length; i++) if (c[i] != trigPrev[i]) changed = true;
        if (!changed) return;
        if (!ArbsLog.perSecondAllowed()) {
            System.arraycopy(c, 0, trigPrev, 0, Math.min(c.length, trigPrev.length));
            return;
        }
        ArbsLog.i(String.format(Locale.US,
                "APEX 트리거: 수신 L=%d R=%d(적용 %d, 해제 %d, 중복 %d, 미지원 %d, 형식오류 %d) 마지막 type L=0x%02X R=0x%02X | 송신 트리거 프레임 %.0f/초(누계 L %d R %d Both %d Mix %d, route 전환 %d) | "
                        + "무기/활 break %d 상한제한 샘플 %d | 세기 %d%%",
                c[0] - trigPrev[0], c[1] - trigPrev[1], c[2] - trigPrev[2], c[3] - trigPrev[3], c[6] - trigPrev[6], c[4] - trigPrev[4], c[5] - trigPrev[5],
                c[15], c[16], frames / sec, c[11], c[12], c[13], c[14], trigRetargets, c[8] + c[9] - trigPrev[8] - trigPrev[9], c[10] - trigPrev[10],
                trig.getStrengthPercent()));
        System.arraycopy(c, 0, trigPrev, 0, Math.min(c.length, trigPrev.length));
    }

    private static double avgMs(long sumNs, long n) { return n == 0 ? 0 : sumNs / 1e6 / n; }

    private int quietTicks;

    private void resetWindow() {
        sentFrames = 0;
        driveFrames = 0;
        neutralFrames = 0;
        rumbleFrames = 0;
        underruns = 0;
        xferSum = xferMax = xferN = rxSum = rxMax = rxN = sleepErrSum = sleepErrMax = sleepErrN = 0;
    }

    /** GetInfo 1회(A0 와 같은 IF2 교환). Pro + 그립 햅틱 + 체크섬 일치일 때만 true. */
    private boolean probeUntilReady() throws InterruptedException {
        // 1) 첫 IF0 입력 리포트 대기(최대 3초, 없으면 그래도 진행)
        long w0 = SystemClock.uptimeMillis();
        if (!policy.inputSeen()) {
            ArbsLog.i("APEX 준비: 첫 IF0 입력 리포트 대기(최대 " + ApexProbePolicy.INPUT_WAIT_MS + "ms)");
            while (!terminate && !policy.inputSeen() && SystemClock.uptimeMillis() - w0 < ApexProbePolicy.INPUT_WAIT_MS) {
                Thread.sleep(20);
            }
            ArbsLog.i(policy.inputSeen() ? "APEX 준비: 첫 입력 리포트 수신(" + (SystemClock.uptimeMillis() - w0) + "ms 후)"
                    : "APEX 준비: 입력 리포트 없이 대기 만료 - 그래도 GetInfo 송신");
        }
        // 2) GetInfo 재시도(1초 간격, 최대 10회). 모두 실패해야 UNSUPPORTED. UNSUPPORTED 후에도 재시도 요청을 기다린다.
        while (!terminate) {
            state = State.PROBING;
            int result;
            do {
                int n = policy.nextAttempt();
                if (n > 1) stats.add(ArbsSessionStats.RETRIES, 1);
                ArbsLog.i("APEX GetInfo 시도 " + n + "/" + ApexProbePolicy.MAX_ATTEMPTS);
                result = probe();
                if (result == 0) {
                    policy.onReady();
                    return true;
                }
                if (result == 2) {
                    policy.onDefinitiveUnsupported();
                    break;
                }
                if (terminate) return false;
                if (!policy.onAttemptFailed()) break;
                long s0 = SystemClock.uptimeMillis();
                while (!terminate && SystemClock.uptimeMillis() - s0 < ApexProbePolicy.RETRY_INTERVAL_MS) Thread.sleep(20);
            } while (!terminate);
            if (terminate) return false;
            state = State.UNSUPPORTED;
            ArbsLog.i("APEX 햅틱 불가(UNSUPPORTED): " + policy.attempt() + "회 시도 실패. [APEX 다시 연결] 또는 동글/컨트롤러 재연결(입력 재개)로 재시도합니다");
            retryRequested = false;
            while (!terminate && !retryRequested) Thread.sleep(100);
            if (terminate) return false;
            retryRequested = false;
            ArbsLog.i("APEX 준비 재시도 시작(처음부터)");
        }
        return false;
    }

    /** GetInfo 1회. 0=통과, 1=무응답/형식 불일치(재시도), 2=응답은 정상이나 지원 불가(재시도 무의미), 3=중단. */
    private int probe() throws InterruptedException {
        byte[] buf = new byte[Math.max(epIn.getMaxPacketSize(), 64)];
        rxBuf = buf;
        int drained = 0;
        long t0 = SystemClock.uptimeMillis();
        while (drained < 128 && SystemClock.uptimeMillis() - t0 < 200) {
            if (conn.bulkTransfer(epIn, buf, buf.length, 10) <= 0) break;
            drained++;
        }
        byte[] req = ApexProtocol.buildGetInfo();
        int sent = conn.bulkTransfer(epOut, req, req.length, 1000);
        ArbsLog.i("APEX GetInfo 송신(IF2 OUT 0x06, 32바이트 1회): 반환 " + sent + ", 큐 비우기 " + drained + "건");
        if (sent != req.length) {
            ArbsLog.i("APEX GetInfo 전송 실패(반환 " + sent + ") - 재시도 대상");
            return 1;
        }
        long start = SystemClock.uptimeMillis();
        ApexProtocol.Info info = null;
        int count = 0;
        while (SystemClock.uptimeMillis() - start < 600 && info == null && count < 64 && !terminate) {
            int left = (int) Math.max(1, Math.min(100, 600 - (SystemClock.uptimeMillis() - start)));
            int n = conn.bulkTransfer(epIn, buf, buf.length, left);
            if (n > 0) {
                count++;
                info = ApexProtocol.parse(buf, n);
            }
        }
        if (terminate) return 3;
        if (info == null) {
            ArbsLog.i("APEX GetInfo 응답 없음/형식 불일치(수신 " + count + "건) - 재시도 대상");
            return 1;
        }
        ArbsLog.i("APEX GetInfo: " + info.describe());
        if (info.checksumOk) lastProbeBattery = info.battery;
        if (!info.checksumOk) {
            ArbsLog.i("APEX GetInfo 체크섬 불일치 - 재시도 대상");
            return 1;
        }
        if (!info.isPro() || !info.hasGripHaptics()) {
            ArbsLog.i("APEX 햅틱 불가: Pro/그립햅틱 아님 - 더 이상 아무것도 보내지 않음");
            return 2;
        }
        // B5 D-B5-1: features 0x10(트리거 햅틱)을 기기마다 프로브 통과 때 정함. 없어도 그립은 계속.
        hwTrigger = info.hasTriggerHaptics();
        if (!hwTrigger) {
            ArbsLog.i(String.format(Locale.US, "APEX 트리거 햅틱 없음: features=0x%02X(0x10 꺼짐) - 트리거 채널 활성·트리거 프레임 보내지 않음, 그립은 유지",
                    info.features & 0xFF));
        }
        return 0;
    }

    private boolean activate() throws InterruptedException {
        suspended = false;
        idleSent = 0;
        pipe.resetFilter();
        pipe.clearQueue();
        pipe.getAndResetDropped();
        pipe.getAndResetPeak();
        winPackets = winGaps = winForeign = winInactive = 0;
        haveSeq = false;
        lastPcmNs = 0;
        sentFrames = driveFrames = neutralFrames = underruns = rumbleFrames = 0;
        xferSum = xferMax = xferN = rxSum = rxMax = rxN = sleepErrSum = sleepErrMax = sleepErrN = 0;
        sched.getAndResetMissed();
        sched.getAndResetResyncs();
        sched.getAndResetCatchups();
        quietTicks = 0;
        trigTarget = ApexProtocol.ROUTE_BOTH;
        trigRelease = 0;
        trigFramesWin = 0;
        if (trigOn()) {
            ArbsLog.i("APEX 트리거 채널 활성: 0x53 `01 02 03 00 40` 1건(ASB buildMotorEnable 순서: 트리거 -> 그립)");
            if (!sendFrame(ApexProtocol.triggerEnableBoth(), "0x53 트리거 활성")) {
                ArbsLog.i("APEX 트리거 활성 명령 전송 실패 -> 정지 시퀀스");
                sendStopSequence();
                return false;
            }
        }
        ArbsLog.i("APEX 활성화: 0x53 그립 실시간 활성 1건 송신 후 94ms 대기");
        if (!sendFrame(ApexProtocol.gripRealtimeEnable(), "0x53 활성")) {
            ArbsLog.i("APEX 활성 명령 전송 실패 -> 정지 시퀀스");
            sendStopSequence();
            return false;
        }
        Thread.sleep(ApexProtocol.ENABLE_WAIT_MS);
        active = true;
        return true;
    }

    private void deactivate(String reason) {
        ArbsLog.i("APEX 정지 시퀀스 시작: " + reason);
        suspended = false;
        rumble.clear();   // 정지 후 재개 때 오래된 진동이 되살아나지 않게
        trig.clear();     // A1-4: 트리거 효과·중재 상태도 함께 초기화(정지 시퀀스의 `01 02 00` 이 트리거 채널을 비활성)
        trigRelease = 0;
        if (!sessionRunning) stats.batteryEnd(queryBattery());   // A1-5: 세션 종료 시 정지 시퀀스 전에 GetInfo 1회(실패 무시)
        int ok = sendStopSequence();
        active = false;
        ArbsLog.i("APEX 정지 완료: 전송 성공 " + ok + "/5, PCM 비활성 중 수신 " + winInactive + "패킷");
        if (!sessionRunning) emitSessionSummary(reason);
        winInactive = 0;
        drainRx();
    }

    /** ASB 와 같은 순서: 중립 0x57 x3 (8 ms 간격) -> 0x53 `01 02 00` -> 0x53 `01 12 00`. 성공 전송 수 반환. */
    private int sendStopSequence() {
        int ok = 0;
        for (int i = 0; i < 3; i++) {
            if (sendFrame(ApexProtocol.neutralFrame(), "정지 중립")) ok++;
            try { Thread.sleep(ApexProtocol.FRAME_MS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        for (byte[] f : ApexProtocol.disablePair()) if (sendFrame(f, "정지 0x53")) ok++;
        return ok;
    }

    /** ASB setApex6TriggerSide(Apex5Device.cpp:690-700): Both = `01 02 03 00 40`, 한쪽 = 활성 + 반대쪽 비활성. */
    private boolean sendTriggerRoute(int route) {
        if (route == ApexProtocol.ROUTE_BOTH) return sendFrame(ApexProtocol.triggerEnableBoth(), "0x53 트리거 Both");
        for (byte[] f : ApexProtocol.triggerSideEnable(route)) if (!sendFrame(f, "0x53 트리거 한쪽")) return false;
        return true;
    }

    private boolean sendFrame(byte[] f, String label) {
        if (!ApexProtocol.isSendable(f)) {
            ArbsLog.i("거부: 허용되지 않은 프레임(" + label + ")");
            return false;
        }
        try {
            int n = conn.bulkTransfer(epOut, f, f.length, 100);
            if (n != f.length) {
                ArbsLog.i("APEX 송신 이상(" + label + "): 반환 " + n);
                stats.add(ArbsSessionStats.USB_ERRORS, 1);
                return false;
            }
            return true;
        } catch (Exception e) {
            ArbsLog.i("APEX 송신 예외(" + label + "): " + e.getClass().getSimpleName());
            stats.add(ArbsSessionStats.USB_ERRORS, 1);
            return false;
        }
    }

    /** 송신 직후 IN 을 1 ms 만 읽어 버린다(A0 와 같은 방식). 처음 20건만 로그. */
    private void rxPoll() {
        try {
            int n = conn.bulkTransfer(epIn, rxBuf, rxBuf.length, 1);
            if (n > 0) {
                rxTotal++;
                if (rxLogged < MAX_RX_LOG) {
                    rxLogged++;
                    ArbsLog.i("APEX IF2 수신[" + rxLogged + "] len=" + n + " " + ApexProtocol.hex(rxBuf, Math.min(n, 12)));
                }
            }
        } catch (Exception ignored) { }
    }

    private void drainRx() {
        try {
            int k = 0;
            long t0 = SystemClock.uptimeMillis();
            while (k < 8 && SystemClock.uptimeMillis() - t0 < 100) {
                int n = conn.bulkTransfer(epIn, rxBuf, rxBuf.length, 20);
                if (n <= 0) break;
                k++;
                if (rxLogged < MAX_RX_LOG) {
                    rxLogged++;
                    ArbsLog.i("APEX IF2 수신(정지 후)[" + k + "] len=" + n + " " + ApexProtocol.hex(rxBuf, Math.min(n, 12)));
                }
            }
        } catch (Exception ignored) { }
    }
}
