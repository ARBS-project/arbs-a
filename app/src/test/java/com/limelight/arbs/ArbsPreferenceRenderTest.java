package com.limelight.arbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * A1-4b 재현 테스트(JVM): 설정 화면이 항목을 그릴 때 안드로이드(android.preference.ListPreference.getSummary)가 하는 일
 * "String.format(summary, entry)" 를 res 파일 그대로 재현한다. summary 에 '%' 가 있으면 UnknownFormatConversionException
 * -> 스크롤로 그 항목이 그려질 때 앱 종료(EA-A1-U-16). 기기 없이 가능한 범위의 재현이며 실기 확인은 별도.
 */
public class ArbsPreferenceRenderTest {
    private static File res() {
        File f = new File("src/main/res");
        if (!f.isDirectory()) f = new File("app/src/main/res");
        return f;
    }

    private static Document parse(File f) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
    }

    private static Map<String, String> strings(File valuesXml) throws Exception {
        Map<String, String> m = new HashMap<>();
        NodeList nl = parse(valuesXml).getElementsByTagName("string");
        for (int i = 0; i < nl.getLength(); i++) {
            Element e = (Element) nl.item(i);
            m.put(e.getAttribute("name"), e.getTextContent());
        }
        return m;
    }

    private static Map<String, List<String>> arrays(File valuesXml) throws Exception {
        Map<String, List<String>> m = new HashMap<>();
        NodeList nl = parse(valuesXml).getElementsByTagName("string-array");
        for (int i = 0; i < nl.getLength(); i++) {
            Element e = (Element) nl.item(i);
            List<String> items = new ArrayList<>();
            NodeList it = e.getElementsByTagName("item");
            for (int j = 0; j < it.getLength(); j++) items.add(it.item(j).getTextContent());
            m.put(e.getAttribute("name"), items);
        }
        return m;
    }

    private static String ref(String v) {
        return v.substring(v.indexOf('/') + 1);
    }

    @Test
    public void everyListPreferenceSummaryFormatsWithEveryEntry() throws Exception {
        File strings = new File(res(), "values/strings.xml");
        Map<String, String> str = strings(strings);
        Map<String, List<String>> arr = arrays(strings);
        NodeList nl = parse(new File(res(), "xml/preferences.xml")).getElementsByTagName("ListPreference");
        int checked = 0;
        for (int i = 0; i < nl.getLength(); i++) {
            Element e = (Element) nl.item(i);
            String key = e.getAttribute("android:key");
            if (!key.startsWith("list_arbs_")) continue; // 기본 Moonlight 항목은 변경 없음(범위 밖)
            String summary = str.get(ref(e.getAttribute("android:summary")));
            List<String> entries = arr.get(ref(e.getAttribute("android:entries")));
            List<String> values = arr.get(ref(e.getAttribute("android:entryValues")));
            assertTrue(key, summary != null && entries != null && values != null);
            assertEquals("entries/entryValues 길이: " + key, entries.size(), values.size());
            String def = e.getAttribute("android:defaultValue");
            assertTrue("defaultValue 가 목록 안에 있어야 함: " + key, values.contains(def));
            for (String entry : entries) {
                // android.preference.ListPreference.getSummary(): String.format(mSummary, entry)
                try {
                    String.format(Locale.getDefault(), summary, entry);
                } catch (RuntimeException ex) {
                    throw new AssertionError(key + " summary 가 그려질 때 예외: " + ex, ex);
                }
            }
            checked++;
        }
        assertTrue("ListPreference 가 하나도 검사되지 않음", checked == 8);
    }

    @Test
    public void arbsListPreferencesMatchSettingsAllowedValues() throws Exception {
        File strings = new File(res(), "values/strings.xml");
        Map<String, List<String>> arr = arrays(strings);
        NodeList nl = parse(new File(res(), "xml/preferences.xml")).getElementsByTagName("ListPreference");
        int arbs = 0;
        for (int i = 0; i < nl.getLength(); i++) {
            Element e = (Element) nl.item(i);
            String key = e.getAttribute("android:key");
            if (!key.startsWith("list_arbs_")) continue;
            List<String> values = arr.get(ref(e.getAttribute("android:entryValues")));
            ArbsSettings.Spec s = ArbsSettings.spec(key);
            assertTrue("ArbsSettings 에 항목 없음: " + key, s != null);
            assertEquals(key, values, s.allowed);
            assertEquals(key, e.getAttribute("android:defaultValue"), s.def);
            arbs++;
        }
        assertEquals(8, arbs);
    }

    @Test
    public void noArbsListSummaryContainsPercent() throws Exception {
        File strings = new File(res(), "values/strings.xml");
        Map<String, String> str = strings(strings);
        NodeList nl = parse(new File(res(), "xml/preferences.xml")).getElementsByTagName("ListPreference");
        for (int i = 0; i < nl.getLength(); i++) {
            Element e = (Element) nl.item(i);
            if (!e.getAttribute("android:key").startsWith("list_arbs_")) continue;
            String summary = str.get(ref(e.getAttribute("android:summary")));
            assertFalse(e.getAttribute("android:key") + " summary 에 % 금지", summary.contains("%"));
        }
    }
}
