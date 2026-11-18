package com.limelight.arbs;

import java.util.Locale;

/**
 * ARBS A1-2: APEX 6 Pro IF2 명령 프레임(안드로이드 의존성 없음, 단위 테스트 대상). A0 ApexMotor/ApexInfo(Kotlin)를 이식한 것.
 * 모든 바이트는 ApexSenseBridge d831575a0dce8cd9faf899ee476977f0d314c336 에서 확인된 것이다:
 *  - 프레임 구조/체크섬 = 인덱스 2..30 합                 src/flydigi/Apex6Protocol.cpp:33-54
 *  - 그립 실시간 활성 0x53 `01 12 02 40 00`               Apex6Protocol.cpp:101,104
 *  - 비활성 0x53 `01 02 00`, `01 12 00`                   Apex6Protocol.cpp:148-153
 *  - 0x57 payload: [0]=0x80(보간)|0x10(우 그립)|0x08(좌 그립)|route(Mute=3) = 0x9B,
 *    서브프레임 8x(트리거,좌그립,우그립), 인코딩 int8+128, 트리거 채널 0x80   Apex6Protocol.cpp:155-175, .h:40-45
 *  - 중립 프레임/정지 순서(중립 x3 -> 비활성 2건)           Apex5Device.cpp:741-767
 * 이 클래스가 만드는 프레임은 GetInfo(0x01), 0x53(그립 활성·비활성 3종), 0x57(그립 전용 0x9B) 뿐이다.
 * 펌웨어 효과, 0x11·0x12, 설정·저장 명령, 트리거 채널 구동은 만들 수 없다.
 */
public final class ApexProtocol {
    private ApexProtocol() { }

    public static final int FRAME_SIZE = 32;
    public static final int MAGIC0 = 0x5A;
    public static final int MAGIC1 = 0xA5;
    public static final int CMD_GET_INFO = 0x01;
    public static final int CMD_MOTOR_ROUTE = 0x53;
    public static final int CMD_REALTIME = 0x57;
    public static final int ROUTE_MUTE = 3;
    /** 하드 상한(int8 ±127, ASB와 동일). 사용자 승인: EA-APR-04(A1-3e)로 60 -> 127. 설정 기본값은 40. */
    public static final int MAX_AMPLITUDE = 127;
    public static final int SUBFRAMES = 8;
    public static final int FRAME_MS = 8;
    public static final int ENABLE_WAIT_MS = 94;

    public static int checksum(byte[] f) {
        int sum = 0;
        for (int i = 2; i <= 30; i++) sum += f[i] & 0xFF;
        return sum & 0xFF;
    }

    private static byte[] cmd(int command, int[] payload) {
        if (command != CMD_GET_INFO && command != CMD_MOTOR_ROUTE && command != CMD_REALTIME) {
            throw new IllegalArgumentException("허용되지 않은 명령");
        }
        if (payload.length > 27) throw new IllegalArgumentException("payload 길이");
        byte[] f = new byte[FRAME_SIZE];
        f[0] = (byte) MAGIC0;
        f[1] = (byte) MAGIC1;
        f[2] = (byte) command;
        f[3] = (byte) (payload.length + 2);
        for (int i = 0; i < payload.length; i++) f[4 + i] = (byte) payload[i];
        f[31] = (byte) checksum(f);
        return f;
    }

    /** GetInfo 요청 `5A A5 01 02 00...00 <체크섬>`. */
    public static byte[] buildGetInfo() {
        return cmd(CMD_GET_INFO, new int[0]);
    }

    /** 0x53 `01 12 02 40 00` - 그립 실시간 활성. */
    public static byte[] gripRealtimeEnable() {
        return cmd(CMD_MOTOR_ROUTE, new int[]{0x01, 0x12, 0x02, 0x40, 0x00});
    }

    /** 0x53 `01 02 00`, `01 12 00` - ASB buildMotorDisable 순서. */
    public static byte[][] disablePair() {
        return new byte[][]{
                cmd(CMD_MOTOR_ROUTE, new int[]{0x01, 0x02, 0x00}),
                cmd(CMD_MOTOR_ROUTE, new int[]{0x01, 0x12, 0x00}),
        };
    }

    /** 0x57 그립 전용 프레임: 서브프레임 8개 signed 값(int8 +128), 한 값이라도 +-{@link #MAX_AMPLITUDE}(127) 를 넘으면 예외. */
    public static byte[] gripFrame(int[] left, int[] right) {
        if (left.length != SUBFRAMES || right.length != SUBFRAMES) throw new IllegalArgumentException("서브프레임은 8개");
        int[] p = new int[25];
        p[0] = 0x80 | 0x10 | 0x08 | ROUTE_MUTE;
        for (int i = 0; i < SUBFRAMES; i++) {
            if (Math.abs(left[i]) > MAX_AMPLITUDE || Math.abs(right[i]) > MAX_AMPLITUDE) {
                throw new IllegalArgumentException("진폭 상한 초과");
            }
            p[1 + i * 3] = 0x80;               // 트리거 채널 off
            p[2 + i * 3] = left[i] + 128;
            p[3 + i * 3] = right[i] + 128;
        }
        return cmd(CMD_REALTIME, p);
    }

    public static byte[] neutralFrame() {
        return gripFrame(new int[SUBFRAMES], new int[SUBFRAMES]);
    }

    /** OUT 으로 보낼 수 있는 프레임인지(32바이트, 0x53 또는 0x57) 다시 검사. GetInfo 는 별도 경로. */
    public static boolean isSendable(byte[] f) {
        if (f == null || f.length != FRAME_SIZE) return false;
        int c = f[2] & 0xFF;
        return c == CMD_MOTOR_ROUTE || c == CMD_REALTIME;
    }

    public static final class Info {
        public final int deviceType, connectionMode, battery, features;
        public final boolean checksumOk;

        Info(int deviceType, int connectionMode, int battery, int features, boolean checksumOk) {
            this.deviceType = deviceType;
            this.connectionMode = connectionMode;
            this.battery = battery;
            this.features = features;
            this.checksumOk = checksumOk;
        }

        public boolean isPro() { return deviceType == 0x96 || deviceType == 0x98; }
        public boolean hasGripHaptics() { return (features & 0x80) != 0; }

        public String describe() {
            return String.format(Locale.US, "DeviceType=0x%02X Pro=%s connectionMode=0x%02X 배터리=%d features=0x%02X 그립햅틱(0x80)=%s 체크섬=%s",
                    deviceType, isPro() ? "예" : "아니오", connectionMode, battery, features,
                    hasGripHaptics() ? "예" : "아니오", checksumOk ? "일치" : "불일치");
        }
    }

    /** 수신 원문에서 GetInfo 응답을 찾아 해석. 아니면 null. 선두 0x00 리포트 ID 허용. */
    public static Info parse(byte[] raw, int len) {
        int off;
        if (len >= 33 && raw[0] == 0 && (raw[1] & 0xFF) == MAGIC0) off = 1;
        else if (len > 0 && (raw[0] & 0xFF) == MAGIC0) off = 0;
        else return null;
        if (len < off + FRAME_SIZE) return null;
        byte[] f = new byte[FRAME_SIZE];
        System.arraycopy(raw, off, f, 0, FRAME_SIZE);
        if ((f[1] & 0xFF) != MAGIC1 || f[2] != CMD_GET_INFO) return null;
        return new Info(f[5] & 0xFF, f[6] & 0xFF, (f[11] & 0xFF) & 0x0F, f[29] & 0xFF, (f[31] & 0xFF) == checksum(f));
    }

    public static String hex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }
}
