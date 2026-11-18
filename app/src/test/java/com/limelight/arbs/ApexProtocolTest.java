package com.limelight.arbs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/** ARBS A1-2: IF2 프레임 단위 테스트. 바이트 근거는 ApexProtocol 클래스 주석(ASB d831575) 참조. */
public class ApexProtocolTest {

    private static void assertFrame(byte[] f, int cmd, int... payload) {
        assertEquals(32, f.length);
        assertEquals(0x5A, f[0] & 0xFF);
        assertEquals(0xA5, f[1] & 0xFF);
        assertEquals(cmd, f[2] & 0xFF);
        assertEquals(payload.length + 2, f[3] & 0xFF);
        for (int i = 0; i < payload.length; i++) assertEquals("payload[" + i + "]", payload[i], f[4 + i] & 0xFF);
        assertEquals(ApexProtocol.checksum(f), f[31] & 0xFF);
    }

    @Test
    public void gripEnableMatchesAsb() {
        byte[] f = ApexProtocol.gripRealtimeEnable();
        assertFrame(f, 0x53, 0x01, 0x12, 0x02, 0x40, 0x00);
        assertEquals(0xAF, f[31] & 0xFF);
    }

    @Test
    public void disablePairMatchesAsb() {
        byte[][] d = ApexProtocol.disablePair();
        assertEquals(2, d.length);
        assertFrame(d[0], 0x53, 0x01, 0x02, 0x00);
        assertFrame(d[1], 0x53, 0x01, 0x12, 0x00);
    }

    @Test
    public void getInfoFrame() {
        byte[] f = ApexProtocol.buildGetInfo();
        assertFrame(f, 0x01);
        assertEquals(0x03, f[31] & 0xFF);       // 0x01 + 0x02
    }

    @Test
    public void neutralFrameIsGripOnly0x9B() {
        byte[] f = ApexProtocol.neutralFrame();
        assertEquals(0x9B, f[4] & 0xFF);
        for (int i = 0; i < 8; i++) {
            assertEquals(0x80, f[5 + i * 3] & 0xFF);   // 트리거 채널 off
            assertEquals(0x80, f[6 + i * 3] & 0xFF);   // 좌 0
            assertEquals(0x80, f[7 + i * 3] & 0xFF);   // 우 0
        }
        assertEquals(27, f[3] & 0xFF);
        assertEquals(ApexProtocol.checksum(f), f[31] & 0xFF);
    }

    @Test
    public void gripFrameEncodesLeftRightAndKeepsTriggerOff() {
        int[] l = {1, -1, 60, -60, 0, 5, -5, 30};
        int[] r = {-2, 2, -60, 60, 0, -5, 5, -30};
        byte[] f = ApexProtocol.gripFrame(l, r);
        assertEquals(0x9B, f[4] & 0xFF);
        for (int i = 0; i < 8; i++) {
            assertEquals(0x80, f[5 + i * 3] & 0xFF);
            assertEquals(l[i] + 128, f[6 + i * 3] & 0xFF);
            assertEquals(r[i] + 128, f[7 + i * 3] & 0xFF);
        }
    }

    @Test
    public void amplitudeAbove60IsRejected() {
        int[] ok = new int[8];
        int[] bad = new int[8];
        bad[3] = 61;
        try {
            ApexProtocol.gripFrame(bad, ok);
            fail("61 must be rejected");
        } catch (IllegalArgumentException expected) { }
        bad[3] = -61;
        try {
            ApexProtocol.gripFrame(ok, bad);
            fail("-61 must be rejected");
        } catch (IllegalArgumentException expected) { }
        bad[3] = 60;
        ApexProtocol.gripFrame(bad, bad);
        bad[3] = -60;
        ApexProtocol.gripFrame(bad, bad);
    }

    @Test
    public void everySendableFrameIsOnly0x53Or0x57AndNeverAFirmwareEffect() {
        assertTrue(ApexProtocol.isSendable(ApexProtocol.gripRealtimeEnable()));
        assertTrue(ApexProtocol.isSendable(ApexProtocol.neutralFrame()));
        for (byte[] d : ApexProtocol.disablePair()) assertTrue(ApexProtocol.isSendable(d));
        assertFalse("GetInfo goes over a separate path", ApexProtocol.isSendable(ApexProtocol.buildGetInfo()));
        // 0x53 은 payload 앞 3바이트가 {01 12 02}, {01 02 00}, {01 12 00} 중 하나여야 한다(펌웨어 효과 `01 <t> 04` 금지).
        byte[] en = ApexProtocol.gripRealtimeEnable();
        assertTrue(en[4] == 0x01 && en[5] == 0x12 && en[6] == 0x02);
        for (byte[] d : ApexProtocol.disablePair()) assertTrue(d[6] == 0x00);
        byte[] fake = new byte[31];
        assertFalse(ApexProtocol.isSendable(fake));
        byte[] unknown = new byte[32];
        unknown[2] = 0x11;
        assertFalse(ApexProtocol.isSendable(unknown));
        unknown[2] = 0x12;
        assertFalse(ApexProtocol.isSendable(unknown));
    }

    @Test
    public void parseGetInfoResponse() {
        byte[] f = new byte[32];
        f[0] = 0x5A;
        f[1] = (byte) 0xA5;
        f[2] = 0x01;
        f[3] = 0x1E;
        f[5] = (byte) 0x96;      // DeviceType
        f[6] = 0x02;
        f[11] = 0x08;
        f[29] = (byte) 0x90;     // features: 0x80 그립 | 0x10 트리거
        f[31] = (byte) ApexProtocol.checksum(f);
        ApexProtocol.Info info = ApexProtocol.parse(f, 32);
        assertNotNull(info);
        assertTrue(info.checksumOk);
        assertTrue(info.isPro());
        assertTrue(info.hasGripHaptics());
        // 리포트 ID 0x00 이 앞에 붙은 33바이트도 허용
        byte[] with = new byte[33];
        System.arraycopy(f, 0, with, 1, 32);
        assertNotNull(ApexProtocol.parse(with, 33));
        // 체크섬이 틀리면 checksumOk=false
        f[31] ^= 0x55;
        assertFalse(ApexProtocol.parse(f, 32).checksumOk);
        // non-Pro / 그립 햅틱 없음
        f[31] = (byte) 0;
        f[5] = (byte) 0x95;
        f[29] = 0x10;
        f[31] = (byte) ApexProtocol.checksum(f);
        ApexProtocol.Info np = ApexProtocol.parse(f, 32);
        assertFalse(np.isPro());
        assertFalse(np.hasGripHaptics());
        // 다른 명령 응답은 null
        byte[] other = f.clone();
        other[2] = 0x53;
        assertNull(ApexProtocol.parse(other, 32));
        assertNull(ApexProtocol.parse(new byte[]{1, 2, 3}, 3));
        assertArrayEquals(new byte[]{0x5A}, new byte[]{(byte) ApexProtocol.MAGIC0});
    }
}
