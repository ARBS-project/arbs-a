package com.limelight.arbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ArbsSettingsTest {
    /** 메모리 SharedPreferences (getString 은 실제처럼 다른 자료형이면 ClassCastException). */
    static final class Fake implements SharedPreferences {
        final Map<String, Object> m = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(m); }
        @Override public String getString(String k, String d) { Object v = m.get(k); return v == null ? d : (String) v; }
        @Override public Set<String> getStringSet(String k, Set<String> d) { return d; }
        @Override public int getInt(String k, int d) { Object v = m.get(k); return v == null ? d : (Integer) v; }
        @Override public long getLong(String k, long d) { Object v = m.get(k); return v == null ? d : (Long) v; }
        @Override public float getFloat(String k, float d) { Object v = m.get(k); return v == null ? d : (Float) v; }
        @Override public boolean getBoolean(String k, boolean d) { Object v = m.get(k); return v == null ? d : (Boolean) v; }
        @Override public boolean contains(String k) { return m.containsKey(k); }
        @Override public Editor edit() { return new Ed(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { }

        final class Ed implements Editor {
            final Map<String, Object> pending = new HashMap<>();
            final java.util.Set<String> removed = new java.util.HashSet<>();
            @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { pending.put(k, v); return this; }
            @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
            @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
            @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
            @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
            @Override public Editor remove(String k) { removed.add(k); return this; }
            @Override public Editor clear() { m.clear(); return this; }
            @Override public boolean commit() { apply(); return true; }
            @Override public void apply() { m.putAll(pending); for (String r : removed) m.remove(r); }
        }
    }

    @Test
    public void emptyPrefsOnlyGetVersion() {
        Fake f = new Fake();
        List<String> ch = ArbsSettings.migrate(f);
        assertEquals(1, ch.size());
        assertEquals(ArbsSettings.CURRENT_VERSION, f.m.get(ArbsSettings.VERSION_KEY));
        assertTrue(ArbsSettings.migrate(f).isEmpty()); // 두 번째는 변경 없음
    }

    @Test
    public void a13eStoredValuesAreKept() {
        Fake f = new Fake();
        f.m.put("list_arbs_apex_amp", "40");
        f.m.put("list_arbs_apex_gain", "4.0");
        f.m.put("list_arbs_gate", "0");
        f.m.put("list_arbs_silence_mode", "1");
        f.m.put("list_arbs_curve", "asb");
        f.m.put("list_arbs_filter", "321");
        f.m.put("list_arbs_jitter_fixed", "0");
        f.m.put("checkbox_arbs_apex_first", true);
        List<String> ch = ArbsSettings.migrate(f);
        assertEquals(1, ch.size()); // 버전만
        assertEquals("40", f.m.get("list_arbs_apex_amp"));
        assertEquals("4.0", f.m.get("list_arbs_apex_gain"));
    }

    @Test
    public void oldOrWrongTypeValuesAreReplacedWithAsbDefaults() {
        Fake f = new Fake();
        f.m.put("list_arbs_apex_gain", "0.75");          // A1-3c 값: 현재 목록 밖
        f.m.put("list_arbs_apex_amp", 40);               // 자료형 Integer
        f.m.put("list_arbs_gate", 2.0f);                 // Float
        f.m.put("list_arbs_filter", "999");              // 목록 밖
        f.m.put("list_arbs_curve", "TANH");              // 대소문자 다름
        f.m.put("checkbox_arbs_trigger", "true");        // 문자열로 저장됨
        f.m.put("checkbox_arbs_input_diag", 1);          // Integer
        f.m.put("list_arbs_keepalive", "50");            // 사라진 키
        List<String> ch = ArbsSettings.migrate(f);
        assertEquals("1.0", f.m.get("list_arbs_apex_gain"));
        assertEquals("127", f.m.get("list_arbs_apex_amp"));
        assertEquals("0", f.m.get("list_arbs_gate"));
        assertEquals("321", f.m.get("list_arbs_filter"));
        assertEquals("asb", f.m.get("list_arbs_curve"));
        assertEquals(Boolean.TRUE, f.m.get("checkbox_arbs_trigger"));
        assertEquals(Boolean.FALSE, f.m.get("checkbox_arbs_input_diag"));
        assertFalse(f.m.containsKey("list_arbs_keepalive"));
        assertEquals(7 + 1 + 1, ch.size()); // 교체 7 + 삭제 1 + 버전 1
        for (String c : ch) assertFalse(c, c.contains("http"));
    }

    @Test
    public void safeGettersNeverThrowOnBadStoredTypes() {
        Fake f = new Fake();
        for (String k : ArbsSettings.keys()) {
            ArbsSettings.Spec s = ArbsSettings.spec(k);
            // 일부러 틀린 자료형 저장(getString/getBoolean 이 ClassCastException 을 던지는 상태)
            f.m.put(k, s.isBool ? (Object) "x" : (Object) Integer.valueOf(7));
        }
        for (String k : ArbsSettings.keys()) {
            ArbsSettings.Spec s = ArbsSettings.spec(k);
            if (s.isBool) assertEquals(k, s.defBool, ArbsSettings.getBool(f, k));
            else assertEquals(k, s.def, ArbsSettings.getList(f, k));
        }
        assertEquals(127, ArbsSettings.getInt(f, "list_arbs_apex_amp"));
        assertEquals(1.0f, ArbsSettings.getFloat(f, "list_arbs_apex_gain"), 0f);
        assertEquals(100, ArbsSettings.getInt(f, "list_arbs_trigger_strength"));
    }

    @Test
    public void allDefaultsParseAsNumbersWhereNumeric() {
        Fake f = new Fake();
        assertEquals(127, ArbsSettings.getInt(f, "list_arbs_apex_amp"));
        assertEquals(1.0f, ArbsSettings.getFloat(f, "list_arbs_apex_gain"), 0f);
        assertEquals(0f, ArbsSettings.getFloat(f, "list_arbs_gate"), 0f);
        assertEquals(1, ArbsSettings.getInt(f, "list_arbs_silence_mode"));
        assertEquals(321, ArbsSettings.getInt(f, "list_arbs_filter"));
        assertEquals(0, ArbsSettings.getInt(f, "list_arbs_jitter_fixed"));
        assertEquals("asb", ArbsSettings.getList(f, "list_arbs_curve"));
        assertTrue(ArbsSettings.getBool(f, "checkbox_arbs_trigger"));
        assertNull(f.m.get("list_arbs_apex_amp"));
    }

    @Test
    public void crashReportIsSanitized() {
        Throwable t = new IllegalStateException("connect to 192.168.0.12:47984 failed token=abcd1234 host fe80::1a2b:3c4d");
        String r = ArbsCrashHandler.buildReport(t, "main", "시각: 20261119-010203", "[+1.0s] 서버 10.1.2.3 연결");
        assertFalse(r, r.contains("192.168.0.12"));
        assertFalse(r, r.contains("10.1.2.3"));
        assertFalse(r, r.contains("abcd1234"));
        assertFalse(r, r.contains("fe80::1a2b"));
        assertTrue(r.contains("IllegalStateException"));
        assertTrue(r.contains("crashReportIsSanitized")); // 스택 트레이스 포함
        assertTrue(r.contains("<ip>"));
    }
}
