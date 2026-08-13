package jp.appathy.sechq;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

public class ClientService extends Service {

    public static final String CHANNEL = "sechq_client";
    public static final int NOTIF_ID = 2001;
    public static final long POLL_MS = 5 * 60 * 1000L;

    private Handler handler;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    poll();
                }
            }).start();
            if (running) {
                handler.postDelayed(this, POLL_MS);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotif("出社中", "管理者からの指示を待機しています"));
        if (!running) {
            running = true;
            handler.post(tick);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) {
            handler.removeCallbacks(tick);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------- polling ----------------

    private void poll() {
        Context c = getApplicationContext();
        try {
            writeStatus(c, "出社");
            checkWifiPolicy(c);
            JSONObject cmd = readCmd(c);
            if (cmd == null) {
                return;
            }
            long ts = cmd.optLong("ts", 0);
            long done = Store.prefs(c).getLong("cmd_done_ts", 0);
            if (ts <= done) {
                return;
            }
            Store.prefs(c).edit().putLong("cmd_done_ts", ts).apply();

            JSONArray actions = cmd.optJSONArray("actions");
            if (actions == null) {
                return;
            }
            for (int i = 0; i < actions.length(); i++) {
                JSONObject a = actions.optJSONObject(i);
                if (a == null) {
                    continue;
                }
                String type = a.optString("type");
                if ("photo".equals(type)) {
                    String msg = a.optString("msg", "机上の書類を点検してください");
                    Store.prefs(c).edit().putString("pending_photo", msg).apply();
                    notifyUser(c, "管理者からのメッセージ", msg + "（タップして撮影）", "photo");
                } else if ("location".equals(type)) {
                    sendLocation(c);
                } else if ("wifi_policy".equals(type)) {
                    JSONArray allowed = a.optJSONArray("allowed");
                    Store.prefs(c).edit().putString("wifi_allowed",
                            allowed == null ? "[]" : allowed.toString()).apply();
                    checkWifiPolicy(c);
                } else if ("message".equals(type)) {
                    notifyUser(c, "管理者からのメッセージ", a.optString("msg", ""), "open");
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static void writeStatus(Context c, String state) {
        try {
            JSONObject o = new JSONObject();
            o.put("端末", Exporter.deviceId());
            o.put("状態", state);
            o.put("日時", Collector.now());
            o.put("接続", Collector.transport(c));
            o.put("SSID", Collector.ssid(c));
            o.put("VPN", Collector.vpnActive(c) ? "接続中" : "未使用");
            Exporter.writeToExportTree(c,
                    "status_" + Exporter.deviceId() + ".json", Exporter.pretty(o));
        } catch (Exception ignored) {
        }
    }

    private void checkWifiPolicy(Context c) {
        try {
            String raw = Store.prefs(c).getString("wifi_allowed", "");
            if (raw.isEmpty() || "[]".equals(raw)) {
                return;
            }
            if (!Collector.isWifi(c)) {
                return;
            }
            String ssid = Collector.ssid(c);
            if (ssid.startsWith("不明") || "-".equals(ssid)) {
                return;
            }
            JSONArray allowed = new JSONArray(raw);
            for (int i = 0; i < allowed.length(); i++) {
                if (ssid.equals(allowed.optString(i))) {
                    return;
                }
            }
            JSONObject o = new JSONObject();
            o.put("端末", Exporter.deviceId());
            o.put("種別", "許可外Wi-Fi接続");
            o.put("SSID", ssid);
            o.put("日時", Collector.now());
            Exporter.writeToExportTree(c,
                    "alert_" + Exporter.deviceId() + "_" + System.currentTimeMillis() + ".json",
                    Exporter.pretty(o));
            notifyUser(c, "許可外のWi-Fiに接続中",
                    "「" + ssid + "」は許可されていません。タップしてWi-Fiを切り替えてください", "wifi");
        } catch (Exception ignored) {
        }
    }

    private void sendLocation(Context c) {
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return;
            }
            Location best = null;
            List<String> providers = lm.getProviders(true);
            for (String p : providers) {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            }
            JSONObject o = new JSONObject();
            o.put("端末", Exporter.deviceId());
            o.put("日時", Collector.now());
            if (best == null) {
                o.put("結果", "測位不可");
            } else {
                o.put("緯度", best.getLatitude());
                o.put("経度", best.getLongitude());
                o.put("測位時刻からの経過秒",
                        (System.currentTimeMillis() - best.getTime()) / 1000);
            }
            Exporter.writeToExportTree(c,
                    "loc_" + Exporter.deviceId() + "_" + System.currentTimeMillis() + ".json",
                    Exporter.pretty(o));
        } catch (SecurityException e) {
            // 権限なし
        } catch (Exception ignored) {
        }
    }

    private JSONObject readCmd(Context c) {
        try {
            String tree = Store.prefs(c).getString("export_tree", "");
            if (tree.isEmpty()) {
                return null;
            }
            DocumentFile root = DocumentFile.fromTreeUri(c, Uri.parse(tree));
            if (root == null) {
                return null;
            }
            DocumentFile f = root.findFile("cmd_" + Exporter.deviceId() + ".json");
            if (f == null) {
                f = root.findFile("cmd_all.json");
            }
            if (f == null) {
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

    // ---------------- notifications ----------------

    private Notification buildNotif(String title, String body) {
        ensureChannel(this);
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private static void notifyUser(Context c, String title, String body, String action) {
        try {
            ensureChannel(c);
            Intent i = new Intent(c, MainActivity.class);
            i.putExtra("action", action);
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, action.hashCode(), i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            nm.notify((int) (System.currentTimeMillis() % 100000),
                    new NotificationCompat.Builder(c, CHANNEL)
                            .setSmallIcon(android.R.drawable.stat_notify_chat)
                            .setContentTitle(title)
                            .setContentText(body)
                            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                            .setContentIntent(pi)
                            .setAutoCancel(true)
                            .build());
        } catch (Exception ignored) {
        }
    }

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "出社モード", NotificationManager.IMPORTANCE_HIGH));
            }
        }
    }
}
