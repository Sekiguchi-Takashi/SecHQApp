package jp.appathy.sechq;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.List;
import java.util.Map;

public class Exporter {

    public static JSONObject buildJson(Context c, List<RiskEngine.Check> checks, int score,
                                       DocClassifier.Result docs) {
        JSONObject o = new JSONObject();
        try {
            o.put("生成日時", Collector.now());
            o.put("総合スコア", score);
            o.put("評価", RiskEngine.rank(score));
            o.put("資産", Collector.toJson(Collector.asset(c)));
            o.put("ネットワーク", Collector.toJson(Collector.network(c)));
            o.put("アカウント", Store.loadArray(c, Store.F_ACCOUNTS));
            JSONObject insp = DeskInspector.latest(c);
            if (insp != null) {
                o.put("直近の書類点検", insp);
            }
            JSONArray ca = new JSONArray();
            for (RiskEngine.Check ch : checks) {
                JSONObject x = new JSONObject();
                x.put("分類", ch.category);
                x.put("項目", ch.title);
                x.put("判定", ch.ok ? "OK" : "NG");
                x.put("重み", ch.weight);
                x.put("詳細", ch.detail);
                if (!ch.ok) {
                    x.put("提案", ch.advice);
                }
                ca.put(x);
            }
            o.put("判定結果", ca);
            JSONObject cat = new JSONObject();
            for (Map.Entry<String, Integer> e : RiskEngine.byCategory(checks).entrySet()) {
                cat.put(e.getKey(), e.getValue());
            }
            o.put("カテゴリ別スコア", cat);
            if (docs != null && docs.error == null && docs.scanned > 0) {
                JSONArray da = new JSONArray();
                for (DocClassifier.Doc d : docs.docs) {
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
                doc.put("解析件数", docs.scanned);
                doc.put("機密相当", docs.sensitive());
                doc.put("一覧", da);
                o.put("機密情報分類", doc);
            }
            o.put("スコア履歴", Store.loadArray(c, DailyWorker.F_HISTORY));
        } catch (Exception ignored) {
        }
        return o;
    }

    /** export_tree に filename で書き込む。既存の同名ファイルは上書き。 */
    public static boolean writeToExportTree(Context c, String filename, String body) {
        String tree = Store.prefs(c).getString("export_tree", "");
        if (tree.isEmpty()) {
            return false;
        }
        try {
            DocumentFile root = DocumentFile.fromTreeUri(c, Uri.parse(tree));
            if (root == null || !root.canWrite()) {
                return false;
            }
            DocumentFile f = root.findFile(filename);
            if (f == null) {
                f = root.createFile("application/json", filename);
            }
            if (f == null) {
                return false;
            }
            OutputStream os = c.getContentResolver().openOutputStream(f.getUri(), "wt");
            if (os == null) {
                return false;
            }
            os.write(body.getBytes("UTF-8"));
            os.close();
            Store.prefs(c).edit().putString("export_last", Collector.now()).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static String deviceId() {
        String m = android.os.Build.MODEL == null ? "device" : android.os.Build.MODEL;
        return m.replaceAll("[^0-9A-Za-z._-]", "_");
    }

    public static String exportName() {
        return "sechq_" + deviceId() + "_" + Collector.today() + ".json";
    }

    public static String pretty(JSONObject o) {
        try {
            return o.toString(2);
        } catch (Exception e) {
            return o.toString();
        }
    }
}
