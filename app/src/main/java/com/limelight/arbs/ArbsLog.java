package com.limelight.arbs;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * ARBS A1-1: 수신 로그 전용(장치로 아무것도 보내지 않는다). files/arbs-a1-&lt;시각&gt;.log 에 줄 단위로 기록.
 * IP·토큰·페어링 정보는 기록하지 않는다(호출자 책임: 여기서는 컨트롤러 번호·타입·capability·패킷 통계만 받는다).
 */
public final class ArbsLog {
    private static final int TAIL_LINES = 400;
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    private static ArbsLog instance;

    private final File file;
    private final long startMs = System.currentTimeMillis();
    private final ArrayDeque<String> tail = new ArrayDeque<>();
    private long written;

    private ArbsLog(File file) {
        this.file = file;
    }

    public static synchronized ArbsLog get(Context context) {
        if (instance == null) {
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            instance = new ArbsLog(new File(context.getApplicationContext().getFilesDir(), "arbs-a1-" + ts + ".log"));
            instance.line("ARBS Moonlight A1-1 로그 — 수신 로그 전용, APEX/장치로 출력 없음");
            instance.line("기기: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        }
        return instance;
    }

    /** 앱 컨텍스트 없이 JNI 스레드에서 호출할 때: get()이 한 번 불린 뒤에만 유효. */
    public static synchronized ArbsLog peek() {
        return instance;
    }

    public synchronized void line(String s) {
        String out = String.format(Locale.US, "[+%7.3fs] %s", (System.currentTimeMillis() - startMs) / 1000.0, s);
        tail.addLast(out);
        while (tail.size() > TAIL_LINES) tail.removeFirst();
        if (written > MAX_FILE_BYTES) return;
        byte[] b = (out + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream fos = new FileOutputStream(file, true)) {
            fos.write(b);
            written += b.length;
        } catch (IOException ignored) {
        }
    }

    public synchronized String tailText() {
        StringBuilder sb = new StringBuilder();
        for (String s : tail) sb.append(s).append('\n');
        return sb.toString();
    }

    public synchronized String fullText() {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return tailText();
        }
    }

    public String fileName() {
        return file.getName();
    }

    /** 다운로드 폴더에 저장(MediaStore, 권한 불필요). 성공하면 파일명, 실패하면 null. */
    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    public String saveToDownloads(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            line("다운로드 저장은 Android 10 이상에서만 지원(복사 버튼 사용)");
            return null;
        }
        try {
            String text = fullText();
            String name = "ARBS-Moonlight-" + file.getName().replace("arbs-a1-", "").replace(".log", "") + ".txt";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/");
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            try (OutputStream os = context.getContentResolver().openOutputStream(uri)) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Downloads.IS_PENDING, 0);
            context.getContentResolver().update(uri, done, null, null);
            line("다운로드 폴더 저장: " + name);
            return name;
        } catch (Exception e) {
            line("다운로드 저장 실패: " + e.getClass().getSimpleName());
            return null;
        }
    }
}
