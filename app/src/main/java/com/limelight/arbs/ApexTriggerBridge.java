package com.limelight.arbs;

/**
 * ARBS A1-4: 트리거 효과 수신 -> 8 ms 블록 트리거 샘플 + route 결정. ASB Apex6HapticBridge 의 Mix 정책 경로 이식.
 * 근거(ApexSenseBridge d831575a…, src/dualsense/Apex6HapticBridge.cpp):
 *  - 효과 수신/중복 제거/해제 지연  handleTrigger :616-685 (Off 는 100 ms 후 해제 :22,666-671)
 *  - 효과 변경 기록(새 효과 판정)   noteTriggerChangeLocked :745-756
 *  - 블록 렌더(세기 % -> 상한 -> 활성 -> 중재 -> route/샘플)  renderBlock :782-1049 (트리거 부분 :853-884, :973-978, :991-1048)
 *  - 중재(Mix)                       Apex6DualArbiter::next :253-352, 상수 Apex6HapticBridge.h:89-98
 * 펌웨어 정책(0x53 sub 0x04)·Priority 정책은 이식하지 않았다(ASB 기본값은 Mix, h:70).
 * ARBS 안전 규칙(A1-4 지시): 같은 쪽 효과 이벤트가 [EFFECT_TIMEOUT_MS](3 s) 동안 없으면 해제한다 - ASB 에는 이 규칙이 없다(근거 없음 -> 지시의 기본값).
 * 호출 스레드가 여럿(수신 콜백, 입력 읽기, 엔진)이라 공개 메서드는 모두 synchronized 이다. 안드로이드 의존성 없음.
 */
public final class ApexTriggerBridge {
    public static final long EFFECT_TIMEOUT_MS = 3000;
    static final long CLEAR_HOLD_MS = 100;            // kTriggerClearHold (cpp:22)
    static final int ENTRY_WINDOW = 3, YIELD_SILENT = 8, OWNER_HOLD = 32, MIX_RELEASE = 63, MIX_PEAK = 127;   // Apex6HapticBridge.h:89-94
    static final int L = ApexProtocol.ROUTE_LEFT, R = ApexProtocol.ROUTE_RIGHT, BOTH = ApexProtocol.ROUTE_BOTH, MUTE = ApexProtocol.ROUTE_MUTE;

    private final ApexTriggerEffect[] eff = {new ApexTriggerEffect(), new ApexTriggerEffect()};
    private final ApexTriggerEffect[] last = {new ApexTriggerEffect(), new ApexTriggerEffect()};
    private final ApexTriggerEffect.State[] st = {new ApexTriggerEffect.State(), new ApexTriggerEffect.State()};
    private final int[] pos = new int[2];
    private final boolean[] pendingClear = new boolean[2];
    private final long[] pendingClearAt = new long[2];
    private final long[] lastEventNs = new long[2];
    private final boolean[] hasEvent = new boolean[2];
    private final boolean[] feedbackSeen = new boolean[2];
    private final long[] feedbackSeenAt = new long[2];
    private final long[] gen = new long[2], genSeen = new long[2];
    private long sampleOffset;
    private int strengthPercent = 100;
    private int ceilingPercent = 100;

    // 중재 상태
    private long leftSeen, rightSeen, leftSince, rightSince, lastChange, dualBlock;
    private int owner = MUTE, preempted = MUTE;
    private boolean episode;

    // 통계(1초 로그용)
    public long updatesL, updatesR, activeUpdates, stops, unsupported, malformed, dedup, timeouts, weaponBreaks, bowBreaks;
    public long limitedSamples, framesL, framesR, framesBoth, mixFrames;
    public int lastTypeL, lastTypeR;

    public synchronized void setStrengthPercent(int p) { strengthPercent = Math.max(0, Math.min(100, p)); }

    public synchronized void setCeilingPercent(int p) { ceilingPercent = Math.max(20, Math.min(100, p)); }

