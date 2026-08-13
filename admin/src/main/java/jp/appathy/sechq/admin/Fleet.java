package jp.appathy.sechq.admin;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Fleet {

    public static class Device {
        public String id;
        public String model;
        public String date;
        public int score = -1;
        public String rank = "";
        public JSONObject latest;
        public List<String> ngTitles = new ArrayList<>();
        public int ngWeight;
        public int ransom;
        public int sensitiveDocs;
        public JSONArray history;
        public int reports;
    }

    public static class Result {
        public String error;
        public int files;
        public List<Device> devices = new ArrayList<>();

        public int avgScore() {
            int n = 0;
            int sum = 0;
            for (Device d : devices) {
                if (d.score >= 0) {
                    sum += d.score;
                    n++;
                }
            }
            return n == 0 ? -1 : sum / n;
        }
    }

    public static Result load(Context c, String treeUri) {
        Result r = new Result();
        if (treeUri == null || treeUri.isEmpty()) {
            r.error = "収集フォルダが未選択です";
            return r;
        }
        DocumentFile root = DocumentFile.fromTreeUri(c, Uri.parse(treeUri));
        if (root == null || !root.canRead()) {
            r.error = "フォルダを読み取れません。再選択してください";
            return r;
        }
        Map<String, Device> byId = new HashMap<>();
        walk(c, root, r, byId, 0);
        r.devices.addAll(byId.values());
        Collections.sort(r.devices, new Comparator<Device>() {
            @Override
            public int compare(Device a, Device b) {
                return a.score - b.score;
            }
        });
        if (r.files == 0 && r.error == null) {
            r.error = "sechq_*.json が見つかりません";
        }
        return r;
    }

    private static void walk(Context c, DocumentFile dir, Result r,
                             Map<String, Device> byId, int depth) {
        if (depth > 3) {
            return;
        }
        DocumentFile[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (DocumentFile f : list) {
            if (f.isDirectory()) {
                walk(c, f, r, byId, depth + 1);
                continue;
            }
            String name = f.getName();
            if (name == null || !name.startsWith("sechq_") || !name.endsWith(".json")) {
                continue;
            }
            if (f.length() > 3 * 1024 * 1024) {
                continue;
            }
            JSONObject o = read(c, f);
            if (o == null) {
                continue;
            }
            r.files++;
            String id = idOf(name, o);
            String date = dateOf(name, o);
            Device d = byId.get(id);
            if (d == null) {
                d = new Device();
                d.id = id;
                byId.put(id, d);
            }
            d.reports++;
            if (d.date == null || date.compareTo(d.date) > 0) {
                apply(d, o, date);
            }
        }
    }

    private static void apply(Device d, JSONObject o, String date) {
        d.date = date;
        d.latest = o;
        d.score = o.optInt("総合スコア", -1);
        d.rank = o.optString("評価", "");
        JSONObject asset = o.optJSONObject("資産");
        d.model = asset == null ? d.id : asset.optString("モデル", d.id);
        d.ngTitles.clear();
        d.ngWeight = 0;
        d.ransom = 0;
        JSONArray checks = o.optJSONArray("判定結果");
        if (checks != null) {
            for (int i = 0; i < checks.length(); i++) {
                JSONObject x = checks.optJSONObject(i);
                if (x == null) {
                    continue;
                }
                if ("NG".equals(x.optString("判定"))) {
                    d.ngTitles.add(x.optString("項目"));
                    d.ngWeight += x.optInt("重み");
                    if ("ランサムウェア痕跡".equals(x.optString("項目"))) {
                        d.ransom = 1;
                    }
                }
            }
        }
        JSONObject docs = o.optJSONObject("機密情報分類");
        d.sensitiveDocs = docs == null ? 0 : docs.optInt("機密相当", 0);
        d.history = o.optJSONArray("スコア履歴");
    }

    private static String idOf(String name, JSONObject o) {
        // sechq_<id>_YYYY-MM-DD.json → id / 旧形式 sechq_YYYY-MM-DD.json → モデル名
        String core = name.substring(6, name.length() - 5);
        if (core.length() > 11 && core.charAt(core.length() - 11) == '_') {
            return core.substring(0, core.length() - 11);
        }
        JSONObject asset = o.optJSONObject("資産");
        if (asset != null) {
            String m = asset.optString("モデル", "");
            if (!m.isEmpty()) {
                return m;
            }
        }
        return "unknown";
    }

    private static String dateOf(String name, JSONObject o) {
        String core = name.substring(6, name.length() - 5);
        if (core.length() >= 10) {
            String tail = core.substring(core.length() - 10);
            if (tail.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return tail;
            }
        }
        String at = o.optString("生成日時", "");
        return at.length() >= 10 ? at.substring(0, 10) : "0000-00-00";
    }

    private static JSONObject read(Context c, DocumentFile f) {
        try {
            InputStream in = c.getContentResolver().openInputStream(f.getUri());
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            in.close();
            return new JSONObject(bo.toString("UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }
}
