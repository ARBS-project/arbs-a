package com.limelight.arbs;

/**
 * ARBS A1-4: DualSense 트리거 효과 -> APEX 트리거 채널(0x57) 샘플. ASB 기본 경로(Mix 정책, 실시간 샘플)의 Java 이식.
 * 근거(모두 ApexSenseBridge d831575a…, src/dualsense/Apex6TriggerEffect.cpp):
 *  - 디코드 decodeApex6TriggerEffect :237-336  (0x00/0x05 Off, 0x21 Feedback, 0x26 Vibration[주파수 <=30 이면 펄스열], 0x25 Weapon, 0x22 Bow)
 *  - 렌더   renderApex6TriggerEffect :338-495
 *  - 상수   Apex6TriggerEffect.h :30-73
 * 이식하지 않은 것(ASB 에는 있음, ARBS 는 미구현 -> "미지원" 로그): 0x23/0x27 주기 효과, Legacy(0x01/0x02/0x04…) 변환(AdaptiveTriggerTranslation),
 * 무기 프리셋/Impact 모양, 펌웨어 효과 명령. 근거 없는 바이트는 만들지 않는다.
 * 안드로이드 의존성 없음(단위 테스트 대상).
 */
public final class ApexTriggerEffect {
    public enum Type { OFF, FEEDBACK, VIBRATION, WEAPON, BOW, RHYTHM }

    /** 디코드 결과 코드. */
    public static final int OK = 0, UNSUPPORTED = 1, INVALID = 2;

    // Apex6TriggerEffect.h
    static final int MAX_DRIVE = 127;               // kApex6TriggerMaximumDrive
    static final int NATIVE_MAX_DRIVE = 96;         // kApex6TriggerNativeMaximumDrive
    static final double CARRIER_HZ = 85.0;          // kApex6TriggerCarrierFrequency
    static final double BURST_HZ = 95.0;            // kApex6TriggerBurstFrequency
    static final int PULSE_TRAIN_MAX_HZ = 30;
    static final int DETENT_BASE_DRIVE = 56, DETENT_PER_STRENGTH = 8, DETENT_MAX_DRIVE = 100;
    static final int DETENT_HOLD = 14, DETENT_RELEASE = 10;
    static final double DETENT_REARM_MARGIN = 20.0, DETENT_MIN_POSITION = 10.0, CONTINUOUS_RELEASE_POSITION = 8.0;
    static final long DETENT_FEEDBACK_WINDOW_MS = 500;
    static final int PULSE_TRAIN_BASE_DRIVE = 32;
    static final double PULSE_TRAIN_DRIVE_STEP = 19.0;
    static final int BURST_ATTACK = 1, BURST_HOLD = 40, BURST_RELEASE = 10;
    static final int BURST_MIN_DRIVE = 70, BURST_MAX_DRIVE = 127, BURST_FULL_FORCE = 48, FORCE_PER_STRENGTH = 8;
    static final double FEEDBACK_DRIVE_PER_LEVEL = 8.0, VIBRATION_DRIVE_PER_LEVEL = 10.0, BOW_DRIVE_PER_LEVEL = 6.0;
    static final int REARM_MARGIN = 4, RHYTHM_RELEASE_MARGIN = 6;

    public Type type = Type.OFF;
    public final int[] zoneStrengths = new int[10];
    public int startZone, endZone, strength, frequency, snapStrength;
    public boolean detent;
    // Rhythm(펄스열)
    public int rhythmDrive, rhythmPeriod, rhythmFrom, rhythmTo = 255;
    public int pulseAttack = BURST_ATTACK, pulseHold = BURST_HOLD, pulseRelease = BURST_RELEASE;

    public boolean same(ApexTriggerEffect o) {
        if (o == null || type != o.type || startZone != o.startZone || endZone != o.endZone || strength != o.strength
                || frequency != o.frequency || snapStrength != o.snapStrength || detent != o.detent
                || rhythmDrive != o.rhythmDrive || rhythmPeriod != o.rhythmPeriod || rhythmFrom != o.rhythmFrom
                || rhythmTo != o.rhythmTo || pulseHold != o.pulseHold) {
            return false;
        }
        return java.util.Arrays.equals(zoneStrengths, o.zoneStrengths);
    }

