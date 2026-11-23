package com.limelight.arbs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.limelight.BuildConfig;
import com.limelight.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ARBS-A R2a (D-R2a-3): optional update notice, off by default, same behavior as ARBS-W.
 * Only reads the latest published release; nothing is downloaded or installed.
 */
public final class ArbsUpdateChecker {
    public static final String PREF_KEY = "checkbox_arbs_check_updates";

    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final AtomicBoolean startupCheckDone = new AtomicBoolean(false);

    private ArbsUpdateChecker() {
    }

    /** Once per app process, only when the user turned the setting on. Silent unless a new version exists. */
    public static void checkAtStartup(Activity activity) {
        if (!PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(PREF_KEY, false)) return;
        if (!startupCheckDone.compareAndSet(false, true)) return;
        start(activity, false);
    }

    /** The user just turned the setting on: every result is shown. */
    public static void checkNow(Activity activity) {
        startupCheckDone.set(true);
        start(activity, true);
    }

    private static final class Result {
        ArbsUpdate.Outcome outcome = ArbsUpdate.Outcome.FAILED;
        String version = "";
        String pageUrl = ArbsUpdate.RELEASES_PAGE_URL;
    }

    private static void start(Activity activity, final boolean showAllResults) {
        final WeakReference<Activity> ref = new WeakReference<>(activity);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                final Result result = fetch();
                ArbsLog.i("업데이트 확인: " + result.outcome);
                final Activity a = ref.get();
                if (a == null) return;
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        show(a, result, showAllResults);
                    }
                });
            }
        }, "ARBS-UpdateCheck");
        t.setDaemon(true);
        t.start();
    }

    private static Result fetch() {
        Result result = new Result();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(ArbsUpdate.LATEST_RELEASE_API_URL).openConnection();
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", ArbsUpdate.userAgent(BuildConfig.VERSION_NAME));
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            final int status = conn.getResponseCode();
            if (status != 200) {
                result.outcome = ArbsUpdate.classify(status, "", "", false, false, BuildConfig.VERSION_NAME);
                return result;
            }
            final JSONObject release;
            try (InputStream in = conn.getInputStream()) {
                release = new JSONObject(readLimited(in));
            }
            final String tag = release.optString("tag_name", "");
            final String pageUrl = release.optString("html_url", "");
            result.outcome = ArbsUpdate.classify(status, tag, pageUrl,
                    release.optBoolean("draft", false), release.optBoolean("prerelease", false), BuildConfig.VERSION_NAME);
            if (result.outcome == ArbsUpdate.Outcome.UPDATE_AVAILABLE) {
                result.version = ArbsUpdate.releaseVersionText(tag);
                result.pageUrl = pageUrl;
            }
        } catch (IOException | JSONException | RuntimeException e) {
            // Offline, timeout or a malformed answer. The exception text is not logged (it can contain addresses).
            result.outcome = ArbsUpdate.Outcome.FAILED;
        } finally {
            if (conn != null) conn.disconnect();
        }
        return result;
    }

    private static String readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > MAX_BODY_BYTES) throw new IOException("response too large");
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void show(final Activity activity, final Result result, boolean showAllResults) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        switch (result.outcome) {
            case UPDATE_AVAILABLE:
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.arbs_update_available_title)
                        .setMessage(activity.getString(R.string.arbs_update_available_message, result.version, BuildConfig.VERSION_NAME))
                        .setPositiveButton(R.string.arbs_update_open, (dialog, which) -> openPage(activity, result.pageUrl))
                        .setNegativeButton(R.string.arbs_update_later, null)
                        .show();
                break;
            case UP_TO_DATE:
                if (showAllResults) Toast.makeText(activity, R.string.toast_arbs_update_latest, Toast.LENGTH_LONG).show();
                break;
            case NO_RELEASE:
                if (showAllResults) Toast.makeText(activity, R.string.toast_arbs_update_no_release, Toast.LENGTH_LONG).show();
                break;
            default:
                if (showAllResults) Toast.makeText(activity, R.string.toast_arbs_update_failed, Toast.LENGTH_LONG).show();
                break;
        }
    }

    private static void openPage(Activity activity, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, activity.getString(R.string.toast_arbs_update_no_browser, url), Toast.LENGTH_LONG).show();
        }
    }
}
