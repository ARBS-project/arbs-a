package com.limelight.arbs;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** ARBS A1-1 로그 화면: 보기·다운로드 폴더 저장·복사. (설정 → ARBS 항목에서 연다.) */
public class ArbsLogActivity extends Activity {
    private TextView view;
    private TextView status;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            view.setText(ArbsLog.get(ArbsLogActivity.this).tailText());
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("ARBS Moonlight A1-1 로그 (수신 전용)");
        title.setTextSize(18f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        status = new TextView(this);
        root.addView(status);

        Button save = new Button(this);
        save.setText("다운로드 폴더에 저장");
        save.setOnClickListener(v -> {
            String n = ArbsLog.get(this).saveToDownloads(this);
            status.setText(n != null ? "저장했습니다: " + n : "저장 실패");
        });
        root.addView(save);

        Button copy = new Button(this);
        copy.setText("전체 복사(클립보드)");
        copy.setOnClickListener(v -> {
            String t = ArbsLog.get(this).fullText();
            ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("ARBS log", t));
            Toast.makeText(this, "복사됨 (" + t.length() + "자)", Toast.LENGTH_SHORT).show();
        });
        root.addView(copy);

        view = new TextView(this);
        view.setTextSize(11f);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(view);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); ui.post(refresher); }
    @Override protected void onPause() { ui.removeCallbacks(refresher); super.onPause(); }
}
