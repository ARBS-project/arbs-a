package com.limelight.binding.input.driver;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;

import com.limelight.arbs.ApexHaptics;
import com.limelight.arbs.ApexProtocol;
import com.limelight.arbs.ArbsLog;
import com.limelight.nvstream.jni.MoonBridge;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * ARBS A1-2: Flydigi APEX 6 dongle (VID 0x37D7 / PID 0x2502) as a PlayStation-type controller with PCM haptics.
 *  - claims IF0 (input; force=false first, then force=true if the kernel driver holds it) and IF2 (haptic commands; force=true) only.
 *    IF1/IF3 are never touched.
 *  - input: IF0 IN 0x81 is parsed by the existing Xbox 360 parser (layout unverified for this device: the first 3 distinct raw
 *    reports are written to the ARBS log).
 *  - output: NOTHING is ever written to IF0 OUT (no LED command, no rumble). rumbleTriggers() only logs;
 *    rumble() (A1-3) and PCM haptics go through {@link ApexHaptics} (IF2, grip only, amplitude <= 60/127).
 */
public class ApexController extends Xbox360Controller {
    public static final int VID = 0x37D7;
    public static final int PID = 0x2502;

    /** ARBS A1-2b: input diagnostic log toggle (settings; default off). */
    private static volatile boolean inputDiag;

    public static void setInputDiag(boolean on) { inputDiag = on; }

    public static boolean isInputDiag() { return inputDiag; }

    private long diagWindowMs;
    private int diagLines;
    private byte[] diagLastRaw;
    private volatile boolean diagArmed;

    /** True once after a handleRead() that logged its raw/parsed line; ControllerHandler then logs the values it sent to the host. */
    public boolean consumeDiagArm() {
        boolean a = diagArmed;
        diagArmed = false;
        return a;
    }

    private int rawLogged;
    private byte[] lastRaw;
    private int rumbleLogged;
    private short lastLow, lastHigh, lastLt, lastRt;

    public static boolean canClaimDevice(UsbDevice device) {
        if (device.getVendorId() != VID || device.getProductId() != PID || device.getInterfaceCount() < 3) {
            return false;
        }
        UsbInterface i0 = device.getInterface(0);
        return i0.getId() == 0
                && i0.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC
                && i0.getInterfaceSubclass() == 93
                && i0.getInterfaceProtocol() == 1;
    }

    public ApexController(UsbDevice device, UsbDeviceConnection connection, int deviceId, UsbDriverListener listener) {
        super(device, connection, deviceId, listener);
        this.type = MoonBridge.LI_CTYPE_PS;
        this.capabilities = (short) (MoonBridge.LI_CCAP_ANALOG_TRIGGERS | MoonBridge.LI_CCAP_RUMBLE | MoonBridge.LI_CCAP_HAPTICS_PCM);
    }

