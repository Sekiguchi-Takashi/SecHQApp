package jp.appathy.sechq;

import java.util.ArrayList;
import java.util.List;

public class AttackCatalog {

    public static final int SAFE = 0;     // 🔐
    public static final int CONFIRM = 1;  // ✅️
    public static final int WARN = 2;     // ⚠️
    public static final int DANGER = 3;   // 💣️

    private static final String[] PENDING_TITLES = {
            "ダウンロード監視", "機密情報分類", "書類点検", "アカウント棚卸し", "拠点登録"
    };

    public static class Attack {
        public String name;
        public String emoji;
        public String outline;
        public String[] related;

        Attack(String name, String emoji, String outline, String... related) {
            this.name = name;
            this.emoji = emoji;
            this.outline = outline;
            this.related = related;
        }
    }

    public static class Status {
        public Attack attack;
        public int level;
        public List<RiskEngine.Check> found = new ArrayList<>();
        public List<String> missing = new ArrayList<>();
    }

    public static final Attack[] ATTACKS = {
            new Attack("ランサムウェア", "🧬",
                    "ファイルを暗号化し身代金を要求する攻撃。メール添付や脆弱性経由で侵入し、端末内のファイルを一斉に暗号化します。",
                    "ランサムウェア痕跡", "危険拡張子", "セキュリティパッチ", "ダウンロード監視"),
            new Attack("フィッシング詐欺", "🎣",
                    "本物そっくりの偽サイトやメールでID・パスワードを盗む攻撃。盗んだ認証情報でSaaSへ不正ログインします。",
                    "多要素認証", "パスワード鮮度", "アカウント棚卸し", "二重拡張子"),
            new Attack("マルウェア感染", "🦠",
                    "不正なアプリやファイルを実行させて端末を乗っ取る攻撃。野良APKや偽装ファイルが主な侵入口です。",
                    "提供元不明アプリ", "危険拡張子", "危険権限アプリ", "USBデバッグ"),
            new Attack("アカウント乗っ取り", "🔓",
                    "漏えいしたパスワードの使い回しや総当たりでログインを突破する攻撃。MFAがない口座から狙われます。",
                    "多要素認証", "パスワード鮮度", "画面ロック"),
            new Attack("通信の盗聴 (中間者攻撃)", "📡",
                    "偽Wi-Fiや暗号化なしのアクセスポイントで通信内容を盗み見る攻撃。カフェや駅の無料Wi-Fiが典型です。",
                    "Wi-Fi暗号化", "VPN", "プライベートDNS"),
            new Attack("端末の盗難・紛失", "📱",
                    "置き忘れや盗難により端末ごと情報が流出するリスク。無施錠の端末は中身が丸見えになります。",
                    "画面ロック", "画面自動オフ", "拠点登録"),
            new Attack("情報持ち出し・内部不正", "🗂️",
                    "端末内に放置された機密文書が、共有や紛失を通じて外部へ渡るリスク。所在の把握とラベル付けが第一歩です。",
                    "機密文書の所在", "ラベル付与", "機密情報分類"),
            new Attack("のぞき見・書類放置", "👀",
                    "机上の書類や画面を第三者に見られる物理的なリスク。外出先や在宅勤務の机が狙われます。",
                    "書類点検", "画面自動オフ"),
            new Attack("脆弱性の悪用", "🕳️",
                    "OSやアプリの未修正の欠陥を突いて侵入する攻撃。古いOS・パッチ未適用・開発者設定の放置が入口になります。",
                    "OSバージョン", "セキュリティパッチ", "開発者オプション", "USBデバッグ"),
    };

    public static List<Status> evaluate(List<RiskEngine.Check> checks) {
        List<Status> out = new ArrayList<>();
        for (Attack a : ATTACKS) {
            Status s = new Status();
            s.attack = a;
            boolean danger = false;
            boolean warn = false;
            boolean confirm = false;
            for (String title : a.related) {
                RiskEngine.Check c = find(checks, title);
                if (c == null) {
                    s.missing.add(title);
                    confirm = true;
                    continue;
                }
                s.found.add(c);
                if (!c.ok) {
                    if (isPending(c.title)) {
                        confirm = true;
                    } else if (c.weight >= 15) {
                        danger = true;
                    } else {
                        warn = true;
                    }
                }
            }
            if (danger) {
                s.level = DANGER;
            } else if (warn) {
                s.level = WARN;
            } else if (confirm) {
                s.level = CONFIRM;
            } else {
                s.level = SAFE;
            }
            out.add(s);
        }
        return out;
    }

    private static RiskEngine.Check find(List<RiskEngine.Check> checks, String title) {
        for (RiskEngine.Check c : checks) {
            if (c.title.equals(title)) {
                return c;
            }
        }
        return null;
    }

    private static boolean isPending(String title) {
        for (String p : PENDING_TITLES) {
            if (p.equals(title)) {
                return true;
            }
        }
        return false;
    }

    public static String levelEmoji(int level) {
        switch (level) {
            case DANGER:
                return "💣️";
            case WARN:
                return "⚠️";
            case CONFIRM:
                return "✅️";
            default:
                return "🔐";
        }
    }

    public static String levelText(int level) {
        switch (level) {
            case DANGER:
                return "危険";
            case WARN:
                return "注意";
            case CONFIRM:
                return "要確認";
            default:
                return "安全";
        }
    }
}