    public ApexTriggerEffect copy() {
        ApexTriggerEffect c = new ApexTriggerEffect();
        c.type = type;
        System.arraycopy(zoneStrengths, 0, c.zoneStrengths, 0, 10);
        c.startZone = startZone;
        c.endZone = endZone;
        c.strength = strength;
        c.frequency = frequency;
        c.snapStrength = snapStrength;
        c.detent = detent;
        c.rhythmDrive = rhythmDrive;
        c.rhythmPeriod = rhythmPeriod;
        c.rhythmFrom = rhythmFrom;
        c.rhythmTo = rhythmTo;
        c.pulseAttack = pulseAttack;
        c.pulseHold = pulseHold;
        c.pulseRelease = pulseRelease;
        return c;
    }

    public boolean isOff() { return type == Type.OFF; }

    /** 로그용 한 줄 요약(IP·토큰 없음). */
    public String describe() {
        switch (type) {
            case OFF: return "Off";
            case FEEDBACK: return "Feedback(0x21) zone " + startZone + ".." + endZone + " maxStr " + maxStrength();
            case VIBRATION: return "Vibration(0x26) zone " + startZone + ".." + endZone + " maxStr " + maxStrength() + " " + frequency + "Hz" + (detent ? " detent" : "");
            case WEAPON: return "Weapon(0x25) zone " + startZone + ".." + endZone + " str " + strength;
            case BOW: return "Bow(0x22) zone " + startZone + ".." + endZone + " str " + strength + " snap " + snapStrength;
            default: return "PulseTrain(0x26<=30Hz) period " + rhythmPeriod + "ms drive " + rhythmDrive + " from " + rhythmFrom;
        }
    }

    int maxStrength() {
        int m = 0;
        for (int v : zoneStrengths) m = Math.max(m, v);
        return m;
    }

    // ------------------------------------------------------------------ 디코드

    /** 디코드 결과(핫 경로 아님: 효과 이벤트당 1회). */
    public static final class Decoded {
        public ApexTriggerEffect effect;   // OK 일 때만
        public int code;
    }

    static int positionFor(int zone) {
        return (zone * 256 + 9) / 10;     // Apex6TriggerEffect.cpp:24-26
    }

    /**
     * ASB 의 11바이트 효과 배열: [0]=type, [1..10]=Moonlight 가 주는 10바이트 payload. (대응은 UNVERIFIED: 첫 로그의 Weapon/Feedback
     * 마스크가 정상 해석되는지로 확인한다.)
     */
    public static Decoded decode(int type, byte[] payload) {
        Decoded d = new Decoded();
        int[] b = new int[11];
        b[0] = type & 0xFF;
        if (payload != null) for (int i = 0; i < 10 && i < payload.length; i++) b[1 + i] = payload[i] & 0xFF;
        ApexTriggerEffect r = new ApexTriggerEffect();
        d.effect = r;
        d.code = OK;
        if (b[0] == 0x00 || b[0] == 0x05) return d;
        if (b[0] == 0x21 || b[0] == 0x26 || b[0] == 0x25 || b[0] == 0x22) {
            int mask = b[1] | (b[2] << 8);
            if ((mask & ~0x03FF) != 0) return fail(d, INVALID);
            if (mask == 0 || (b[0] == 0x26 && b[9] == 0)) return d;
            int first = 10, last = 0, active = 0;
            for (int zone = 0; zone < 10; zone++) {
                if ((mask & (1 << zone)) == 0) continue;
                first = Math.min(first, zone);
                last = zone;
                active++;
            }
            r.startZone = first;
            r.endZone = last;
            if (b[0] == 0x25 || b[0] == 0x22) {
                boolean bow = b[0] == 0x22;
                int forcePair = b[3] | (b[4] << 8);
                if (active != 2 || first >= last || (bow && (last > 8 || (forcePair & ~0x3F) != 0))) return fail(d, INVALID);
                r.type = bow ? Type.BOW : Type.WEAPON;
                r.strength = (b[3] & 0x07) + 1;
                if (bow) r.snapStrength = ((forcePair >> 3) & 0x07) + 1;
                return d;
            }
            long packed = (long) b[3] | ((long) b[4] << 8) | ((long) b[5] << 16) | ((long) b[6] << 24);
            for (int zone = 0; zone < 10; zone++) {
                if ((mask & (1 << zone)) != 0) r.zoneStrengths[zone] = (int) ((packed >> (zone * 3)) & 0x07) + 1;
            }
            if (b[0] == 0x26 && b[9] <= PULSE_TRAIN_MAX_HZ) {
                int strongest = r.maxStrength();
                ApexTriggerEffect train = new ApexTriggerEffect();
                train.type = Type.RHYTHM;
                int period = Math.max(1, (int) lround(1000.0 / b[9]));
                int hold = BURST_HOLD;
                int fit = period * 7 / 10;
                int total = BURST_ATTACK + BURST_HOLD + BURST_RELEASE;
                if (total > fit) {
                    int tail = BURST_ATTACK + BURST_RELEASE;
                    hold = fit > tail + 4 ? fit - tail : 4;
                }
                train.pulseHold = hold;
                train.rhythmPeriod = Math.max(period, BURST_ATTACK + hold + BURST_RELEASE);
                train.rhythmDrive = (int) Math.min((long) MAX_DRIVE,
                        lround(PULSE_TRAIN_BASE_DRIVE + PULSE_TRAIN_DRIVE_STEP * (strongest - 1.0)));
                train.rhythmFrom = positionFor(first);
                train.rhythmTo = 255;
                d.effect = train;
                return d;
            }
            r.type = b[0] == 0x21 ? Type.FEEDBACK : Type.VIBRATION;
            r.frequency = b[0] == 0x26 ? b[9] : 0;
            return d;
        }
        // 0x23/0x27(주기 효과), Legacy(0x01/0x02/0x04/…) 등: ASB 는 변환하지만 ARBS 는 이식하지 않음
        return fail(d, UNSUPPORTED);
    }

