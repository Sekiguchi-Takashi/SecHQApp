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
            "資産", "脆弱性", "認証", "感染予防", "ネットワーク", "物理・外出", "AI分析"
    };

    private FrameLayout content;
    private final List<TextView> tabViews = new ArrayList<>();
    private int current = 0;

    private ActivityResultLauncher<Intent> treeLauncher;
    private ActivityResultLauncher<Intent> saveLauncher;
    private ActivityResultLauncher<String[]> permLauncher;
    private String pendingSaveBody = "";

    private FileScanner.Result lastScan;

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
                            Store.prefs(this).edit().putString("tree", uri.toString()).apply();
                            lastScan = null;
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
                screenNetwork(body);
                break;
            case 5:
                screenPhysical(body);
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
            lastScan = FileScanner.scan(this, Store.prefs(this).getString("tree", ""));
            render(3);
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

        v.addView(btn("再取得", vw -> render(4)));
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

        v.addView(note("※ 撮影による書類放置検知はv1.1で追加予定です"));
    }

    private void screenAi(LinearLayout v) {
        v.addView(sectionTitle("8. AI統合分析", "全カテゴリを統合し、リスクスコアと改善提案を生成します"));

        List<RiskEngine.Check> checks = allChecks();
        int s = RiskEngine.score(checks);
        v.addView(scoreCard(s));

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

    private List<RiskEngine.Check> allChecks() {
        JSONArray accounts = Store.loadArray(this, Store.F_ACCOUNTS);
        if (lastScan == null) {
            String tree = Store.prefs(this).getString("tree", "");
            if (!tree.isEmpty()) {
                lastScan = FileScanner.scan(this, tree);
            }
        }
        return RiskEngine.run(this, accounts, lastScan);
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
        if (best != null) {
            applyLocation(best, asHome);
        } else {
            toast("測位中です。少し待って再度お試しください");
        }
        try {
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    applyLocation(location, asHome);
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
        } catch (Exception ignored) {
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
        if (current == 5) {
            render(5);
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
