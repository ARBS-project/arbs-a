package com.limelight.arbs;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ARBS A1-4b: ARBS 설정 항목의 단일 정의 + 시작 시 마이그레이션 + 예외 없는 읽기.
 * 자료형이 다르거나 허용 목록 밖인 저장값은 ASB 기본값으로 교체한다(이전 판 값이 남아 있어도 설정 화면·엔진이 죽지 않게).
 * res/xml/preferences.xml 의 키·기본값·entryValues 와 일치해야 하며 단위 테스트가 이를 검사한다.
 */
public final class ArbsSettings {
    public static final String VERSION_KEY = "arbs_settings_version";
    /** 설정 구조가 바뀔 때 올린다. 1 = A1-4b. */
    public static final int CURRENT_VERSION = 1;

    public static final class Spec {
        public final String key;
        public final boolean isBool;
        public final boolean defBool;
        public final String def;
        public final List<String> allowed;

        private Spec(String key, boolean defBool) {
            this.key = key;
            this.isBool = true;
            this.defBool = defBool;
            this.def = String.valueOf(defBool);
            this.allowed = Collections.emptyList();
        }

        private Spec(String key, String def, String... allowed) {
            this.key = key;
            this.isBool = false;
            this.defBool = false;
            this.def = def;
            this.allowed = Collections.unmodifiableList(Arrays.asList(allowed));
        }
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    private static void b(String key, boolean def) {
        SPECS.put(key, new Spec(key, def));
    }

    private static void l(String key, String def, String... allowed) {
        SPECS.put(key, new Spec(key, def, allowed));
    }

    static {
        b("checkbox_arbs_haptics_test", false);
        b("checkbox_arbs_apex_first", true);
        b("checkbox_arbs_input_diag", false);
        b("checkbox_arbs_verbose_log", false);
        l("list_arbs_apex_amp", "127", "0", "10", "20", "30", "40", "50", "60", "80", "100", "127");
        l("list_arbs_apex_gain", "1.0", "1.0", "2.0", "3.0", "4.0", "6.0", "8.0");
        l("list_arbs_gate", "0", "0", "1", "2", "4");
        l("list_arbs_silence_mode", "1", "1", "2", "3", "4");
        l("list_arbs_curve", "asb", "asb", "tanh");
        l("list_arbs_filter", "321", "161", "321", "641");
        l("list_arbs_jitter_fixed", "0", "0", "16", "24", "32", "48", "64", "80");
        b("checkbox_arbs_trigger", true);
        l("list_arbs_trigger_strength", "100", "0", "20", "40", "60", "80", "100");
    }

    /** 더는 쓰지 않는 이전 판 키(남아 있으면 지운다). */
    private static final String[] OBSOLETE = {"list_arbs_keepalive"};

    private ArbsSettings() {
    }

    public static Spec spec(String key) {
        return SPECS.get(key);
    }

    public static List<String> keys() {
        return new ArrayList<>(SPECS.keySet());
    }

    /**
     * 저장된 ARBS 값을 검사해 문제가 있는 것만 ASB 기본값으로 교체한다.
     * @return 변경 내용(한 줄씩, 값 자체는 포함하되 개인정보 없음). 변경이 없으면 빈 목록.
     */
    public static List<String> migrate(SharedPreferences prefs) {
        List<String> changes = new ArrayList<>();
        Map<String, ?> all;
        try {
            all = prefs.getAll();
        } catch (RuntimeException e) {
            changes.add("설정 읽기 실패(" + e.getClass().getSimpleName() + ") - 이번 실행은 기본값 사용");
            return changes;
        }
        SharedPreferences.Editor ed = prefs.edit();
        boolean dirty = false;
        for (Spec s : SPECS.values()) {
            Object v = all.get(s.key);
            if (v == null) continue; // 저장값 없음 = XML 기본값
            String why = null;
            if (s.isBool) {
                if (!(v instanceof Boolean)) why = "자료형 " + v.getClass().getSimpleName() + " (Boolean 이어야 함)";
            } else if (!(v instanceof String)) {
                why = "자료형 " + v.getClass().getSimpleName() + " (String 이어야 함)";
            } else if (!s.allowed.contains(v)) {
                why = "허용 목록 밖 값 '" + v + "'";
            }
            if (why != null) {
                if (s.isBool) ed.putBoolean(s.key, s.defBool);
                else ed.putString(s.key, s.def);
                changes.add("설정 교체 " + s.key + ": " + why + " -> ASB 기본값 " + s.def);
                dirty = true;
            }
        }
        for (String k : OBSOLETE) {
            if (all.containsKey(k)) {
                ed.remove(k);
                changes.add("설정 삭제 " + k + ": 이전 판에서 쓰던 항목");
                dirty = true;
            }
        }
        Object ver = all.get(VERSION_KEY);
        if (!(ver instanceof Integer) || (Integer) ver != CURRENT_VERSION) {
            ed.putInt(VERSION_KEY, CURRENT_VERSION);
            changes.add("설정 버전 " + (ver == null ? "없음" : String.valueOf(ver)) + " -> " + CURRENT_VERSION);
            dirty = true;
        }
        if (dirty) ed.apply();
        return changes;
    }

    /** 문자열 항목을 안전하게 읽는다: 자료형·허용 목록이 틀리면 기본값. */
    public static String getList(SharedPreferences prefs, String key) {
        Spec s = SPECS.get(key);
        if (s == null || s.isBool) throw new IllegalArgumentException("list key 아님: " + key);
        try {
            String v = prefs.getString(key, s.def);
            return v != null && s.allowed.contains(v) ? v : s.def;
        } catch (RuntimeException e) { // ClassCastException 등
            return s.def;
        }
    }

    public static boolean getBool(SharedPreferences prefs, String key) {
        Spec s = SPECS.get(key);
        if (s == null || !s.isBool) throw new IllegalArgumentException("bool key 아님: " + key);
        try {
            return prefs.getBoolean(key, s.defBool);
        } catch (RuntimeException e) {
            return s.defBool;
        }
    }

    public static int getInt(SharedPreferences prefs, String key) {
        return Integer.parseInt(getList(prefs, key)); // 허용 목록은 모두 정수 문자열
    }

    public static float getFloat(SharedPreferences prefs, String key) {
        return Float.parseFloat(getList(prefs, key));
    }
}