    public synchronized int getStrengthPercent() { return strengthPercent; }

    public synchronized void setPosition(int left, int right) {
        pos[0] = left & 0xFF;
        pos[1] = right & 0xFF;
    }

    /** 로그용 누계 스냅샷: [0]=수신L [1]=수신R [2]=적용 [3]=해제 [4]=미지원 [5]=형식오류 [6]=중복 [7]=타임아웃 [8]=weapon break [9]=bow break
     *  [10]=상한제한 샘플 [11..14]=프레임 L/R/Both/Mix [15,16]=마지막 type L/R. */
    public synchronized long[] stats() {
        return new long[]{updatesL, updatesR, activeUpdates, stops, unsupported, malformed, dedup, timeouts, weaponBreaks, bowBreaks,
                limitedSamples, framesL, framesR, framesBoth, mixFrames, lastTypeL, lastTypeR};
    }

    public synchronized boolean anyEffect() { return !eff[0].isOff() || !eff[1].isOff() || pendingClear[0] || pendingClear[1]; }

    /** 정지/세션 종료: 효과·상태·중재를 모두 초기화(ASB stop() cpp:404-440 과 같은 항목). 통계는 유지. */
    public synchronized void clear() {
        for (int i = 0; i < 2; i++) {
            eff[i] = new ApexTriggerEffect();
            last[i] = new ApexTriggerEffect();
            st[i].reset();
            pendingClear[i] = false;
            feedbackSeen[i] = false;
            lastEventNs[i] = 0;
            hasEvent[i] = false;
            gen[i] = genSeen[i] = 0;
        }
        sampleOffset = 0;
        leftSeen = rightSeen = leftSince = rightSince = lastChange = dualBlock = 0;
        owner = preempted = MUTE;
        episode = false;
    }

    /** 수신 효과 1건 처리(handleTrigger). 반환: 로그용 요약(변경/무시/해제 등). null 이면 로그 불필요(중복). */
    public synchronized String onEffect(boolean left, int type, byte[] payload, long nowNs) {
        int s = left ? 0 : 1;
        if (left) {
            updatesL++;
            lastTypeL = type & 0xFF;
        } else {
            updatesR++;
            lastTypeR = type & 0xFF;
        }
        ApexTriggerEffect.Decoded dec = ApexTriggerEffect.decode(type, payload);
        ApexTriggerEffect next = dec.effect;
        if (dec.code == ApexTriggerEffect.OK && next.type == ApexTriggerEffect.Type.FEEDBACK) {
            feedbackSeen[s] = true;
            feedbackSeenAt[s] = nowNs;
        } else if (dec.code == ApexTriggerEffect.OK && next.type == ApexTriggerEffect.Type.VIBRATION) {
            if (feedbackSeen[s] && nowNs - feedbackSeenAt[s] < ApexTriggerEffect.DETENT_FEEDBACK_WINDOW_MS * 1_000_000L) next.detent = true;
        }
        String side = left ? "L" : "R";
        if (dec.code != ApexTriggerEffect.OK) {
            if (dec.code == ApexTriggerEffect.UNSUPPORTED) unsupported++;
            else malformed++;
        }
        lastEventNs[s] = nowNs;      // 3 s 무갱신 타이머: 같은 쪽의 모든 이벤트(중복 포함)가 갱신
        hasEvent[s] = true;
        ApexTriggerEffect target = eff[s];
        if (dec.code != ApexTriggerEffect.OK) {
            // 미지원/형식 오류: ASB 와 같이 Off 로 처리(즉시 해제). 증거를 위해 매번 로그.
            eff[s] = new ApexTriggerEffect();
            pendingClear[s] = false;
            noteChange(s);
            st[s].retarget();
            return "트리거 효과 " + (dec.code == ApexTriggerEffect.UNSUPPORTED ? "미지원(ARBS 미이식)" : "형식 오류") + "(" + side + ", type=0x"
                    + String.format("%02X", type & 0xFF) + ") -> Off 처리";
        }
        if (next.isOff() && !target.isOff()) {
            pendingClear[s] = true;
            pendingClearAt[s] = nowNs + CLEAR_HOLD_MS * 1_000_000L;
            stops++;
            return "트리거 효과 해제 예약(" + side + ", " + CLEAR_HOLD_MS + "ms 후)";
        }
        pendingClear[s] = false;
        if (next.same(target)) {
            dedup++;
            return null;
        }
        eff[s] = next;
        noteChange(s);
        st[s].retarget();
        st[s].setDetentPending(next.detent);
        if (next.isOff()) stops++; else activeUpdates++;
        return "트리거 효과 적용(" + side + "): " + next.describe();
    }