    private static Decoded fail(Decoded d, int code) {
        d.code = code;
        d.effect = new ApexTriggerEffect();
        return d;
    }

    // ------------------------------------------------------------------ 렌더

    /** 한쪽 트리거의 렌더 상태(Apex6TriggerRenderState, h:168-202). */
    public static final class State {
        double phase, amplitude;
        // burst 모양(attack/hold/release 만, 주파수는 BURST_HZ)
        int burstAttack = BURST_ATTACK, burstHold = BURST_HOLD, burstRelease = BURST_RELEASE;
        public long burstsStarted;
        int trainElapsed;
        int burstElapsed, burstLength;
        int previousPosition, burstDrive;
        boolean hasPreviousPosition, hasAmplitude, armed = true, burstInFrame, trainActive, detentPending;

        public void reset() {
            phase = 0;
            amplitude = 0;
            burstAttack = BURST_ATTACK;
            burstHold = BURST_HOLD;
            burstRelease = BURST_RELEASE;
            burstsStarted = 0;
            trainElapsed = 0;
            burstElapsed = 0;
            burstLength = 0;
            previousPosition = 0;
            burstDrive = 0;
            hasPreviousPosition = hasAmplitude = burstInFrame = trainActive = detentPending = false;
            armed = true;
        }

        /** 효과가 바뀔 때(retarget, h:187-201): 위상·진폭·진행 중 burst·위치 기억은 유지, 나머지는 초기화. */
        public void retarget() {
            double ph = phase, am = amplitude;
            boolean ha = hasAmplitude && am > 0.0;
            int ba = burstAttack, bh = burstHold, br = burstRelease, be = burstElapsed, bl = burstLength, bd = burstDrive;
            long bs = burstsStarted;
            int pp = previousPosition;
            boolean hp = hasPreviousPosition, ar = armed;
            reset();
            phase = ph;
            amplitude = am;
            hasAmplitude = ha;
            burstAttack = ba;
            burstHold = bh;
            burstRelease = br;
            burstElapsed = be;
            burstLength = bl;
            burstDrive = bd;
            burstsStarted = bs;
            previousPosition = pp;
            hasPreviousPosition = hp;
            armed = ar;
        }

        public void setDetentPending(boolean v) { detentPending = v; }

        public boolean burstActive() { return burstInFrame || burstLength != 0; }
    }

