package jp.appathy.sechq;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class AppAuditor {

    // permission, weight, label
    private static final String[][] DANGEROUS = {
            {"android.permission.READ_SMS", "4", "SMS読取"},
            {"android.permission.RECEIVE_SMS", "3", "SMS受信"},
            {"android.permission.SEND_SMS", "3", "SMS送信"},
            {"android.permission.BIND_ACCESSIBILITY_SERVICE", "5", "アクセシビリティ"},
            {"android.permission.SYSTEM_ALERT_WINDOW", "3", "重ね描き"},
            {"android.permission.READ_CALL_LOG", "3", "通話履歴"},
            {"android.permission.RECORD_AUDIO", "2", "録音"},
            {"android.permission.CAMERA", "1", "カメラ"},
            {"android.permission.READ_CONTACTS", "2", "連絡先"},
            {"android.permission.ACCESS_FINE_LOCATION", "1", "位置情報"},
            {"android.permission.REQUEST_INSTALL_PACKAGES", "4", "アプリインストール"},
            {"android.permission.BIND_DEVICE_ADMIN", "4", "端末管理者"},
    };

    public static class AppRisk {
        public String label;
        public String pkg;
        public boolean system;
        public int score;
        public List<String> perms = new ArrayList<>();
    }

    public static class Result {
        public int total;
        public int flagged;
        public List<AppRisk> apps = new ArrayList<>();
    }

    public static Result audit(Context c) {
        Result r = new Result();
        PackageManager pm = c.getPackageManager();
        List<PackageInfo> list;
        try {
            list = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS);
        } catch (Exception e) {
            return r;
        }
        for (PackageInfo pi : list) {
            r.total++;
            if (pi.requestedPermissions == null) {
                continue;
            }
            AppRisk a = new AppRisk();
            a.pkg = pi.packageName;
            try {
                ApplicationInfo ai = pi.applicationInfo;
                a.label = ai == null ? pi.packageName : String.valueOf(ai.loadLabel(pm));
                a.system = ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            } catch (Exception e) {
                a.label = pi.packageName;
            }
            for (String p : pi.requestedPermissions) {
                for (String[] d : DANGEROUS) {
                    if (d[0].equals(p)) {
                        a.score += Integer.parseInt(d[1]);
                        a.perms.add(d[2]);
                    }
                }
            }
            if (a.score > 0 && !a.system) {
                r.apps.add(a);
            }
        }
        Collections.sort(r.apps, new Comparator<AppRisk>() {
            @Override
            public int compare(AppRisk x, AppRisk y) {
                return y.score - x.score;
            }
        });
        for (AppRisk a : r.apps) {
            if (a.score >= 5) {
                r.flagged++;
            }
        }
        return r;
    }
}
