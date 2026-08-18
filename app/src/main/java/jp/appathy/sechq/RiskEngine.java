package jp.appathy.sechq;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class RiskEngine {

    public static class Check {
        public String category;
        public String title;
        public String detail;
        public String advice;
        public boolean ok;
        public int weight;

        Check(String category, String title, boolean ok, int weight, String detail, String advice) {
            this.category = category;
            this.title = title;
            this.ok = ok;
            this.weight = weight;
            this.detail = detail;
            this.advice = advice;
        }
    }

    public static final String C_ASSET = "資産";
    public static final String C_AUTH = "認証";
    public static final String C_ENDPOINT = "エンドポイント";
    public static final String C_NETWORK = "ネットワーク";
    public static final String C_PHYSICAL = "物理";
    public static final String C_DATA = "情報資産";

    public static final String C_APPS = "アプリ";

    public static List<Check> run(Context c, JSONArray accounts, FileScanner.Result files,
                                  DocClassifier.Result docs) {
        return run(c, accounts, files, docs, null);
    }

    public static List<Check> run(Context c, JSONArray accounts, FileScanner.Result files,
                                  DocClassifier.Result docs, AppAuditor.Result apps) {
        List<Check> l = new ArrayList<>();

        // --- 資産 / 脆弱性 ---
        boolean lock = Collector.isDeviceSecure(c);
        l.add(new Check(C_ASSET, "画面ロック", lock, 20,
                lock ? "パスコード等が設定されています" : "端末が無施錠です",
                "PIN・パターン・生体認証のいずれかを必ず設定する"));

        long age = Collector.patchAgeDays();
        boolean patchOk = age >= 0 && age <= 90;
        l.add(new Check(C_ASSET, "セキュリティパッチ",
                patchOk, 15,
                age < 0 ? "パッチ日付を取得できません" : "適用日から " + age + " 日経過",
                "システム更新を確認し、90日以内のパッチを維持する"));

        boolean osOk = Build.VERSION.SDK_INT >= 30;
        l.add(new Check(C_ASSET, "OSバージョン", osOk, 10,
                "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
                "サポート対象のOSバージョンへ更新、または端末を更改する"));

        int timeout = Collector.screenTimeoutSec(c);
        boolean toOk = timeout > 0 && timeout <= 300;
        l.add(new Check(C_ASSET, "画面自動オフ", toOk, 5,
                timeout < 0 ? "取得不可" : timeout + " 秒",
                "5分以内に自動ロックされるよう設定する"));

        // --- エンドポイント ---
        boolean adb = Collector.global(c, android.provider.Settings.Global.ADB_ENABLED);
        l.add(new Check(C_ENDPOINT, "USBデバッグ", !adb, 10,
                adb ? "有効になっています" : "無効です",
                "業務端末ではUSBデバッグを無効にする"));

        boolean dev = Collector.global(c, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED);
        l.add(new Check(C_ENDPOINT, "開発者オプション", !dev, 5,
                dev ? "有効になっています" : "無効です",
                "不要であれば開発者オプションを無効にする"));

        boolean unknown = Collector.unknownSources(c);
        l.add(new Check(C_ENDPOINT, "提供元不明アプリ", !unknown, 10,
                unknown ? "インストール許可されたアプリがあります" : "許可なし",
                "野良APKの導入経路を塞ぐ（許可を取り消す）"));

        int ransom = files == null ? 0 : files.ransom.size();
        int danger = files == null ? 0 : files.danger.size();
        int dbl = files == null ? 0 : files.doubleExt.size();
        if (files != null && files.scanned) {
            l.add(new Check(C_ENDPOINT, "ランサムウェア痕跡", ransom == 0, 25,
                    ransom == 0 ? "暗号化拡張子は検出されませんでした" : ransom + " 件の暗号化拡張子を検出",
                    "直ちにネットワークから切り離し、バックアップから復旧する"));
            l.add(new Check(C_ENDPOINT, "危険拡張子", danger == 0, 10,
                    danger == 0 ? "実行形式ファイルなし" : danger + " 件の実行形式ファイル",
                    "不要な実行形式ファイルを削除し、出所を確認する"));
            l.add(new Check(C_ENDPOINT, "二重拡張子", dbl == 0, 15,
                    dbl == 0 ? "偽装ファイルなし" : dbl + " 件の二重拡張子を検出",
                    "文書を装った実行ファイルの可能性。開かずに削除する"));
        } else {
            l.add(new Check(C_ENDPOINT, "ダウンロード監視", false, 8,
                    "監視フォルダが未設定です",
                    "感染予防タブで監視フォルダを選択する"));
        }

        // --- 認証 ---
        int n = accounts == null ? 0 : accounts.length();
        if (n == 0) {
            l.add(new Check(C_AUTH, "アカウント棚卸し", false, 10,
                    "SaaSアカウントが未登録です",
                    "認証タブに業務利用中のSaaSを登録する"));
        } else {
            int noMfa = 0;
            int oldPw = 0;
            for (int i = 0; i < n; i++) {
                JSONObject o = accounts.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                if (!o.optBoolean("mfa", false)) {
                    noMfa++;
                }
                if (daysSince(o.optString("pw", "")) > 180) {
                    oldPw++;
                }
            }
            l.add(new Check(C_AUTH, "多要素認証", noMfa == 0, 20,
                    noMfa == 0 ? n + " 件すべてMFA有効" : n + " 件中 " + noMfa + " 件がMFA未設定",
                    "MFA未設定のSaaSから優先的に有効化する"));
            int badMail = 0;
            int uncheckedMail = 0;
            for (int i = 0; i < n; i++) {
                JSONObject o = accounts.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String mail = o.optString("mail", "");
                if (mail.isEmpty()) {
                    continue;
                }
                String verdict = o.optString("mail_verdict", "");
                if (verdict.isEmpty()) {
                    uncheckedMail++;
                } else if (!verdict.startsWith("問題は")) {
                    badMail++;
                }
            }
            if (badMail > 0 || uncheckedMail > 0) {
                l.add(new Check(C_AUTH, "メールアドレス検証", badMail == 0, 10,
                        badMail > 0
                                ? badMail + " 件に問題（使い捨て/失効ドメイン等）"
                                : uncheckedMail + " 件が未検証",
                        badMail > 0
                                ? "業務アカウントの連絡先を正規のドメインへ変更する"
                                : "認証タブでアドレスを検証する"));
            }
            l.add(new Check(C_AUTH, "パスワード鮮度", oldPw == 0, 10,
                    oldPw == 0 ? "180日以内に更新済み" : oldPw + " 件が180日以上未更新",
                    "パスワードマネージャで長い固有パスワードへ更新する"));
        }

        // --- 情報資産 (機密情報分類) ---
        if (docs != null && docs.error == null && docs.scanned > 0) {
            int sensitive = docs.sensitive();
            l.add(new Check(C_DATA, "機密文書の所在", sensitive == 0, 15,
                    sensitive == 0
                            ? docs.scanned + " 件を解析し、機密相当は検出されませんでした"
                            : docs.scanned + " 件中 " + sensitive + " 件が機密相当（極秘/社外秘）",
                    "端末内の機密文書を削除するか、暗号化された共有領域へ移す"));

            int unlabeled = 0;
            for (DocClassifier.Doc d : docs.docs) {
                boolean high = DocClassifier.L_TOP.equals(d.label)
                        || DocClassifier.L_CONF.equals(d.label);
                if (high && !d.name.startsWith("【")) {
                    unlabeled++;
                }
            }
            l.add(new Check(C_DATA, "ラベル付与", unlabeled == 0, 10,
                    unlabeled == 0 ? "機密文書はすべてラベル済み" : unlabeled + " 件が未ラベル",
                    "機密情報タブでラベルを付与し、取扱区分を明示する"));
        } else {
            l.add(new Check(C_DATA, "機密情報分類", false, 8,
                    "文書解析が未実行です",
                    "機密情報タブで解析を実行する"));
        }

        // --- アプリ ---
        if (apps != null && apps.total > 0) {
            l.add(new Check(C_APPS, "危険権限アプリ", apps.flagged == 0, 12,
                    apps.flagged == 0
                            ? "高リスク権限の組み合わせを持つアプリはありません"
                            : apps.flagged + " 件が高リスク権限（SMS/アクセシビリティ等）を保有",
                    "心当たりのないアプリはアンインストールし、権限を見直す"));
        }

        // --- ネットワーク ---
        String sec = Collector.wifiSecurity(c);
        if ("オープン(暗号化なし)".equals(sec) || "WEP".equals(sec)) {
            l.add(new Check(C_NETWORK, "Wi-Fi暗号化", false, 15,
                    "接続中のWi-Fiは " + sec + " です",
                    "WPA2/WPA3のアクセスポイントへ切り替える"));
        } else if (sec != null) {
            l.add(new Check(C_NETWORK, "Wi-Fi暗号化", true, 15,
                    sec, ""));
        }

        boolean wifi = Collector.isWifi(c);
        boolean vpn = Collector.vpnActive(c);
        l.add(new Check(C_NETWORK, "VPN", !wifi || vpn, 10,
                vpn ? "VPN接続中" : (wifi ? "Wi-Fi接続中でVPN未使用" : "モバイル回線"),
                "社外Wi-Fi利用時はVPNを経由する"));

        JSONObject ipi = NetProbe.saved(c);
        if (ipi != null) {
            String home = Store.prefs(c).getString("home_country", "JP");
            String cc = ipi.optString("国コード", "");
            boolean sameCountry = cc.isEmpty() || home.equalsIgnoreCase(cc);
            l.add(new Check(C_NETWORK, "接続元の国", sameCountry, 15,
                    sameCountry
                            ? "接続元: " + ipi.optString("国") + " / "
                                    + ipi.optString("回線事業者")
                            : "想定(" + home + ")と異なる国から接続: "
                                    + ipi.optString("国") + " (" + cc + ")",
                    "心当たりがなければ通信経路と認証情報の漏えいを確認する"));

            boolean hosting = ipi.optBoolean("ホスティング系", false);
            l.add(new Check(C_NETWORK, "接続経路", !hosting, 8,
                    hosting
                            ? "データセンター/VPN事業者の回線を経由: "
                                    + ipi.optString("回線事業者")
                            : "一般回線: " + ipi.optString("回線事業者"),
                    "業務で許可されたVPN以外の匿名化サービスを経由していないか確認する"));

            String at = ipi.optString("取得日時", "");
            boolean fresh = at.length() >= 10 && daysSince(at.substring(0, 10)) <= 7;
            if (!fresh) {
                l.add(new Check(C_NETWORK, "接続元の再確認", false, 5,
                        "IP照合が7日以上前です (" + at + ")",
                        "ネットワークタブで接続元を再照合する"));
            }
        } else {
            l.add(new Check(C_NETWORK, "接続元の照合", false, 8,
                    "外部IP照合が未実行です",
                    "ネットワークタブで接続元を照合する"));
        }

        boolean pdns = Collector.privateDnsActive(c);
        l.add(new Check(C_NETWORK, "プライベートDNS", pdns, 8,
                pdns ? "有効" : "無効",
                "DNS over TLS を設定し、名前解決の盗聴・改ざんを防ぐ"));

        // --- 物理 ---
        boolean home = Store.prefs(c).contains("home_lat");
        l.add(new Check(C_PHYSICAL, "拠点登録", home, 5,
                home ? "拠点が登録済み" : "拠点が未登録です",
                "物理・外出タブで拠点を登録し、外出判定を有効にする"));

        JSONObject insp = DeskInspector.latest(c);
        if (insp == null) {
            l.add(new Check(C_PHYSICAL, "書類点検", false, 8,
                    "未実施です",
                    "書類点検タブで机上を撮影し、放置書類を点検する"));
        } else {
            String at = insp.optString("t", "");
            long days = daysSince(at.length() >= 10 ? at.substring(0, 10) : "");
            String lab = insp.optString("label", DocClassifier.L_PUBLIC);
            boolean clean = !DocClassifier.L_TOP.equals(lab) && !DocClassifier.L_CONF.equals(lab);
            boolean fresh = days <= 7;
            l.add(new Check(C_PHYSICAL, "書類点検", clean && fresh, 12,
                    "最終点検 " + at + " / 判定 " + lab
                            + (fresh ? "" : "（7日以上前）"),
                    clean ? "7日以内に机上を再点検する"
                            : "机上の機密書類を施錠保管し、再点検する"));
        }

        return l;
    }

    public static long daysSince(String yyyymmdd) {
        if (yyyymmdd == null || yyyymmdd.length() < 8) {
            return 99999;
        }
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(yyyymmdd);
            if (d == null) {
                return 99999;
            }
            return (System.currentTimeMillis() - d.getTime()) / 86400000L;
        } catch (Exception e) {
            return 99999;
        }
    }

    public static int score(List<Check> checks) {
        int lost = 0;
        for (Check c : checks) {
            if (!c.ok) {
                lost += c.weight;
            }
        }
        int s = 100 - lost;
        return Math.max(0, Math.min(100, s));
    }

    public static LinkedHashMap<String, Integer> byCategory(List<Check> checks) {
        LinkedHashMap<String, Integer> total = new LinkedHashMap<>();
        LinkedHashMap<String, Integer> lost = new LinkedHashMap<>();
        for (Check c : checks) {
            total.put(c.category, get(total, c.category) + c.weight);
            if (!c.ok) {
                lost.put(c.category, get(lost, c.category) + c.weight);
            }
        }
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        for (String k : total.keySet()) {
            int t = get(total, k);
            int v = t == 0 ? 100 : (int) Math.round(100.0 * (t - get(lost, k)) / t);
            out.put(k, v);
        }
        return out;
    }

    private static int get(LinkedHashMap<String, Integer> m, String k) {
        Integer v = m.get(k);
        return v == null ? 0 : v;
    }

    public static String rank(int score) {
        if (score >= 90) {
            return "A / 良好";
        }
        if (score >= 75) {
            return "B / 概ね良好";
        }
        if (score >= 60) {
            return "C / 要改善";
        }
        if (score >= 40) {
            return "D / 危険";
        }
        return "E / 重大リスク";
    }

    public static List<Check> failures(List<Check> checks) {
        List<Check> l = new ArrayList<>();
        for (Check c : checks) {
            if (!c.ok) {
                l.add(c);
            }
        }
        java.util.Collections.sort(l, new java.util.Comparator<Check>() {
            @Override
            public int compare(Check a, Check b) {
                return b.weight - a.weight;
            }
        });
        return l;
    }
}
