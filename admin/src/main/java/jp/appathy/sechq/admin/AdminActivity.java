package jp.appathy.sechq.admin;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

public class AdminActivity extends AppCompatActivity {

    static final int BG = 0xFF0E1116;
    static final int CARD = 0xFF171B21;
    static final int LINE = 0xFF2A313A;
    static final int TEXT = 0xFFE6EDF3;
    static final int SUB = 0xFF8B949E;
    static final int OK = 0xFF3FB950;
    static final int WARN = 0xFFF0883E;
    static final int BAD = 0xFFF85149;
    static final int ACC = 0xFF58A6FF;

    private LinearLayout body;
    private Fleet.Result fleet;
    private boolean loading;
    private ActivityResultLauncher<Intent> treeLauncher;

    private SharedPreferences prefs() {
        return getSharedPreferences("sechq_admin", MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        treeLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            try {
                                getContentResolver().takePersistableUriPermission(uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (Exception ignored) {
                            }
                            prefs().edit().putString("tree", uri.toString()).apply();
                            reload();
                        }
                    }
                });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(14), dp(16), dp(14), 0);

        TextView t = new TextView(this);
        t.setText("SecHQ 管理者ダッシュボード");
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        root.addView(t);

        TextView s = new TextView(this);
        s.setText("各端末が書き出した sechq_*.json を集約します");
        s.setTextColor(SUB);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        s.setPadding(0, 0, 0, dp(8));
        root.addView(s);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(28));
        sv.addView(body);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        sv.setLayoutParams(lp);
        root.addView(sv);

        setContentView(root);
        reload();
    }

    private void reload() {
        final String tree = prefs().getString("tree", "");
        render();
        if (tree.isEmpty() || loading) {
            return;
        }
        loading = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Fleet.Result r = Fleet.load(AdminActivity.this, tree);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        fleet = r;
                        loading = false;
                        render();
                    }
                });
            }
        }).start();
    }

    private void render() {
        body.removeAllViews();

        body.addView(btn("収集フォルダを選択 (Driveの共有フォルダ)", v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            treeLauncher.launch(i);
        }));
        body.addView(btn("再読み込み", v -> reload()));

        if (loading) {
            body.addView(note("読み込み中…"));
            return;
        }
        if (fleet == null) {
            body.addView(note("フォルダを選択してください"));
            return;
        }
        if (fleet.error != null) {
            body.addView(note(fleet.error));
            return;
        }

        LinearLayout sum = card();
        int avg = fleet.avgScore();
        TextView h = new TextView(this);
        h.setText("端末 " + fleet.devices.size() + " 台 / レポート " + fleet.files + " 件"
                + (avg >= 0 ? " / 平均スコア " + avg : ""));
        h.setTextColor(avg >= 80 ? OK : (avg >= 60 ? WARN : BAD));
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sum.addView(h);

        int ransom = 0;
        int stale = 0;
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd",
                java.util.Locale.JAPAN).format(new java.util.Date());
        for (Fleet.Device d : fleet.devices) {
            if (d.ransom > 0) {
                ransom++;
            }
            if (d.date != null && daysBetween(d.date, today) > 7) {
                stale++;
            }
        }
        if (ransom > 0) {
            TextView r = new TextView(this);
            r.setText("💣️ ランサムウェア痕跡のある端末: " + ransom + " 台");
            r.setTextColor(BAD);
            r.setTypeface(Typeface.DEFAULT_BOLD);
            r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            sum.addView(r);
        }
        if (stale > 0) {
            TextView r = new TextView(this);
            r.setText("⏳ 7日以上報告のない端末: " + stale + " 台");
            r.setTextColor(WARN);
            r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            sum.addView(r);
        }
        body.addView(sum);

        for (final Fleet.Device d : fleet.devices) {
            body.addView(deviceCard(d));
        }
    }

    private View deviceCard(final Fleet.Device d) {
        LinearLayout l = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView n = new TextView(this);
        n.setText((d.ransom > 0 ? "💣️ " : "") + d.model);
        n.setTextColor(TEXT);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mid.addView(n);

        String today = new java.text.SimpleDateFormat("yyyy-MM-dd",
                java.util.Locale.JAPAN).format(new java.util.Date());
        long ago = d.date == null ? -1 : daysBetween(d.date, today);
        TextView sub = new TextView(this);
        sub.setText("最終報告 " + d.date + (ago > 7 ? " ⏳" : "")
                + " / NG " + d.ngTitles.size() + " 件"
                + (d.sensitiveDocs > 0 ? " / 機密文書 " + d.sensitiveDocs : ""));
        sub.setTextColor(ago > 7 ? WARN : SUB);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mid.addView(sub);
        row.addView(mid);

        int col = d.score >= 80 ? OK : (d.score >= 60 ? WARN : BAD);
        TextView badge = new TextView(this);
        badge.setText(d.score < 0 ? "-" : String.valueOf(d.score));
        badge.setTextColor(0xFF0E1116);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        badge.setPadding(dp(12), dp(4), dp(12), dp(4));
        GradientDrawable g = new GradientDrawable();
        g.setColor(col);
        g.setCornerRadius(dp(8));
        badge.setBackground(g);
        row.addView(badge);

        l.addView(row);
        l.setOnClickListener(v -> deviceDetail(d));
        return l;
    }

    private void deviceDetail(Fleet.Device d) {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(12));
        box.setBackgroundColor(CARD);

        TextView h = new TextView(this);
        h.setText(d.model + "\nスコア " + d.score + " " + d.rank
                + " / 最終報告 " + d.date + " / 累計 " + d.reports + " 件");
        h.setTextColor(TEXT);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(h);

        if (d.latest != null) {
            JSONObject cat = d.latest.optJSONObject("カテゴリ別スコア");
            if (cat != null) {
                TextView ct = new TextView(this);
                ct.setText("\n【カテゴリ別】");
                ct.setTextColor(SUB);
                ct.setTypeface(Typeface.DEFAULT_BOLD);
                ct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                box.addView(ct);
                java.util.Iterator<String> it = cat.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    int v = cat.optInt(k);
                    TextView t = new TextView(this);
                    t.setText(k + ": " + v);
                    t.setTextColor(v >= 80 ? OK : (v >= 60 ? WARN : BAD));
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    box.addView(t);
                }
            }

            JSONArray checks = d.latest.optJSONArray("判定結果");
            if (checks != null) {
                TextView ct = new TextView(this);
                ct.setText("\n【NG項目と対策】");
                ct.setTextColor(SUB);
                ct.setTypeface(Typeface.DEFAULT_BOLD);
                ct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                box.addView(ct);
                int ng = 0;
                for (int i = 0; i < checks.length(); i++) {
                    JSONObject x = checks.optJSONObject(i);
                    if (x == null || !"NG".equals(x.optString("判定"))) {
                        continue;
                    }
                    ng++;
                    TextView t = new TextView(this);
                    t.setText("✕ [" + x.optInt("重み") + "] " + x.optString("項目")
                            + " — " + x.optString("詳細")
                            + "\n　→ " + x.optString("提案"));
                    t.setTextColor(BAD);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    t.setPadding(0, dp(4), 0, 0);
                    box.addView(t);
                }
                if (ng == 0) {
                    TextView t = new TextView(this);
                    t.setText("指摘事項なし");
                    t.setTextColor(OK);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                    box.addView(t);
                }
            }

            if (d.history != null && d.history.length() >= 2) {
                TextView ct = new TextView(this);
                ct.setText("\n【スコア推移】");
                ct.setTextColor(SUB);
                ct.setTypeface(Typeface.DEFAULT_BOLD);
                ct.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                box.addView(ct);
                int start = Math.max(0, d.history.length() - 14);
                StringBuilder sb = new StringBuilder();
                for (int i = start; i < d.history.length(); i++) {
                    JSONObject o = d.history.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(o.optString("d").substring(5)).append("  ")
                            .append(o.optInt("s"));
                }
                TextView t = new TextView(this);
                t.setText(sb.toString());
                t.setTextColor(TEXT);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                box.addView(t);
            }
        }

        sv.addView(box);
        new AlertDialog.Builder(this)
                .setView(sv)
                .setPositiveButton("閉じる", null)
                .show();
    }

    private static long daysBetween(String from, String to) {
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            return (f.parse(to).getTime() - f.parse(from).getTime()) / 86400000L;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---- ui helpers ----

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(12));
        g.setStroke(dp(1), LINE);
        l.setBackground(g);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(10);
        l.setLayoutParams(p);
        return l;
    }

    private View btn(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(0xFF0E1116);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACC);
        g.setCornerRadius(dp(10));
        b.setBackground(g);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(10);
        b.setLayoutParams(p);
        return b;
    }

    private View note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(SUB);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        t.setPadding(0, dp(4), 0, dp(8));
        return t;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
