package jp.appathy.sechq;

import android.content.Context;

import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

public class NightlyWorker extends Worker {

    public NightlyWorker(Context context, WorkerParameters params) {
        super(context, params);
    }

    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        try {
            if (!"admin".equals(Store.prefs(c).getString("mode", ""))) {
                return Result.success();
            }
            String today = Collector.today();
            if (today.equals(Store.prefs(c).getString("last_summary_date", ""))) {
                return Result.success();
            }
            AdminHub.Inbox in = AdminHub.load(c);
            if (in.error != null || in.devices.isEmpty()) {
                return Result.success();
            }
            AdminHub.Summary s = AdminHub.aggregate(c, in);
            AdminHub.saveSummary(c, s, "23時自動");
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        } finally {
            schedule(c);
        }
    }

    /** 次の23:00に単発でスケジュールする。 */
    public static void schedule(Context c) {
        try {
            Calendar now = Calendar.getInstance();
            Calendar target = Calendar.getInstance();
            target.set(Calendar.HOUR_OF_DAY, 23);
            target.set(Calendar.MINUTE, 0);
            target.set(Calendar.SECOND, 0);
            if (!target.after(now)) {
                target.add(Calendar.DAY_OF_YEAR, 1);
            }
            long delay = target.getTimeInMillis() - now.getTimeInMillis();
            androidx.work.OneTimeWorkRequest req =
                    new androidx.work.OneTimeWorkRequest.Builder(NightlyWorker.class)
                            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                            .build();
            androidx.work.WorkManager.getInstance(c)
                    .enqueueUniqueWork("sechq_nightly",
                            androidx.work.ExistingWorkPolicy.REPLACE, req);
        } catch (Exception ignored) {
        }
    }
}