    static long lround(double v) {
        return (long) (v < 0 ? Math.ceil(v - 0.5) : Math.floor(v + 0.5));
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static double zoneLevel(int[] strengths, double position) {
        double coordinate = position * (10.0 / 256.0);
        int zone = (int) clamp(coordinate, 0.0, 10 - 0.001);
        double level = strengths[zone];
        if (level == 0.0) return 0.0;
        double offset = coordinate - (zone + 0.5);
        if (offset < 0.0) {
            if (zone == 0) return level;
            double neighbour = strengths[zone - 1];
            double edge = neighbour > 0.0 ? (level + neighbour) / 2.0 : 0.0;
            return edge + (level - edge) * (offset + 0.5) / 0.5;
        }
        if (zone + 1 >= 10) return level;
        double neighbour = strengths[zone + 1];
        double edge = neighbour > 0.0 ? (level + neighbour) / 2.0 : 0.0;
        return level + (edge - level) * offset / 0.5;
    }

    static int burstDriveForForce(int force) {
        if (force == 0) return 0;
        double fraction = clamp((force - (double) FORCE_PER_STRENGTH) / (BURST_FULL_FORCE - FORCE_PER_STRENGTH), 0.0, 1.0);
        return (int) lround(BURST_MIN_DRIVE + (BURST_MAX_DRIVE - BURST_MIN_DRIVE) * fraction);
    }

    /** 기본 모양(세그먼트 없음) 한 샘플: sin(2π f t) * impactLevel (cpp:72-93, 110-125, 꼬리 없음). */
    static double shapeValue(int attack, int hold, int release, double hz, int index) {
        int body = attack + hold + release;
        double level;
        if (index < attack) level = Math.min(1.0, (double) (index + 1) / attack);
        else if (index < attack + hold) level = 1.0;
        else if (index < body) level = 1.0 + (0.0 - 1.0) * ((double) (index - attack - hold + 1) / release);
        else level = 0.0;
        return Math.sin(2.0 * Math.PI * hz * index / 1000.0) * level;
    }

    private static int driveSample(double value) {
        return (int) Math.max(-MAX_DRIVE, Math.min(MAX_DRIVE, lround(value)));
    }

    private static void startBurst(State s, int attack, int hold, int release, int drive) {
        int total = attack + hold + release;
        if (drive == 0 || total == 0) return;
        s.burstAttack = attack;
        s.burstHold = hold;
        s.burstRelease = release;
        s.burstDrive = Math.min(drive, 255);
        s.burstElapsed = 0;
        s.burstLength = Math.min(total, 65535);
        s.burstsStarted++;
    }

    private static boolean crossedBreakPoint(State s, double position, double rearm, double trigger) {
        if (position <= rearm) s.armed = true;
        if (!s.hasPreviousPosition || !s.armed || s.previousPosition >= trigger || position < trigger) return false;
        s.armed = false;
        return true;
    }

    private static int burstSample(State s) {
        int body = s.burstAttack + s.burstHold + s.burstRelease;
        int index = s.burstElapsed;
        double value = shapeValue(s.burstAttack, s.burstHold, s.burstRelease, BURST_HZ, index) * s.burstDrive;
        if (index < body) s.burstInFrame = true;
        s.burstElapsed++;
        if (s.burstElapsed >= s.burstLength) {
            s.burstElapsed = 0;
            s.burstLength = 0;
        }
        return driveSample(value);
    }

    private static int rhythmSample(ApexTriggerEffect e, int elapsed) {
        if (e.rhythmPeriod == 0) return 0;
        int cycle = elapsed % e.rhythmPeriod;
        int total = e.pulseAttack + e.pulseHold + e.pulseRelease;
        double value = 0.0;
        if (cycle < total) value = shapeValue(e.pulseAttack, e.pulseHold, e.pulseRelease, BURST_HZ, cycle) * e.rhythmDrive;
        return driveSample(value);
    }

    private static int sample(State s, double frequency, double amplitude) {
        long value = lround(Math.sin(s.phase * 2.0 * Math.PI) * amplitude);
        s.phase += frequency / 1000.0;
        s.phase -= Math.floor(s.phase);
        return (int) Math.max(-MAX_DRIVE, Math.min(MAX_DRIVE, value));
    }

    /**
     * 8 샘플(1 kHz, 8 ms) 렌더. [position] = 트리거 아날로그 0..255, [sampleOffset] = 연속 효과 위상 기준(누적 샘플 수, 음수면 사용 안 함).
     * [out] 길이 8. renderApex6TriggerEffect(cpp:338-495)와 같은 순서.
     */
    public static void render(ApexTriggerEffect e, int position, State s, long sampleOffset, int[] out) {
        double amplitude = 0.0;
        double frequency = CARRIER_HZ;
        boolean continuous = false;
        s.burstInFrame = false;
        double current = position;
        switch (e.type) {
            case OFF:
                break;
            case FEEDBACK:
                amplitude = FEEDBACK_DRIVE_PER_LEVEL * zoneLevelPositive(e.zoneStrengths, current);
                continuous = true;
                break;
            case VIBRATION: {
                continuous = true;
                if (e.detent) {
                    double detentStart = Math.max(DETENT_MIN_POSITION, positionFor(e.startZone));
                    boolean installedWhilePressed = s.detentPending && current >= DETENT_MIN_POSITION;
                    if (installedWhilePressed) s.detentPending = false;
                    if (crossedBreakPoint(s, current, Math.max(0.0, detentStart - DETENT_REARM_MARGIN), detentStart)
                            || installedWhilePressed) {
                        int strongest = e.maxStrength();
                        startBurst(s, BURST_ATTACK, DETENT_HOLD, DETENT_RELEASE,
                                Math.min(DETENT_MAX_DRIVE, DETENT_BASE_DRIVE + DETENT_PER_STRENGTH * strongest));
                    }
                }
                if (e.frequency == 0) break;
                amplitude = VIBRATION_DRIVE_PER_LEVEL * zoneLevelPositive(e.zoneStrengths, current);
                frequency = e.frequency;
                break;
            }
            case WEAPON: {
                double start = positionFor(e.startZone), end = positionFor(e.endZone);
                if (crossedBreakPoint(s, current, start + REARM_MARGIN, end)) {
                    startBurst(s, BURST_ATTACK, BURST_HOLD, BURST_RELEASE, burstDriveForForce(e.strength * FORCE_PER_STRENGTH));
                }
                break;
            }
            case BOW: {
                double start = positionFor(e.startZone), end = positionFor(e.endZone);
                if (crossedBreakPoint(s, current, start + REARM_MARGIN, end)) {
                    startBurst(s, BURST_ATTACK, BURST_HOLD, BURST_RELEASE, burstDriveForForce(e.snapStrength * FORCE_PER_STRENGTH));
                }
                if (s.armed && current < end && end > start) {
                    double draw = clamp((current - start) / (end - start), 0.0, 1.0);
                    amplitude = e.strength * BOW_DRIVE_PER_LEVEL * draw;
                }
                break;
            }
            default: {   // RHYTHM
                double from = e.rhythmFrom, to = e.rhythmTo;
                boolean inside = current >= from && current <= to;
                boolean staying = current + RHYTHM_RELEASE_MARGIN >= from && current <= to + RHYTHM_RELEASE_MARGIN;
                if (!s.trainActive && inside) {
                    s.trainActive = true;
                    s.trainElapsed = 0;
                } else if (s.trainActive && !staying) {
                    s.trainActive = false;
                }
                break;
            }
        }
        s.previousPosition = position;
        s.hasPreviousPosition = true;
        if (continuous && sampleOffset >= 0) {
            s.phase = ((sampleOffset % 1000L) * frequency / 1000.0) % 1.0;
        }
        if (continuous && current < CONTINUOUS_RELEASE_POSITION) amplitude = 0.0;
        double target = (!Double.isNaN(amplitude) && !Double.isInfinite(amplitude)) ? clamp(amplitude, 0.0, NATIVE_MAX_DRIVE) : 0.0;
        double rampStart = s.hasAmplitude ? s.amplitude : target;
        for (int i = 0; i < 8; i++) {
            if (s.burstLength != 0) {
                out[i] = burstSample(s);
                rampStart = 0.0;
                continue;
            }
            if (e.type == Type.RHYTHM && s.trainActive) {
                out[i] = rhythmSample(e, s.trainElapsed++);
                rampStart = 0.0;
                continue;
            }
            double a = rampStart + (target - rampStart) * (double) (i + 1) / 8.0;
            out[i] = sample(s, frequency, a);
        }
        s.amplitude = s.burstInFrame ? 0.0 : target;
        s.hasAmplitude = true;
    }

    /** levelCurve(level) = level^1 (kApex6TriggerLevelExponent=1) 이므로 level>0 이면 그대로. */
    private static double zoneLevelPositive(int[] strengths, double position) {
        double l = zoneLevel(strengths, position);
        return l > 0.0 ? l : 0.0;
    }

    // ------------------------------------------------------------------ 세기 / 상한 (EffectStrength.h, Apex6HapticShaping.h)

    /** scaleEffectStrength: int8 * percent / 100 (C++ 정수 나눗셈 = 0 방향 절삭). */
    public static int scaleStrength(int sample, int percent) {
        return sample * Math.min(percent, 100) / 100;
    }

    /** limitApex6TriggerSample(Apex6HapticShaping.h:31-45). ceilingPercent 20..100. */
    public static int limitCeiling(int sample, int ceilingPercent) {
        int percent = Math.max(20, Math.min(100, ceilingPercent));
        if (percent >= 100 || sample == 0) return sample;
        double ceiling = 127.0 * percent / 100.0;
        double knee = ceiling * 0.5;
        double magnitude = Math.abs(sample);
        if (magnitude <= knee) return sample;
        double excess = (magnitude - knee) / (ceiling - knee);
        double limited = knee + (ceiling - knee) * excess / (1.0 + excess);
        int rounded = Math.min((int) (limited + 0.5), (int) (ceiling + 0.5));
        return sample < 0 ? -rounded : rounded;
    }
}
