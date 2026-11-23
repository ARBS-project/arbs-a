package com.limelight.arbs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;

import javax.xml.parsers.DocumentBuilderFactory;

/** ARBS-A R2a (D-R2a-3): update notice rules, same cases as ARBS-W. */
public class ArbsUpdateTest {
    private static final String PAGE = "https://github.com/ARBS-project/arbs-a/releases/tag/v0.1.1";

    @Test
    public void endpointIsTheArbsARepositoryLatestStableRelease() {
        assertEquals("https://api.github.com/repos/arbs-project/arbs-a/releases/latest", ArbsUpdate.LATEST_RELEASE_API_URL);
        assertEquals("https://github.com/arbs-project/arbs-a/releases", ArbsUpdate.RELEASES_PAGE_URL);
        assertFalse(ArbsUpdate.LATEST_RELEASE_API_URL.toLowerCase().contains("moonlight"));
        assertFalse(ArbsUpdate.RELEASES_PAGE_URL.toLowerCase().contains("moonlight"));
    }

    @Test
    public void parsesOnlyThreeNumericParts() {
        assertEquals(1, ArbsUpdate.parseVersion("v1.2.3")[0]);
        assertEquals(3, ArbsUpdate.parseVersion("1.2.3")[2]);
        for (String bad : new String[]{null, "", "v", "1.2", "1.2.3.4", "1.2.3-beta", "V1.2.3", " 1.2.3", "1..3", "1234567.0.0", "١.2.3"}) {
            assertNull(String.valueOf(bad), ArbsUpdate.parseVersion(bad));
        }
    }

    @Test
    public void comparesNumericallyNotAsText() {
        assertTrue(ArbsUpdate.isNewerRelease("0.1.0", "v0.1.1"));
        assertTrue(ArbsUpdate.isNewerRelease("0.9.0", "0.10.0"));
        assertTrue(ArbsUpdate.isNewerRelease("0.1.9", "1.0.0"));
        assertFalse(ArbsUpdate.isNewerRelease("0.1.0", "v0.1.0"));
        assertFalse(ArbsUpdate.isNewerRelease("0.2.0", "v0.1.9"));
        assertFalse(ArbsUpdate.isNewerRelease("0.1.0", "nightly"));
        assertFalse(ArbsUpdate.isNewerRelease("dev", "v9.9.9"));
    }

    @Test
    public void versionTextDropsLeadingV() {
        assertEquals("0.1.1", ArbsUpdate.releaseVersionText("v0.1.1"));
        assertEquals("0.1.1", ArbsUpdate.releaseVersionText("0.1.1"));
        assertEquals("", ArbsUpdate.releaseVersionText("v0.1"));
    }

    @Test
    public void trustsOnlyArbsAReleasePages() {
        assertTrue(ArbsUpdate.isTrustedReleaseUrl(PAGE));
        assertTrue(ArbsUpdate.isTrustedReleaseUrl("https://github.com/arbs-project/arbs-a/releases/tag/v0.1.1"));
        for (String bad : new String[]{
                null, "",
                "http://github.com/arbs-project/arbs-a/releases/tag/v1.0.0",
                "https://github.com.evil.test/arbs-project/arbs-a/releases/tag/v1.0.0",
                "https://user@github.com/arbs-project/arbs-a/releases/tag/v1.0.0",
                "https://github.com/moonlight-stream/moonlight-android/releases/tag/v1.0.0",
                "https://github.com/arbs-project/arbs-w/releases/tag/v1.0.0",
                "https://github.com/arbs-project/ARBS-A/releases/tag/v1.0.0",
                "https://github.com/arbs-project/arbs-a/releases/../../evil",
                "https://github.com/arbs-project/arbs-a/releases/tag/v1?x=1",
                "https://github.com/arbs-project/arbs-a/releases/tag/v1#x",
                "https://github.com/arbſ-project/arbs-a/releases/tag/v1.0.0",
        }) {
            assertFalse(String.valueOf(bad), ArbsUpdate.isTrustedReleaseUrl(bad));
        }
    }

    @Test
    public void classifiesEveryAnswer() {
        assertEquals(ArbsUpdate.Outcome.UPDATE_AVAILABLE, ArbsUpdate.classify(200, "v0.1.1", PAGE, false, false, "0.1.0"));
        assertEquals(ArbsUpdate.Outcome.UP_TO_DATE, ArbsUpdate.classify(200, "v0.1.0", PAGE, false, false, "0.1.0"));
        assertEquals(ArbsUpdate.Outcome.NO_RELEASE, ArbsUpdate.classify(404, "", "", false, false, "0.1.0"));
        assertEquals(ArbsUpdate.Outcome.FAILED, ArbsUpdate.classify(403, "", "", false, false, "0.1.0"));
        assertEquals(ArbsUpdate.Outcome.FAILED, ArbsUpdate.classify(500, "", "", false, false, "0.1.0"));
        assertEquals(ArbsUpdate.Outcome.FAILED, ArbsUpdate.classify(200, "nightly", PAGE, false, false, "0.1.0"));
        assertEquals(ArbsUpdate.Outcome.FAILED, ArbsUpdate.classify(200, "v0.1.1", "https://evil.test/", false, false, "0.1.0"));
    }

    @Test
    public void draftsAndPrereleasesAreNeverOffered() {
        assertEquals(ArbsUpdate.Outcome.UP_TO_DATE, ArbsUpdate.classify(200, "v9.0.0", PAGE, true, false, "0.1.0"));
        assertEquals(ArbsUpdate.Outcome.UP_TO_DATE, ArbsUpdate.classify(200, "v9.0.0", PAGE, false, true, "0.1.0"));
    }

    @Test
    public void userAgentIsSent() {
        assertEquals("ARBS-A/0.1.0", ArbsUpdate.userAgent("0.1.0"));
    }

    @Test
    public void settingIsInTheArbsCategoryAndOffByDefault() throws Exception {
        File xml = new File("src/main/res/xml/preferences.xml");
        if (!xml.isFile()) xml = new File("app/src/main/res/xml/preferences.xml");
        NodeList boxes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml).getElementsByTagName("CheckBoxPreference");
        Element found = null;
        for (int i = 0; i < boxes.getLength(); i++) {
            Element e = (Element) boxes.item(i);
            if ("checkbox_arbs_check_updates".equals(e.getAttribute("android:key"))) found = e;
        }
        assertNotNull(found);
        assertEquals("false", found.getAttribute("android:defaultValue"));
        assertEquals("category_arbs", ((Element) found.getParentNode()).getAttribute("android:key"));
    }
}
