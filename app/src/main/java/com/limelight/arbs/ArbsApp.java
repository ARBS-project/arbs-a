package com.limelight.arbs;

import android.app.Application;
import android.preference.PreferenceManager;

import java.util.List;

/** ARBS A1-4b: 앱 시작 시 크래시 처리기 설치 + 설정 마이그레이션(모든 액티비티보다 먼저). */
public class ArbsApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ArbsCrashHandler.install(this);
        } catch (Throwable ignored) {
        }
        try {
            List<String> changes = ArbsSettings.migrate(PreferenceManager.getDefaultSharedPreferences(this));
            if (!changes.isEmpty()) {
                ArbsLog log = ArbsLog.get(this);
                for (String c : changes) log.line(c);
            }
        } catch (Throwable t) {
            try {
                ArbsLog.get(this).line("설정 마이그레이션 실패: " + t.getClass().getSimpleName());
            } catch (Throwable ignored) {
            }
        }
    }
}
