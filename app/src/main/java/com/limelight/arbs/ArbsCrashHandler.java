package com.limelight.arbs;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ARBS A1-4b: 전역 예외 처리기. 앱이 종료될 예외가 나면 스택 트레이스를 ARBS 로그와 다운로드 폴더
 * arbs-crash-&lt;시각&gt;.txt 에 저장(IP 주소 제거)한 뒤 기존 처리기로 넘겨 종전처럼 종료한다.
 * 저장 중 어떤 오류가 나도 삼키고 기존 처리기를 반드시 호출한다.
 */
public final class ArbsCrashHandler implements Thread.UncaughtExceptionHandler {
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?\\b");
    private static final Pattern IPV6 = Pattern.compile("(?i)\\b(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{1,4}\\b|(?i)::(?:[0-9a-f]{1,4}:?)+");
    private static final Pattern TOKEN = Pattern.compile("(?i)(token|password|passwd|secret|uniqueid|pin)=[^&\\s,;]+");

    private final Context app;
    private final Thread.UncaughtExceptionHandler previous;
    private static boolean installed;

    private ArbsCrashHandler(Context app, Thread.UncaughtExceptionHandler previous) {
        this.app = app;
        this.previous = previous;
    }

    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        Thread.setDefaultUncaughtExceptionHandler(
                new ArbsCrashHandler(context.getApplicationContext(), Thread.getDefaultUncaughtExceptionHandler()));
    }

    /** IP 주소·토큰류 제거. */
    public static String sanitize(String s) {
        if (s == null) return "";
        s = IPV4.matcher(s).replaceAll("<ip>");
        s = IPV6.matcher(s).replaceAll("<ip>");
        return TOKEN.matcher(s).replaceAll("$1=<removed>");
    }

    public static String buildReport(Throwable t, String threadName, String header, String logTail) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        StringBuilder sb = new StringBuilder();
        sb.append("ARBS Moonlight 크래시 보고 (IP·토큰 제거됨)\n");
        sb.append(header).append('\n');
        sb.append("스레드: ").append(threadName).append('\n');
        sb.append("예외: ").append(t.getClass().getName()).append('\n');
        sb.append("--- 스택 트레이스 ---\n").append(sw).append('\n');
        if (logTail != null && !logTail.isEmpty()) {
            sb.append("--- ARBS 로그 마지막 부분 ---\n").append(logTail).append('\n');
        }
        return sanitize(sb.toString());
    }

    @Override
    public void uncaughtException(Thread thread, Throwable t) {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String header = "시각: " + ts + "\n기기: " + Build.MANUFACTURER + " " + Build.MODEL
                    + ", Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")";
            ArbsLog log = ArbsLog.get(app);
            String tail = log.tailText();
            String report = buildReport(t, thread == null ? "?" : thread.getName(), header, tail);
            String firstFrame = t.getStackTrace().length > 0 ? t.getStackTrace()[0].toString() : "";
            log.line(sanitize("치명적 예외로 앱 종료: " + t.getClass().getName() + " @ " + firstFrame));
            String name = "arbs-crash-" + ts + ".txt";
            if (!saveToDownloads(report, name)) {
                saveToFiles(report, name);
            }
        } catch (Throwable ignored) {
            // 크래시 보고 실패가 원래 종료 처리를 막으면 안 된다
        }
        if (previous != null) previous.uncaughtException(thread, t);
    }

    private boolean saveToDownloads(String text, String name) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
            Uri uri = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return false;
            try (OutputStream os = app.getContentResolver().openOutputStream(uri)) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
            ArbsLog.i("크래시 보고 저장(다운로드): " + name);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void saveToFiles(String text, String name) {
        try (FileOutputStream fos = new FileOutputStream(new File(app.getFilesDir(), name))) {
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            ArbsLog.i("크래시 보고 저장(앱 내부 files, 다운로드 저장 실패): " + name);
        } catch (Throwable ignored) {
        }
    }
}
