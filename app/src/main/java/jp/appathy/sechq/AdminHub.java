package jp.appathy.sechq;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AdminHub {

    public static final String LOCK_FILE = "admin_lock.json";
    public static final long LOCK_TTL_MS = 10 * 60 * 1000L;

    // ---------------- inbox ----------------

    public static class Event {
        public String kind;      // 状態 / レポート / 点検 / 位置 / 警告
        public String device;
        public String at;
        public String summary;
        public JSONObject raw;
        public String fileName;
    }

    public static class Device {
        public String id;
        public String state = "不明";
        public String lastAt = "";
        public String ssid = "";
        public int score = -1;
        public boolean reported;
        public List<Event> events = new ArrayList<>();
    }

    public static class Inbox {
        public String error;
        public int files;
        public List<Event> events = new ArrayList<>();
        public List<Device> devices = new ArrayList<>();
    }

    public static Inbox load(Context c) {
        Inbox in = new Inbox();
        String tree = Store.prefs(c).getString("export_tree", "");
        if (tree.isEmpty()) {
            in.error = "共有フォルダが未設定です";
            return in;
        }
        DocumentFile root = DocumentFile.fromTreeUri(c, Uri.parse(tree));
        if (root == null || !root.canRead()) {
            in.error = "共有フォルダを読み取れません";
            return in;
        }
        DocumentFile[] list = root.listFiles();
        if (list == null) {
            return in;
        }
        Map<String, Device> byId = new HashMap<>();
        for (DocumentFile f : list) {
            String name = f.getName();
            if (name == null || !name.endsWith(".json") || f.isDirectory()) {
                continue;
            }
            if (name.startsWith("cmd_") || name.equals(LOCK_FILE)
                    || name.startsWith("summary_")) {
                continue;
            }
            JSONObject o = read(c, f);
            if (o == null) {
                continue;
            }
            in.files++;
            Event e = toEvent(name, o);
            if (e == null) {
                continue;
            }
            e.fileName = name;
            in.events.add(e);

            Device d = byId.get(e.device);
            if (d == null) {
                d = new Device();
                d.id = e.device;
                byId.put(e.device, d);
            }
            d.events.add(e);
            if (e.at.compareTo(d.lastAt) > 0) {
                d.lastAt = e.at;
            }
            if ("状態".equals(e.kind)) {
                d.state = o.optString("状態", d.state);
                d.ssid = o.optString("SSID", d.ssid);
            } else if ("レポート".equals(e.kind)) {
                d.reported = true;
                d.score = o.optInt("総合スコア", d.score);
            }
        }
        Collections.sort(in.events, new Comparator<Event>() {
            @Override
            public int compare(Event a, Event b) {
                return b.at.compareTo(a.at);
            }
        });
        in.devices.addAll(byId.values());
        Collections.sort(in.devices, new Comparator<Device>() {
            @Override
            public int compare(Device a, Device b) {
                return b.lastAt.compareTo(a.lastAt);
            }
        });
        return in;
    }

    private static Event toEvent(String name, JSONObject o) {
        Event e = new Event();
        e.raw = o;
        e.device = o.optString("端末", "");
        e.at = o.optString("日時", o.optString("生成日時", ""));
        if (name.startsWith("status_")) {
            e.kind = "状態";
            e.summary = o.optString("状態") + " / " + o.optString("接続")
                    + " / SSID " + o.optString("SSID") + " / VPN " + o.optString("VPN");
        } else if (name.startsWith("sechq_")) {
            e.kind = "レポート";
            if (e.device.isEmpty()) {
                JSONObject a = o.optJSONObject("資産");
                e.device = a == null ? "unknown" : a.optString("モデル", "unknown");
            }
            e.summary = "スコア " + o.optInt("総合スコア", -1) + " " + o.optString("評価");
        } else if (name.startsWith("desk_")) {
            e.kind = "点検";
            e.summary = o.has("結果") ? o.optString("結果")
                    : (o.optString("判定") + " / スコア " + o.optInt("スコア"));
        } else if (name.startsWith("loc_")) {
            e.kind = "位置";
            e.summary = o.has("結果") ? o.optString("結果")
                    : (o.optDouble("緯度", 0) + ", " + o.optDouble("経度", 0));
        } else if (name.startsWith("alert_")) {
            e.kind = "警告";
            e.summary = o.optString("種別") + " / " + o.optString("SSID");
        } else {
            return null;
        }
        if (e.device.isEmpty()) {
            e.device = "unknown";
        }
        return e;
    }

    // ---------------- commands ----------------

    public static boolean sendCommand(Context c, String deviceId, JSONObject action) {
        try {
            JSONArray actions = new JSONArray();
            actions.put(action);
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("発行", Collector.now());
            o.put("actions", actions);
            String name = (deviceId == null || deviceId.isEmpty())
                    ? "cmd_all.json" : "cmd_" + deviceId + ".json";
            return Exporter.writeToExportTree(c, name, Exporter.pretty(o));
        } catch (Exception e) {
            return false;
        }
    }

    public static JSONObject messageAction(String msg) {
        try {
            JSONObject a = new JSONObject();
            a.put("type", "message");
            a.put("msg", msg);
            return a;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static JSONObject simpleAction(String type) {
        try {
            JSONObject a = new JSONObject();
            a.put("type", type);
            return a;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static JSONObject photoAction(String msg) {
        try {
            JSONObject a = new JSONObject();
            a.put("type", "photo");
            a.put("msg", msg);
            return a;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static JSONObject wifiPolicyAction(List<String> ssids) {
        try {
            JSONObject a = new JSONObject();
            a.put("type", "wifi_policy");
            a.put("allowed", new JSONArray(ssids));
            return a;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // ---------------- aggregation ----------------

    public static class Summary {
        public String at;
        public int devices;
        public int reported;
        public int atWork;
        public int alerts;
        public int avgScore = -1;
        public boolean complete;
    }

    public static Summary aggregate(Context c, Inbox in) {
        Summary s = new Summary();
        s.at = Collector.now();
        s.devices = in.devices.size();
        int sum = 0;
        int n = 0;
        for (Device d : in.devices) {
            if (d.reported) {
                s.reported++;
            }
            if ("出社".equals(d.state)) {
                s.atWork++;
            }
            if (d.score >= 0) {
                sum += d.score;
                n++;
            }
            for (Event e : d.events) {
                if ("警告".equals(e.kind)) {
                    s.alerts++;
                }
            }
        }
        s.avgScore = n == 0 ? -1 : sum / n;
        int expected = Store.prefs(c).getInt("expected_devices", 0);
        s.complete = expected > 0 && s.reported >= expected;
        return s;
    }

    public static void saveSummary(Context c, Summary s, String trigger) {
        try {
            JSONObject o = new JSONObject();
            o.put("集計日時", s.at);
            o.put("契機", trigger);
            o.put("端末数", s.devices);
            o.put("報告済み", s.reported);
            o.put("出社中", s.atWork);
            o.put("警告", s.alerts);
            o.put("平均スコア", s.avgScore);
            Exporter.writeToExportTree(c,
                    "summary_" + Collector.today() + ".json", Exporter.pretty(o));
            Store.prefs(c).edit()
                    .putString("last_summary_at", s.at)
                    .putString("last_summary_trigger", trigger)
                    .putString("last_summary_date", Collector.today())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    // ---------------- lock ----------------

    public static class Lock {
        public boolean mine;
        public String owner;
        public String at;
        public boolean conflict;
    }

    public static String selfId(Context c) {
        String id = Store.prefs(c).getString("admin_id", "");
        if (id.isEmpty()) {
            id = Exporter.deviceId() + "-"
                    + Integer.toHexString((int) (System.currentTimeMillis() & 0xFFFFF));
            Store.prefs(c).edit().putString("admin_id", id).apply();
        }
        return id;
    }

    /** ロック取得を試み、他端末が保持中なら conflict=true を返す。 */
    public static Lock acquire(Context c) {
        Lock l = new Lock();
        String me = selfId(c);
        try {
            JSONObject cur = readLock(c);
            if (cur != null) {
                String owner = cur.optString("owner", "");
                long ts = cur.optLong("ts", 0);
                boolean fresh = System.currentTimeMillis() - ts < LOCK_TTL_MS;
                if (fresh && !me.equals(owner)) {
                    l.conflict = true;
                    l.owner = owner;
                    l.at = cur.optString("at", "");
                    return l;
                }
            }
            JSONObject o = new JSONObject();
            o.put("owner", me);
            o.put("ts", System.currentTimeMillis());
            o.put("at", Collector.now());
            writeLock(c, o);
            l.mine = true;
            l.owner = me;
            l.at = Collector.now();
        } catch (Exception e) {
            l.mine = true;
        }
        return l;
    }

    public static void release(Context c) {
        try {
            JSONObject cur = readLock(c);
            if (cur != null && !selfId(c).equals(cur.optString("owner", ""))) {
                return;
            }
            JSONObject o = new JSONObject();
            o.put("owner", "");
            o.put("ts", 0);
            o.put("at", Collector.now());
            writeLock(c, o);
        } catch (Exception ignored) {
        }
    }

    private static JSONObject readLock(Context c) {
        try {
            String tree = Store.prefs(c).getString("export_tree", "");
            if (tree.isEmpty()) {
                return null;
            }
            DocumentFile root = DocumentFile.fromTreeUri(c, Uri.parse(tree));
            if (root == null) {
                return null;
            }
            DocumentFile f = root.findFile(LOCK_FILE);
            if (f == null) {
                return null;
            }
            return read(c, f);
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeLock(Context c, JSONObject o) {
        try {
            String tree = Store.prefs(c).getString("export_tree", "");
            DocumentFile root = DocumentFile.fromTreeUri(c, Uri.parse(tree));
            if (root == null) {
                return;
            }
            DocumentFile f = root.findFile(LOCK_FILE);
            if (f == null) {
                f = root.createFile("application/json", LOCK_FILE);
            }
            if (f == null) {
                return;
            }
            OutputStream os = c.getContentResolver().openOutputStream(f.getUri(), "wt");
            if (os == null) {
                return;
            }
            os.write(Exporter.pretty(o).getBytes("UTF-8"));
            os.close();
        } catch (Exception ignored) {
        }
    }

    private static JSONObject read(Context c, DocumentFile f) {
        try {
            if (f.length() > 3 * 1024 * 1024) {
                return null;
            }
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