    @Override
    protected boolean claimInterfaces() {
        UsbInterface if0 = null, if2 = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface u = device.getInterface(i);
            if (u.getId() == 0) if0 = u;
            if (u.getId() == 2) if2 = u;
        }
        if (if0 == null) {
            ArbsLog.i("APEX: IF0 없음 - claim 중단");
            return false;
        }
        boolean ok = connection.claimInterface(if0, false);
        ArbsLog.i("APEX claimInterface(IF0, force=false): " + (ok ? "성공" : "실패"));
        if (!ok) {
            ok = connection.claimInterface(if0, true);
            ArbsLog.i("APEX claimInterface(IF0, force=true): " + (ok ? "성공" : "실패"));
            if (!ok) return false;
        }
        if (if2 == null) {
            ArbsLog.i("APEX: IF2 없음 - 입력만 사용(햅틱 없음)");
            return true;
        }
        boolean ok2 = connection.claimInterface(if2, true);
        ArbsLog.i("APEX claimInterface(IF2, force=true): " + (ok2 ? "성공" : "실패") + " (IF1/IF3 은 건드리지 않음)");
        ifaceTwoClaimed = ok2;
        return true;
    }

    private boolean ifaceTwoClaimed;

    @Override
    protected boolean doInit() {
        // No LED command: nothing is written to IF0 OUT.
        ArbsLog.i("APEX 컨트롤러 시작: type=PS caps=ANALOG_TRIGGERS|RUMBLE|HAPTICS_PCM(0x8000), IF0 OUT 사용 안 함");
        if (ifaceTwoClaimed) {
            ApexHaptics.get().attach(connection, device);
        }
        return true;
    }

    @Override
    protected boolean handleRead(ByteBuffer buffer) {
        int n = buffer.remaining();
        ApexHaptics.get().onInputReport();   // A1-3b: 첫 입력 후 GetInfo / 입력 재개 시 재시도
        if (rawLogged < 3) {
            byte[] raw = new byte[n];
            buffer.duplicate().get(raw);
            if (lastRaw == null || !Arrays.equals(lastRaw, raw)) {
                lastRaw = raw;
                rawLogged++;
                ArbsLog.i("APEX 입력 리포트 샘플[" + rawLogged + "] len=" + n + " " + ApexProtocol.hex(raw, Math.min(n, 24)));
            }
        }
        boolean res = super.handleRead(buffer);
        if (inputDiag) {
            diagnose(buffer, n);
        }
        return res;
    }

    private void diagnose(ByteBuffer buffer, int n) {
        byte[] raw = new byte[n];
        buffer.duplicate().position(0);
        ByteBuffer d = buffer.duplicate();
        d.position(0);
        d.get(raw, 0, Math.min(n, d.remaining()));
        if (diagLastRaw != null && Arrays.equals(diagLastRaw, raw)) {
            return;
        }
        diagLastRaw = raw;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - diagWindowMs >= 1000) {
            diagWindowMs = now;
            diagLines = 0;
        }
        if (diagLines >= 20) {
            return;
        }
        diagLines++;
        diagArmed = true;
        ArbsLog.i(String.format(java.util.Locale.US,
                "입력진단 raw[%d]=%s | 파싱 LT=%.3f RT=%.3f buttons=0x%X LS=(%.2f,%.2f) RS=(%.2f,%.2f)",
                n, ApexProtocol.hex(raw, Math.min(n, 24)), leftTrigger, rightTrigger, buttonFlags,
                leftStickX, leftStickY, rightStickX, rightStickY));
    }

    @Override
    public void stop() {
        // Stop haptics (stop sequence on IF2) before the connection is closed by the base class.
        ApexHaptics.get().detach("USB/컨트롤러 종료");
        super.stop();
    }

    /** A1-3: rumble -> 그립 사인(IF2, 상한 <=60/127). IF0 OUT is never written. */
    @Override
    public void rumble(short lowFreqMotor, short highFreqMotor) {
        if ((lowFreqMotor != lastLow || highFreqMotor != lastHigh) && rumbleLogged < 200) {
            rumbleLogged++;
            ArbsLog.i(String.format("APEX rumble 수신(그립 출력): low=%d high=%d", lowFreqMotor & 0xFFFF, highFreqMotor & 0xFFFF));
        }
        lastLow = lowFreqMotor;
        lastHigh = highFreqMotor;
        ApexHaptics.get().onRumble(lowFreqMotor & 0xFFFF, highFreqMotor & 0xFFFF);
    }

    @Override
    public void rumbleTriggers(short leftTrigger, short rightTrigger) {
        if ((leftTrigger != lastLt || rightTrigger != lastRt) && rumbleLogged < 200) {
            rumbleLogged++;
            ArbsLog.i(String.format("APEX 트리거 rumble 수신(로그만, 출력 없음): L=%d R=%d", leftTrigger & 0xFFFF, rightTrigger & 0xFFFF));
        }
        lastLt = leftTrigger;
        lastRt = rightTrigger;
    }
}
