package com.limelight.arbs;

/**
 * ARBS-A R2a (D-R2a-3): rules for the optional update notice, same as ARBS-W (arbs_update.cpp).
 * GitHub Releases of arbs-project/arbs-a only. No Android classes and no I/O, so it runs in JVM unit tests.
 */
public final class ArbsUpdate {
    public static final String LATEST_RELEASE_API_URL = "https://api.github.com/repos/arbs-project/arbs-a/releases/latest";
    public static final String RELEASES_PAGE_URL = "https://github.com/arbs-project/arbs-a/releases";

    public enum Outcome { UPDATE_AVAILABLE, UP_TO_DATE, NO_RELEASE, FAILED }

    private static final String TRUSTED_HOST = "https://github.com/";
    private static final String TRUSTED_ORG = "arbs-project";
    private static final String TRUSTED_REPO = "/arbs-a/releases/";

    private ArbsUpdate() {
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static char lowerAscii(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c - 'A' + 'a') : c;
    }

    /** "0.1.2" or "v0.1.2" (exactly three numeric parts). Anything else returns null. */
    static int[] parseVersion(String text) {
        if (text == null) return null;
        int i = (!text.isEmpty() && text.charAt(0) == 'v') ? 1 : 0;
        int[] parsed = new int[3];
        for (int part = 0; part < parsed.length; part++) {
            final int start = i;
            int value = 0;
            while (i < text.length() && isDigit(text.charAt(i))) {
                if (i - start >= 6) return null; // keeps the value far from int overflow
                value = value * 10 + (text.charAt(i) - '0');
                i++;
            }
            if (i == start) return null;
            parsed[part] = value;
            if (part + 1 < parsed.length) {
                if (i >= text.length() || text.charAt(i) != '.') return null;
                i++;
            }
        }
        return i == text.length() ? parsed : null;
    }

    /** Tag without the leading "v"; empty when the tag is not a release version. */
    public static String releaseVersionText(String tag) {
        if (parseVersion(tag) == null) return "";
        return tag.charAt(0) == 'v' ? tag.substring(1) : tag;
    }

    /** True only when both parse and tag > current. */
    public static boolean isNewerRelease(String current, String tag) {
        int[] cur = parseVersion(current);
        int[] latest = parseVersion(tag);
        if (cur == null || latest == null) return false;
        for (int i = 0; i < cur.length; i++) {
            if (latest[i] != cur[i]) return latest[i] > cur[i];
        }
        return false;
    }

    /** https://github.com/&lt;arbs-project, any ASCII case&gt;/arbs-a/releases/&lt;plain path&gt;. No "..", query, fragment or credentials. */
    public static boolean isTrustedReleaseUrl(String url) {
        if (url == null || !url.startsWith(TRUSTED_HOST)) return false;
        final int orgStart = TRUSTED_HOST.length();
        final int orgEnd = orgStart + TRUSTED_ORG.length();
        if (url.length() < orgEnd + TRUSTED_REPO.length()) return false;
        for (int i = 0; i < TRUSTED_ORG.length(); i++) {
            // ASCII only: String.equalsIgnoreCase would also accept look-alike letters such as U+017F.
            if (lowerAscii(url.charAt(orgStart + i)) != TRUSTED_ORG.charAt(i)) return false;
        }
        if (!url.startsWith(TRUSTED_REPO, orgEnd)) return false;
        final String rest = url.substring(orgEnd + TRUSTED_REPO.length());
        if (rest.contains("..")) return false;
        for (int i = 0; i < rest.length(); i++) {
            final char c = rest.charAt(i);
            final boolean plain = isDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '.' || c == '-' || c == '_' || c == '/';
            if (!plain) return false;
        }
        return true;
    }

    /** GitHub's API answers 403 without a User-Agent. */
    public static String userAgent(String version) {
        return "ARBS-A/" + version;
    }

    /** Decides the result of one check. 404 = nothing published yet, which is not an error. */
    public static Outcome classify(int httpStatus, String tag, String pageUrl, boolean draft, boolean prerelease, String currentVersion) {
        if (httpStatus == 404) return Outcome.NO_RELEASE;
        if (httpStatus != 200) return Outcome.FAILED;
        // /releases/latest never returns these; checked again so a test build is never offered.
        if (draft || prerelease) return Outcome.UP_TO_DATE;
        if (releaseVersionText(tag).isEmpty() || !isTrustedReleaseUrl(pageUrl)) return Outcome.FAILED;
        return isNewerRelease(currentVersion, tag) ? Outcome.UPDATE_AVAILABLE : Outcome.UP_TO_DATE;
    }
}
