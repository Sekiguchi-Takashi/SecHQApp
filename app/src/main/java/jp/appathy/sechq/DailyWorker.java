package jp.appathy.sechq;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public class DailyWorker extends Worker {

    public static final String CHANNEL = "sechq_daily";
    public static final String F_HISTORY = "history.json";

    public DailyWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        try {
            String tree = Store.prefs(c).getString("tree", "");
            FileScanner.Result files = tree.isEmpty() ? null : FileScanner.scan(c, tree);
            JSONArray accounts = Store.loadArray(c, Store.F_ACCOUNTS);
            List<RiskEngine.Check> checks = RiskEngine.run(c, accounts, files, null);
            int score = RiskEngine.score(checks);

            appendHistory(c, score);

            int last = Store.prefs(c).getInt("last_score", -1);
            Store.prefs(c).edit().putInt("last_score", score).apply();

            boolean ransom = files != null && files.scanned && !files.ransom.isEmpty();
            if (ransom) {
                notify(c, "緊急: 暗号化拡張子を検出",
                        files.ransom.size() + " 件。直ちにネットワークから切り離してください");
            } else if (score < 60) {
                notify(c, "リスクスコア " + score + " (" + RiskEngine.rank(score) + ")",
                        "要改善項目があります。アプリで確認してください");
            } else if (last >= 0 && last - score >= 10) {
                notify(c, "リスクスコアが低下: " + last + " → " + score,
                        "設定変更や新しいファイルがないか確認してください");
            }
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    public static void appendHistory(Context c, int score) {
        JSONArray h = Store.loadArray(c, F_HISTORY);
        String today = Collector.today();
        try {
            JSONObject lastObj = h.length() == 0 ? null : h.optJSONObject(h.length() - 1);
            if (lastObj != null && today.equals(lastObj.optString("d"))) {
                lastObj.put("s", score);
            } else {
                JSONObject o = new JSONObject();
                o.put("d", today);
                o.put("s", score);
                h.put(o);
            }
        } catch (Exception ignored) {
        }
        while (h.length() > 90) {
            h.remove(0);
        }
        Store.saveArray(c, F_HISTORY, h);
    }

    private static void notify(Context c, String title, String body) {
        try {
            NotificationManager nm =
                    (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(
                        CHANNEL, "定期チェック", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent i = new Intent(c, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(c, 0, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(1001, b.build());
        } catch (Exception ignored) {
        }
    }
}
