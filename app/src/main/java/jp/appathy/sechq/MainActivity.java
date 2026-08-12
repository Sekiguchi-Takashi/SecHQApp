package jp.appathy.sechq;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    static final int BG = 0xFF0E1116;
    static final int CARD = 0xFF171B21;
    static final int LINE = 0xFF2A313A;
    static final int TEXT = 0xFFE6EDF3;
    static final int SUB = 0xFF8B949E;
    static final int OK = 0xFF3FB950;
    static final int WARN = 0xFFF0883E;
    static final int BAD = 0xFFF85149;
    static final int ACC = 0xFF58A6FF;

    static final String[] TABS = {
            "資産", "脆弱性", "認証", "感染予防", "機密情報", "アプリ", "ネットワーク",
            "物理・外出", "書類点検", "AI分析"
    };

    private FrameLayout content;
    private final List<TextView> tabViews = new ArrayList<>();
    private int current = 0;

    private ActivityResultLauncher<Intent> treeLauncher;
    private ActivityResultLauncher<Intent> saveLauncher;
    private ActivityResultLauncher<String[]> permLauncher;
    private String pendingSaveBody = "";

    private FileScanner.Result lastScan;
    private DocClassifier.Result lastDocs;
    private DeskInspector.Result lastInspection;
    private AppAuditor.Result lastAudit;
    private java.util.Set<String> excluded;
    private ActivityResultLauncher<Uri> cameraLauncher;
    private ActivityResultLauncher<String> pickLauncher;
    private Uri captureUri;

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (captureUri != null) {
            outState.putString("captureUri", captureUri.toString());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            String cu = savedInstanceState.getString("captureUri");
            if (cu != null) {
                captureUri = Uri.parse(cu);
            }
        }

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
                            Store.prefs(this).edit().putString("tree", uri.toString()).apply();
                            lastScan = null;
                            lastDocs = null;
                            render(3);
                        }
                    }
                });

        saveLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        if (uri != null) {
                            try {
                                OutputStream os = getContentResolver().openOutputStream(uri);
                                if (os != null) {
                                    os.write(pendingSaveBody.getBytes("UTF-8"));
                                    os.close();
                                }
                                toast("保存しました");
                            } catch (Exception e) {
                                toast("保存に失敗しました");
                            }
                        }
                    }
                });

        permLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                r -> render(current));

        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicture(),
                success -> {
                    if (success != null && success && captureUri != null) {
                        runInspection(captureUri);
                    }
                });

        pickLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null) {
                        runInspection(uri);
                    }
                });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        root.addView(buildHeader());
        root.addView(buildTabBar());

        content = new FrameLayout(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        content.setLayoutParams(lp);
        root.addView(content);

        setContentView(root);
        requestPerms();
        excluded = new java.util.HashSet<>(Store.prefs(this)
                .getStringSet("excluded", new java.util.HashSet<String>()));
        scheduleDaily();
        render(0);
    }

    // ---------------- shell ----------------

    private View buildHeader() {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(16), dp(16), dp(16), dp(10));
        h.setBackgroundColor(BG);

        TextView t = new TextView(this);
        t.setText("セキュリティ対策2.0");
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        h.addView(t);

        TextView s = new TextView(this);
        s.setText("Appathy / SecHQ — オントロジー駆動セキュリティ");
        s.setTextColor(SUB);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        h.addView(s);
        return h;
    }

    private View buildTabBar() {
        android.widget.HorizontalScrollView sv = new android.widget.HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(10), 0, dp(10), dp(10));

        for (int i = 0; i < TABS.length; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(TABS[i]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            tv.setPadding(dp(14), dp(8), dp(14), dp(8));
            tv.setOnClickListener(v -> render(idx));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.rightMargin = dp(6);
            tv.setLayoutParams(p);
            tabViews.add(tv);
            bar.addView(tv);
        }
        sv.addView(bar);
        return sv;
    }

    private void styleTabs() {
        for (int i = 0; i < tabViews.size(); i++) {
            TextView tv = tabViews.get(i);
            boolean on = i == current;
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(18));
            g.setColor(on ? ACC : CARD);
            g.setStroke(dp(1), on ? ACC : LINE);
            tv.setBackground(g);
            tv.setTextColor(on ? 0xFF0E1116 : SUB);
            tv.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        }
    }

    private void render(int index) {
        current = index;
        styleTabs();
        content.removeAllViews();

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout body = col();
        switch (index) {
            case 0:
                screenAsset(body);
                break;
            case 1:
                screenVuln(body);
                break;
            case 2:
                screenAuth(body);
                break;
            case 3:
                screenFiles(body);
                break;
            case 4:
                screenDocs(body);
                break;
            case 5:
                screenApps(body);
                break;
            case 6:
                screenNetwork(body);
                break;
            case 7:
                screenPhysical(body);
                break;
            case 8:
                screenDesk(body);
                break;
            default:
                screenAi(body);
                break;
        }
        sv.addView(body);
        content.addView(sv);
    }

    // ---------------- screens ----------------

    private void screenAsset(LinearLayout v) {
        v.addView(sectionTitle("1. 資産管理", "端末・OS・ブラウザの現況を収集します"));

        LinkedHashMap<String, String> a = Collector.asset(this);
        LinearLayout c = card();
        for (Map.Entry<String, String> e : a.entrySet()) {
            kv(c, e.getKey(), e.getValue());
        }
        v.addView(c);

        v.addView(btn("スナップショットを保存", vw -> {
            JSONObject o = new JSONObject();
            try {
                o.put("asset", Collector.toJson(Collector.asset(this)));
                o.put("network", Collector.toJson(Collector.network(this)));
                o.put("at", Collector.now());
            } catch (Exception ignored) {
            }
            Store.saveObject(this, Store.F_SNAPSHOT, o);
            toast("スナップショットを保存しました");
        }));

        v.addView(btn("資産JSONを書き出す (Drive等へ)", vw -> {
            JSONObject o = Collector.toJson(Collector.asset(this));
            saveJson("asset_" + Collector.today() + ".json", pretty(o));
        }));

        JSONObject snap = Store.loadObject(this, Store.F_SNAPSHOT);
        if (snap.has("at")) {
            v.addView(note("前回スナップショット: " + snap.optString("at")));
        }
    }

    private void screenVuln(LinearLayout v) {
        v.addView(sectionTitle("2. 脆弱性管理", "オントロジーの脆弱性ノードに対するルール判定"));

        List<RiskEngine.Check> checks = allChecks();
        int s = RiskEngine.score(checks);
        v.addView(scoreCard(s));

        for (RiskEngine.Check c : checks) {
            v.addView(checkCard(c));
        }
    }

    private void screenAuth(LinearLayout v) {
        v.addView(sectionTitle("3. ID・認証管理", "SaaSごとのMFA・パスワード鮮度を管理します"));

        JSONArray arr = Store.loadArray(this, Store.F_ACCOUNTS);
        if (arr.length() == 0) {
            v.addView(note("登録されたアカウントはありません"));
        }
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) {
                continue;
            }
            final int idx = i;
            LinearLayout c = card();
            TextView t = new TextView(this);
            t.setText(o.optString("name", "(名称なし)"));
            t.setTextColor(TEXT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            c.addView(t);

            boolean mfa = o.optBoolean("mfa", false);
            kvColor(c, "MFA", mfa ? "有効" : "未設定", mfa ? OK : BAD);
            long d = RiskEngine.daysSince(o.optString("pw", ""));
            String pwText = d > 9000 ? "未登録" : o.optString("pw") + " (" + d + "日前)";
            kvColor(c, "パスワード更新", pwText, d > 180 ? WARN : OK);
            String role = o.optString("role", "");
            if (!role.isEmpty()) {
                kv(c, "権限", role);
            }
            c.addView(btn("削除", vw -> {
                JSONArray cur = Store.loadArray(this, Store.F_ACCOUNTS);
                JSONArray out = new JSONArray();
                for (int j = 0; j < cur.length(); j++) {
                    if (j != idx) {
                        out.put(cur.opt(j));
                    }
                }
                Store.saveArray(this, Store.F_ACCOUNTS, out);
                render(2);
            }));
            v.addView(c);
        }

        v.addView(btn("アカウントを追加", vw -> accountDialog()));
    }

    private void accountDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(0));

        final EditText name = new EditText(this);
        name.setHint("サービス名 (例: Google Workspace)");
        box.addView(name);

        final EditText role = new EditText(this);
        role.setHint("権限 (例: 管理者 / 一般)");
        box.addView(role);

        final EditText pw = new EditText(this);
        pw.setHint("パスワード最終更新日 (yyyy-MM-dd)");
        pw.setText(Collector.today());
        box.addView(pw);

        final CheckBox mfa = new CheckBox(this);
        mfa.setText("MFA 有効");
        box.addView(mfa);

        new AlertDialog.Builder(this)
                .setTitle("アカウント追加")
                .setView(box)
                .setPositiveButton("追加", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        toast("サービス名を入力してください");
                        return;
                    }
                    JSONArray arr = Store.loadArray(this, Store.F_ACCOUNTS);
                    try {
                        JSONObject o = new JSONObject();
                        o.put("name", n);
                        o.put("role", role.getText().toString().trim());
                        o.put("pw", pw.getText().toString().trim());
                        o.put("mfa", mfa.isChecked());
                        arr.put(o);
                    } catch (Exception ignored) {
                    }
                    Store.saveArray(this, Store.F_ACCOUNTS, arr);
                    render(2);
                })
                .setNegativeButton("キャンセル", null)
                .show();
    }

    private void screenFiles(LinearLayout v) {
        v.addView(sectionTitle("4. AI感染予防", "危険拡張子・二重拡張子・暗号化痕跡を検出します"));

        String tree = Store.prefs(this).getString("tree", "");
        LinearLayout c = card();
        kv(c, "監視フォルダ", tree.isEmpty() ? "未選択" : Uri.decode(tree));
        v.addView(c);

        v.addView(btn("監視フォルダを選択 (Download推奨)", vw -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            treeLauncher.launch(i);
        }));

        v.addView(btn("スキャン実行", vw -> {
            toast("スキャン中です");
            lastScan = null;
            ensureScan();
        }));

        if (lastScan != null) {
            LinearLayout r = card();
            if (lastScan.error != null) {
                kvColor(r, "結果", lastScan.error, WARN);
            } else {
                kv(r, "検査ファイル数", lastScan.total + " 件");
                kvColor(r, "暗号化拡張子", lastScan.ransom.size() + " 件",
                        lastScan.ransom.isEmpty() ? OK : BAD);
                kvColor(r, "二重拡張子", lastScan.doubleExt.size() + " 件",
                        lastScan.doubleExt.isEmpty() ? OK : BAD);
                kvColor(r, "危険拡張子", lastScan.danger.size() + " 件",
                        lastScan.danger.isEmpty() ? OK : WARN);
            }
            v.addView(r);

            addFileList(v, "暗号化拡張子 (ランサムウェア疑い)", lastScan.ransom, BAD);
            addFileList(v, "二重拡張子 (偽装疑い)", lastScan.doubleExt, BAD);
            addFileList(v, "危険拡張子", lastScan.danger, WARN);
        } else {
            v.addView(note("スキャン未実行です"));
        }
    }

    private void addFileList(LinearLayout v, String title, List<String> list, int color) {
        if (list == null || list.isEmpty()) {
            return;
        }
        LinearLayout c = card();
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        int n = Math.min(list.size(), 30);
        for (int i = 0; i < n; i++) {
            TextView f = new TextView(this);
            f.setText("・" + list.get(i));
            f.setTextColor(TEXT);
            f.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            c.addView(f);
        }
        if (list.size() > n) {
            c.addView(note("他 " + (list.size() - n) + " 件"));
        }
        v.addView(c);
    }

    private void screenDocs(LinearLayout v) {
        v.addView(sectionTitle("4. AI機密情報分類",
                "Office・PDF・テキストを解析し、機密度を判定してラベルを付与します"));

        String tree = Store.prefs(this).getString("tree", "");
        if (tree.isEmpty()) {
            v.addView(note("監視フォルダが未選択です。感染予防タブで選択してください"));
        }

        v.addView(btn("文書を解析", vw -> {
            toast("解析中です");
            analyzeDocs();
        }));

        if (!excluded.isEmpty()) {
            v.addView(btn("除外リストを空にする (" + excluded.size() + "件)", vw -> {
                excluded.clear();
                Store.prefs(this).edit()
                        .putStringSet("excluded", new java.util.HashSet<String>()).apply();
                toast("除外リストを初期化しました");
                render(4);
            }));
        }

        if (lastDocs == null) {
            v.addView(note("解析未実行です"));
            return;
        }

        LinearLayout s = card();
        if (lastDocs.error != null) {
            kvColor(s, "結果", lastDocs.error, WARN);
            v.addView(s);
            return;
        }
        kv(s, "解析文書数", lastDocs.scanned + " 件");
        kvColor(s, DocClassifier.L_TOP, lastDocs.count(DocClassifier.L_TOP) + " 件",
                lastDocs.count(DocClassifier.L_TOP) == 0 ? OK : BAD);
        kvColor(s, DocClassifier.L_CONF, lastDocs.count(DocClassifier.L_CONF) + " 件",
                lastDocs.count(DocClassifier.L_CONF) == 0 ? OK : WARN);
        kv(s, DocClassifier.L_INTERNAL, lastDocs.count(DocClassifier.L_INTERNAL) + " 件");
        kv(s, DocClassifier.L_PUBLIC, lastDocs.count(DocClassifier.L_PUBLIC) + " 件");
        v.addView(s);

        if (lastDocs.sensitive() > 0) {
            v.addView(btn("機密文書にラベルを一括付与", vw -> {
                int n = 0;
                for (DocClassifier.Doc d : lastDocs.docs) {
                    boolean high = DocClassifier.L_TOP.equals(d.label)
                            || DocClassifier.L_CONF.equals(d.label);
                    if (high && DocClassifier.applyLabel(this, d)) {
                        n++;
                    }
                }
                toast(n + " 件にラベルを付与しました");
                analyzeDocs();
            }));
        }

        int shown = 0;
        for (DocClassifier.Doc d : lastDocs.docs) {
            if (shown >= 40) {
                break;
            }
            if (DocClassifier.L_PUBLIC.equals(d.label)) {
                continue;
            }
            shown++;
            v.addView(docCard(d));
        }
        if (shown == 0) {
            v.addView(note("機密度が高い文書は検出されませんでした"));
        }
    }

    private void addHistoryGraph(LinearLayout v) {
        JSONArray h = Store.loadArray(this, DailyWorker.F_HISTORY);
        if (h.length() < 2) {
            return;
        }
        LinearLayout c = card();
        TextView t = new TextView(this);
        t.setText("スコア推移 (最新14日)");
        t.setTextColor(SUB);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        int start = Math.max(0, h.length() - 14);
        for (int i = start; i < h.length(); i++) {
            JSONObject o = h.optJSONObject(i);
            if (o == null) {
                continue;
            }
            int sc = o.optInt("s");
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(3), 0, dp(3));
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView d = new TextView(this);
            d.setText(o.optString("d").substring(5));
            d.setTextColor(SUB);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            d.setLayoutParams(new LinearLayout.LayoutParams(dp(48),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(d);

            View bar = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setColor(sc >= 80 ? OK : (sc >= 60 ? WARN : BAD));
            g.setCornerRadius(dp(3));
            bar.setBackground(g);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(10), sc);
            bar.setLayoutParams(bp);
            row.addView(bar);

            View gap = new View(this);
            gap.setLayoutParams(new LinearLayout.LayoutParams(0, dp(10),
                    Math.max(1, 100 - sc)));
            row.addView(gap);

            TextView sv = new TextView(this);
            sv.setText(String.valueOf(sc));
            sv.setTextColor(TEXT);
            sv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            sv.setPadding(dp(6), 0, 0, 0);
            row.addView(sv);
            c.addView(row);
        }
        v.addView(c);
    }

    private void scheduleDaily() {
        try {
            androidx.work.PeriodicWorkRequest req =
                    new androidx.work.PeriodicWorkRequest.Builder(
                            DailyWorker.class, 24, java.util.concurrent.TimeUnit.HOURS)
                            .build();
            androidx.work.WorkManager.getInstance(this)
                    .enqueueUniquePeriodicWork("sechq_daily",
                            androidx.work.ExistingPeriodicWorkPolicy.KEEP, req);
        } catch (Exception ignored) {
        }
    }

    private void screenApps(LinearLayout v) {
        v.addView(sectionTitle("1b. アプリ棚卸し",
                "インストール済みアプリの危険権限を検査します"));

        v.addView(btn("棚卸しを実行", vw -> {
            toast("検査中です");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final AppAuditor.Result r = AppAuditor.audit(MainActivity.this);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            lastAudit = r;
                            if (current == 5) {
                                render(5);
                            }
                        }
                    });
                }
            }).start();
        }));

        if (lastAudit == null) {
            v.addView(note("棚卸し未実行です"));
            return;
        }

        LinearLayout c = card();
        kv(c, "インストール済み", lastAudit.total + " 件");
        kvColor(c, "高リスク (スコア5以上)", lastAudit.flagged + " 件",
                lastAudit.flagged == 0 ? OK : WARN);
        kv(c, "権限保有 (非システム)", lastAudit.apps.size() + " 件");
        v.addView(c);

        int shown = 0;
        for (AppAuditor.AppRisk a : lastAudit.apps) {
            if (shown >= 40) {
                break;
            }
            shown++;
            LinearLayout l = card();
            TextView t = new TextView(this);
            t.setText(a.label + "  (スコア " + a.score + ")");
            t.setTextColor(a.score >= 5 ? WARN : TEXT);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            l.addView(t);
            TextView pk = new TextView(this);
            pk.setText(a.pkg);
            pk.setTextColor(SUB);
            pk.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            l.addView(pk);
            StringBuilder sb = new StringBuilder();
            for (String x : a.perms) {
                if (sb.length() > 0) {
                    sb.append(" / ");
                }
                sb.append(x);
            }
            TextView pm = new TextView(this);
            pm.setText(sb.toString());
            pm.setTextColor(SUB);
            pm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            pm.setPadding(0, dp(4), 0, 0);
            l.addView(pm);
            v.addView(l);
        }
    }

    private boolean analyzing;

    private void analyzeDocs() {
        if (analyzing) {
            return;
        }
        analyzing = true;
        final String tree = Store.prefs(this).getString("tree", "");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final DocClassifier.Result r = DocClassifier.scan(MainActivity.this, tree, excluded);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        lastDocs = r;
                        analyzing = false;
                        if (current == 4) {
                            render(4);
                        }
                    }
                });
            }
        }).start();
    }

    private View docCard(DocClassifier.Doc d) {
        LinearLayout l = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);

        int col = DocClassifier.L_TOP.equals(d.label) ? BAD
                : (DocClassifier.L_CONF.equals(d.label) ? WARN : ACC);
        TextView badge = new TextView(this);
        badge.setText(d.label);
        badge.setTextColor(0xFF0E1116);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable g = new GradientDrawable();
        g.setColor(col);
        g.setCornerRadius(dp(6));
        badge.setBackground(g);
        head.addView(badge);

        TextView sc = new TextView(this);
        sc.setText("  " + d.type + " / スコア " + d.score);
        sc.setTextColor(SUB);
        sc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        head.addView(sc);
        l.addView(head);

        TextView t = new TextView(this);
        t.setText(d.name);
        t.setTextColor(TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(0, dp(6), 0, 0);
        l.addView(t);

        TextView h = new TextView(this);
        StringBuilder sb = new StringBuilder();
        for (String x : d.hits) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(x);
        }
        h.setText("検出根拠: " + (sb.length() == 0 ? "なし" : sb.toString())
                + (d.textOk ? "" : "（本文抽出不可・ファイル名のみ判定）"));
        h.setTextColor(SUB);
        h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        h.setPadding(0, dp(4), 0, 0);
        l.addView(h);

        if (!d.name.startsWith("【")) {
            l.addView(btn("この文書にラベルを付与", vw -> {
                if (DocClassifier.applyLabel(this, d)) {
                    toast("ラベルを付与しました");
                    analyzeDocs();
                } else {
                    toast("付与できませんでした");
                }
            }));
        }
        l.addView(btn("誤検知として除外", vw -> {
            excluded.add(d.name);
            Store.prefs(this).edit()
                    .putStringSet("excluded", new java.util.HashSet<>(excluded)).apply();
            toast("除外しました");
            analyzeDocs();
        }));
        return l;
    }

    private void screenNetwork(LinearLayout v) {
        v.addView(sectionTitle("7. AIネットワーク管理", "SSID・VPN・DNSの安全判定"));

        LinkedHashMap<String, String> n = Collector.network(this);
        LinearLayout c = card();
        for (Map.Entry<String, String> e : n.entrySet()) {
            kv(c, e.getKey(), e.getValue());
        }
        v.addView(c);

        boolean wifi = Collector.isWifi(this);
        boolean vpn = Collector.vpnActive(this);
        boolean pdns = Collector.privateDnsActive(this);

        LinearLayout j = card();
        TextView t = new TextView(this);
        String verdict;
        int col;
        if (wifi && !vpn && !pdns) {
            verdict = "判定: 要注意 — Wi-Fi接続中にVPNもプライベートDNSも無効です";
            col = BAD;
        } else if (wifi && !vpn) {
            verdict = "判定: 注意 — 社外Wi-FiではVPNを推奨します";
            col = WARN;
        } else {
            verdict = "判定: 良好 — 通信経路の保護は概ね確保されています";
            col = OK;
        }
        t.setText(verdict);
        t.setTextColor(col);
        j.addView(t);
        v.addView(j);

        v.addView(btn("再取得", vw -> render(6)));
    }

    private void screenPhysical(LinearLayout v) {
        v.addView(sectionTitle("5-6. AI物理セキュリティ・外出管理", "拠点からの距離で在席／外出を判定します"));

        SharedPreferences p = Store.prefs(this);
        LinearLayout c = card();
        if (p.contains("home_lat")) {
            kv(c, "拠点", fmt(p.getFloat("home_lat", 0)) + ", " + fmt(p.getFloat("home_lng", 0)));
        } else {
            kvColor(c, "拠点", "未登録", WARN);
        }
        if (p.contains("last_lat")) {
            kv(c, "最終取得地点", fmt(p.getFloat("last_lat", 0)) + ", " + fmt(p.getFloat("last_lng", 0)));
            kv(c, "取得日時", p.getString("last_at", "-"));
            if (p.contains("home_lat")) {
                float[] r = new float[1];
                Location.distanceBetween(p.getFloat("home_lat", 0), p.getFloat("home_lng", 0),
                        p.getFloat("last_lat", 0), p.getFloat("last_lng", 0), r);
                int m = (int) r[0];
                kvColor(c, "拠点からの距離", m + " m", m > 500 ? WARN : OK);
                kvColor(c, "状態", m > 500 ? "外出中" : "拠点内", m > 500 ? WARN : OK);
            }
        }
        v.addView(c);

        v.addView(btn("現在地を取得", vw -> fetchLocation(false)));
        v.addView(btn("現在地を拠点として登録", vw -> fetchLocation(true)));

        JSONArray log = Store.loadArray(this, Store.F_LOCATION);
        if (log.length() > 0) {
            LinearLayout l = card();
            TextView t = new TextView(this);
            t.setText("取得履歴 (最新20件)");
            t.setTextColor(SUB);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            l.addView(t);
            int start = Math.max(0, log.length() - 20);
            for (int i = log.length() - 1; i >= start; i--) {
                JSONObject o = log.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                TextView e = new TextView(this);
                e.setText(o.optString("t") + "  " + o.optInt("dist") + "m  " + o.optString("state"));
                e.setTextColor(TEXT);
                e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                l.addView(e);
            }
            v.addView(l);
        }

        v.addView(note("※ 机上の書類放置検知は「書類点検」タブで行えます"));
    }

    private void screenDesk(LinearLayout v) {
        v.addView(sectionTitle("5. AI物理セキュリティ（書類点検）",
                "机上を撮影し、文字認識で機密書類の放置を検出します"));

        v.addView(btn("撮影して点検", vw -> {
            try {
                java.io.File dir = new java.io.File(getCacheDir(), "captures");
                if (!dir.exists()) {
                    dir.mkdirs();
                }
                java.io.File f = new java.io.File(dir, "desk.jpg");
                captureUri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", f);
                cameraLauncher.launch(captureUri);
            } catch (Exception e) {
                toast("カメラを起動できませんでした");
            }
        }));

        v.addView(btn("画像を選んで点検", vw -> pickLauncher.launch("image/*")));

        if (lastInspection != null) {
            LinearLayout c = card();
            if (lastInspection.error != null) {
                kvColor(c, "結果", lastInspection.error, WARN);
            } else {
                boolean risky = DocClassifier.L_TOP.equals(lastInspection.label)
                        || DocClassifier.L_CONF.equals(lastInspection.label);
                kvColor(c, "判定", risky ? "機密書類の放置を検出" : "問題は検出されませんでした",
                        risky ? BAD : OK);
                kv(c, "分類", lastInspection.label + " (スコア " + lastInspection.score + ")");
                kv(c, "認識行数", lastInspection.lines + " 行");
                StringBuilder sb = new StringBuilder();
                if (lastInspection.hits != null) {
                    for (String h : lastInspection.hits) {
                        if (sb.length() > 0) {
                            sb.append(" / ");
                        }
                        sb.append(h);
                    }
                }
                kv(c, "検出根拠", sb.length() == 0 ? "なし" : sb.toString());
                if (lastInspection.excerpt != null && !lastInspection.excerpt.trim().isEmpty()) {
                    TextView t = new TextView(this);
                    t.setText("認識テキスト（抜粋）\n" + lastInspection.excerpt);
                    t.setTextColor(SUB);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                    t.setPadding(0, dp(8), 0, 0);
                    c.addView(t);
                }
            }
            v.addView(c);
        }

        JSONArray log = Store.loadArray(this, DeskInspector.F_INSPECTIONS);
        if (log.length() > 0) {
            LinearLayout l = card();
            TextView t = new TextView(this);
            t.setText("点検履歴 (最新15件)");
            t.setTextColor(SUB);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            l.addView(t);
            int start = Math.max(0, log.length() - 15);
            for (int i = log.length() - 1; i >= start; i--) {
                JSONObject o = log.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String lab = o.optString("label");
                TextView e = new TextView(this);
                e.setText(o.optString("t") + "  " + lab + "  (" + o.optInt("score") + ")");
                e.setTextColor(DocClassifier.L_TOP.equals(lab) || DocClassifier.L_CONF.equals(lab)
                        ? BAD : TEXT);
                e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                l.addView(e);
            }
            v.addView(l);
        } else {
            v.addView(note("点検履歴はありません"));
        }

        v.addView(note("※ 画像は端末内で処理され、送信も保存もされません（結果のみ記録）"));
    }

    private void runInspection(Uri uri) {
        toast("点検中です");
        DeskInspector.inspect(this, uri, r -> {
            lastInspection = r;
            if (r.error == null) {
                DeskInspector.record(this, r);
            }
            if (current == 8) {
                render(8);
            }
        });
    }

    private void screenAi(LinearLayout v) {
        v.addView(sectionTitle("8. AI統合分析", "全カテゴリを統合し、リスクスコアと改善提案を生成します"));

        List<RiskEngine.Check> checks = allChecks();
        int s = RiskEngine.score(checks);
        v.addView(scoreCard(s));
        DailyWorker.appendHistory(this, s);
        addHistoryGraph(v);

        LinearLayout cat = card();
        TextView ct = new TextView(this);
        ct.setText("カテゴリ別スコア");
        ct.setTextColor(SUB);
        ct.setTypeface(Typeface.DEFAULT_BOLD);
        cat.addView(ct);
        LinkedHashMap<String, Integer> m = RiskEngine.byCategory(checks);
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            kvColor(cat, e.getKey(), e.getValue() + " / 100",
                    e.getValue() >= 80 ? OK : (e.getValue() >= 60 ? WARN : BAD));
        }
        v.addView(cat);

        List<RiskEngine.Check> f = RiskEngine.failures(checks);
        LinearLayout adv = card();
        TextView at = new TextView(this);
        at.setText("改善提案 (重要度順)");
        at.setTextColor(SUB);
        at.setTypeface(Typeface.DEFAULT_BOLD);
        adv.addView(at);
        if (f.isEmpty()) {
            adv.addView(note("指摘事項はありません"));
        }
        for (RiskEngine.Check c : f) {
            TextView t = new TextView(this);
            t.setText("[" + c.weight + "] " + c.category + " / " + c.title + "\n　→ " + c.advice);
            t.setTextColor(TEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setPadding(0, dp(6), 0, dp(6));
            adv.addView(t);
        }
        v.addView(adv);

        v.addView(btn("AI用プロンプトをコピー", vw -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("sechq", buildPrompt(checks, s)));
                toast("コピーしました");
            }
        }));
        v.addView(btn("レポートを共有", vw -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, buildPrompt(checks, s));
            startActivity(Intent.createChooser(i, "レポートを共有"));
        }));
        v.addView(btn("統合JSONを書き出す", vw ->
                saveJson("sechq_" + Collector.today() + ".json", pretty(buildJson(checks, s)))));
    }

    // ---------------- logic helpers ----------------

    private boolean scanning;

    private List<RiskEngine.Check> allChecks() {
        JSONArray accounts = Store.loadArray(this, Store.F_ACCOUNTS);
        ensureScan();
        return RiskEngine.run(this, accounts, lastScan, lastDocs, lastAudit);
    }

    private void ensureScan() {
        if (lastScan != null || scanning) {
            return;
        }
        final String tree = Store.prefs(this).getString("tree", "");
        if (tree.isEmpty()) {
            return;
        }
        scanning = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final FileScanner.Result r = FileScanner.scan(MainActivity.this, tree);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        lastScan = r;
                        scanning = false;
                        render(current);
                    }
                });
            }
        }).start();
    }

    private JSONObject buildJson(List<RiskEngine.Check> checks, int score) {
        JSONObject o = new JSONObject();
        try {
            o.put("生成日時", Collector.now());
            o.put("総合スコア", score);
            o.put("評価", RiskEngine.rank(score));
            o.put("資産", Collector.toJson(Collector.asset(this)));
            o.put("ネットワーク", Collector.toJson(Collector.network(this)));
            o.put("アカウント", Store.loadArray(this, Store.F_ACCOUNTS));
            JSONObject insp = DeskInspector.latest(this);
            if (insp != null) {
                o.put("直近の書類点検", insp);
            }
            JSONArray ca = new JSONArray();
            for (RiskEngine.Check c : checks) {
                JSONObject x = new JSONObject();
                x.put("分類", c.category);
                x.put("項目", c.title);
                x.put("判定", c.ok ? "OK" : "NG");
                x.put("重み", c.weight);
                x.put("詳細", c.detail);
                if (!c.ok) {
                    x.put("提案", c.advice);
                }
                ca.put(x);
            }
            o.put("判定結果", ca);
            if (lastDocs != null && lastDocs.error == null && lastDocs.scanned > 0) {
                JSONArray da = new JSONArray();
                for (DocClassifier.Doc d : lastDocs.docs) {
                    if (DocClassifier.L_PUBLIC.equals(d.label)) {
                        continue;
                    }
                    JSONObject x = new JSONObject();
                    x.put("ファイル名", d.name);
                    x.put("種別", d.type);
                    x.put("ラベル", d.label);
                    x.put("スコア", d.score);
                    x.put("根拠", new JSONArray(d.hits));
                    da.put(x);
                }
                JSONObject doc = new JSONObject();
                doc.put("解析件数", lastDocs.scanned);
                doc.put("機密相当", lastDocs.sensitive());
                doc.put("一覧", da);
                o.put("機密情報分類", doc);
            }
            JSONObject cat = new JSONObject();
            for (Map.Entry<String, Integer> e : RiskEngine.byCategory(checks).entrySet()) {
                cat.put(e.getKey(), e.getValue());
            }
            o.put("カテゴリ別スコア", cat);
        } catch (Exception ignored) {
        }
        return o;
    }

    private String buildPrompt(List<RiskEngine.Check> checks, int score) {
        StringBuilder sb = new StringBuilder();
        sb.append("あなたは情報セキュリティコンサルタントです。");
        sb.append("以下はセキュリティ対策2.0アプリが収集した端末の実測データです。");
        sb.append("オントロジー（資産・脆弱性・脅威・対策・インシデント）に沿って分析し、");
        sb.append("優先度付きの改善計画を提示してください。\n\n");
        sb.append(pretty(buildJson(checks, score)));
        return sb.toString();
    }

    private String pretty(JSONObject o) {
        try {
            return o.toString(2);
        } catch (Exception e) {
            return o.toString();
        }
    }

    private void saveJson(String name, String body) {
        pendingSaveBody = body;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, name);
        saveLauncher.launch(i);
    }

    private void fetchLocation(final boolean asHome) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPerms();
            toast("位置情報の許可が必要です");
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            toast("位置情報を利用できません");
            return;
        }
        Location best = null;
        try {
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
        } catch (SecurityException ignored) {
        }
        long age = best == null ? Long.MAX_VALUE
                : System.currentTimeMillis() - best.getTime();
        if (best != null && age <= 120000) {
            applyLocation(best, asHome);
            return;
        }
        toast("測位中です");
        final boolean[] applied = {false};
        try {
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (!applied[0]) {
                        applied[0] = true;
                        applyLocation(location, asHome);
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            }, getMainLooper());
        } catch (Exception e) {
            if (best != null) {
                applyLocation(best, asHome);
            } else {
                toast("測位できませんでした。位置情報を確認してください");
            }
        }
    }

    private void applyLocation(Location l, boolean asHome) {
        SharedPreferences.Editor e = Store.prefs(this).edit();
        if (asHome) {
            e.putFloat("home_lat", (float) l.getLatitude());
            e.putFloat("home_lng", (float) l.getLongitude());
        }
        e.putFloat("last_lat", (float) l.getLatitude());
        e.putFloat("last_lng", (float) l.getLongitude());
        e.putString("last_at", Collector.now());
        e.apply();

        SharedPreferences p = Store.prefs(this);
        int dist = 0;
        if (p.contains("home_lat")) {
            float[] r = new float[1];
            Location.distanceBetween(p.getFloat("home_lat", 0), p.getFloat("home_lng", 0),
                    l.getLatitude(), l.getLongitude(), r);
            dist = (int) r[0];
        }
        JSONArray log = Store.loadArray(this, Store.F_LOCATION);
        try {
            JSONObject o = new JSONObject();
            o.put("t", Collector.now());
            o.put("lat", l.getLatitude());
            o.put("lng", l.getLongitude());
            o.put("dist", dist);
            o.put("state", dist > 500 ? "外出中" : "拠点内");
            log.put(o);
        } catch (Exception ignored) {
        }
        while (log.length() > 200) {
            log.remove(0);
        }
        Store.saveArray(this, Store.F_LOCATION, log);
        toast(asHome ? "拠点を登録しました" : "現在地を記録しました");
        if (current == 7) {
            render(7);
        }
    }

    private void requestPerms() {
        List<String> need = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            permLauncher.launch(need.toArray(new String[0]));
        }
    }

    // ---------------- ui helpers ----------------

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private LinearLayout col() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(4), dp(14), dp(28));
        return l;
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

    private View sectionTitle(String title, String desc) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, dp(4), 0, dp(10));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        l.addView(t);
        TextView d = new TextView(this);
        d.setText(desc);
        d.setTextColor(SUB);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        l.addView(d);
        return l;
    }

    private View scoreCard(int s) {
        LinearLayout c = card();
        TextView t = new TextView(this);
        t.setText(String.valueOf(s));
        t.setTextColor(s >= 80 ? OK : (s >= 60 ? WARN : BAD));
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        t.setGravity(Gravity.CENTER);
        c.addView(t);

        TextView r = new TextView(this);
        r.setText("総合リスクスコア　" + RiskEngine.rank(s));
        r.setTextColor(SUB);
        r.setGravity(Gravity.CENTER);
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        c.addView(r);
        return c;
    }

    private View checkCard(RiskEngine.Check c) {
        LinearLayout l = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);

        TextView badge = new TextView(this);
        badge.setText(c.ok ? "OK" : "NG");
        badge.setTextColor(0xFF0E1116);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable g = new GradientDrawable();
        g.setColor(c.ok ? OK : BAD);
        g.setCornerRadius(dp(6));
        badge.setBackground(g);
        head.addView(badge);

        TextView t = new TextView(this);
        t.setText("  " + c.category + " / " + c.title);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        head.addView(t);
        l.addView(head);

        TextView d = new TextView(this);
        d.setText(c.detail);
        d.setTextColor(SUB);
        d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        d.setPadding(0, dp(6), 0, 0);
        l.addView(d);

        if (!c.ok) {
            TextView a = new TextView(this);
            a.setText("対策: " + c.advice);
            a.setTextColor(WARN);
            a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            a.setPadding(0, dp(4), 0, 0);
            l.addView(a);
        }
        return l;
    }

    private void kv(LinearLayout p, String k, String v) {
        kvColor(p, k, v, TEXT);
    }

    private void kvColor(LinearLayout p, String k, String v, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView a = new TextView(this);
        a.setText(k);
        a.setTextColor(SUB);
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        a.setLayoutParams(new LinearLayout.LayoutParams(dp(120), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(a);

        TextView b = new TextView(this);
        b.setText(v);
        b.setTextColor(color);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        b.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(b);
        p.addView(row);
    }

    private View note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(SUB);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        t.setPadding(0, dp(4), 0, dp(8));
        return t;
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

    private String fmt(float f) {
        return String.format(Locale.US, "%.5f", f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