    private void noteChange(int s) {
        ApexTriggerEffect e = eff[s];
        if (e.isOff()) return;
        if (e.same(last[s])) return;
        if (e.type != last[s].type) gen[s]++;
        last[s] = e.copy();
    }

    /** 렌더 결과. */
    public static final class Out {
        /** 이번 블록에 트리거 채널을 쓸지. */
        public boolean enabled;
        /** ApexProtocol.ROUTE_* (enabled 일 때만 의미). */
        public int route = MUTE;
        public final int[] samples = new int[8];
        public String timeoutNote;   // 타임아웃 해제가 일어났다면 로그 문구(1회성)
    }

    /**
     * 8 ms 블록 1개 렌더(renderBlock 의 트리거 부분). 타임아웃/해제 대기 처리 후 효과 렌더, 세기·상한 적용, 중재.
     */
    public synchronized void render(long nowNs, Out out) {
        out.enabled = false;
        out.route = MUTE;
        out.timeoutNote = null;
        java.util.Arrays.fill(out.samples, 0);
        for (int s = 0; s < 2; s++) {
            if (pendingClear[s] && nowNs >= pendingClearAt[s]) {
                pendingClear[s] = false;
                eff[s] = new ApexTriggerEffect();
                noteChange(s);
                st[s].retarget();
            }
            if (!eff[s].isOff() && hasEvent[s] && nowNs - lastEventNs[s] > EFFECT_TIMEOUT_MS * 1_000_000L) {
                eff[s] = new ApexTriggerEffect();
                noteChange(s);
                st[s].retarget();
                timeouts++;
                out.timeoutNote = "트리거 효과 3초 무갱신 -> 해제(" + (s == 0 ? "L" : "R") + ")";
            }
        }
        int[][] r = new int[2][8];
        long bl = st[0].burstsStarted, br = st[1].burstsStarted;
        ApexTriggerEffect.render(eff[0], pos[0], st[0], sampleOffset, r[0]);
        ApexTriggerEffect.render(eff[1], pos[1], st[1], sampleOffset, r[1]);
        sampleOffset += 8;
        boolean leftBurstFrame = st[0].burstActive(), rightBurstFrame = st[1].burstActive();
        if (st[0].burstsStarted > bl) { if (eff[0].type == ApexTriggerEffect.Type.BOW) bowBreaks++; else weaponBreaks++; }
        if (st[1].burstsStarted > br) { if (eff[1].type == ApexTriggerEffect.Type.BOW) bowBreaks++; else weaponBreaks++; }
        boolean newL = genSeen[0] != gen[0], newR = genSeen[1] != gen[1];
        genSeen[0] = gen[0];
        genSeen[1] = gen[1];
        boolean leftActive = false, rightActive = false, identical = true;
        for (int i = 0; i < 8; i++) {
            int a = ApexTriggerEffect.scaleStrength(r[0][i], strengthPercent);
            int b = ApexTriggerEffect.scaleStrength(r[1][i], strengthPercent);
            int la = ApexTriggerEffect.limitCeiling(a, ceilingPercent);
            int lb = ApexTriggerEffect.limitCeiling(b, ceilingPercent);
            if (la != a) limitedSamples++;
            if (lb != b) limitedSamples++;
            r[0][i] = la;
            r[1][i] = lb;
            if (la != 0) leftActive = true;
            if (lb != 0) rightActive = true;
            if (la != lb) identical = false;
        }
        identical = leftActive && rightActive && identical;
        // ---- 중재(Mix)
        long stamp = ++dualBlock;
        boolean leftBurst = leftBurstFrame && leftActive, rightBurst = rightBurstFrame && rightActive;
        if (leftActive) {
            if (since(leftSeen, stamp) > ENTRY_WINDOW) leftSince = stamp;
            leftSeen = stamp;
        }
        if (rightActive) {
            if (since(rightSeen, stamp) > ENTRY_WINDOW) rightSince = stamp;
            rightSeen = stamp;
        }
        if (episode && (identical || since(leftSeen, stamp) > MIX_RELEASE || since(rightSeen, stamp) > MIX_RELEASE)) {
            episode = false;
            preempted = MUTE;
        }
        if (!episode) {
            if (!identical && (leftActive || rightActive) && since(leftSeen, stamp) <= ENTRY_WINDOW && since(rightSeen, stamp) <= ENTRY_WINDOW) {
                episode = true;
                int chosen = leftSince > rightSince ? L : R;
                boolean held = (owner == L || owner == R) && (owner == L ? leftActive : rightActive) && since(lastChange, stamp) < OWNER_HOLD;
                if (leftBurst != rightBurst) {
                    chosen = leftBurst ? L : R;
                    preempted = chosen == L ? R : L;
                } else if (newL != newR) {
                    chosen = newL ? L : R;
                } else if (held) {
                    chosen = owner;
                }
                changeOwner(chosen, stamp);
            }
        } else {
            int other = owner == L ? R : L;
            boolean ownerBurst = owner == L ? leftBurst : rightBurst;
            boolean otherBurst = other == L ? leftBurst : rightBurst;
            boolean otherActive = other == L ? leftActive : rightActive;
            boolean otherNew = other == L ? newL : newR;
            long ownerSeen = owner == L ? since(leftSeen, stamp) : since(rightSeen, stamp);
            if (otherBurst && otherActive && !ownerBurst) {
                if (preempted == MUTE) preempted = owner;
                changeOwner(other, stamp);
            } else if (preempted != MUTE && !ownerBurst) {
                int back = preempted;
                preempted = MUTE;
                changeOwner(back, stamp);
            } else if (otherNew && otherActive && !ownerBurst) {
                changeOwner(other, stamp);
            } else if (!ownerBurst && otherActive && ownerSeen > YIELD_SILENT && since(lastChange, stamp) >= OWNER_HOLD) {
                changeOwner(other, stamp);
            }
        }
        int route = MUTE;
        if (episode || identical) route = BOTH;
        else if (rightActive && !leftActive) route = R;
        else if (leftActive) route = L;
        out.enabled = leftActive || rightActive;
        if (!out.enabled) return;
        if (episode && !identical) {
            // mix: Both 경로에 두 쪽 합(±127 클램프)
            route = BOTH;
            for (int i = 0; i < 8; i++) out.samples[i] = Math.max(-MIX_PEAK, Math.min(MIX_PEAK, r[0][i] + r[1][i]));
            mixFrames++;
        } else if (route == BOTH) {
            System.arraycopy(r[0], 0, out.samples, 0, 8);
            framesBoth++;
        } else {
            System.arraycopy(route == R ? r[1] : r[0], 0, out.samples, 0, 8);
            if (route == R) framesR++; else framesL++;
        }
        out.route = route;
    }

    private void changeOwner(int side, long stamp) {
        if (owner != side) {
            owner = side;
            lastChange = stamp;
        }
    }

    private static long since(long seen, long stamp) {
        return seen == 0 || seen > stamp ? Long.MAX_VALUE : stamp - seen;
    }
}
