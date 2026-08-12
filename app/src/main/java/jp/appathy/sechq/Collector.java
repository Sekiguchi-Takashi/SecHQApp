package jp.appathy.sechq;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;

import org.json.JSONObject;

import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Collector {

    public static LinkedHashMap<String, String> asset(Context c) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("メーカー", Build.MANUFACTURER);
        m.put("モデル", Build.MODEL);
        m.put("端末名", Build.DEVICE);
        m.put("Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        m.put("ビルド", Build.DISPLAY);
        m.put("セキュリティパッチ", patchLevel());
        m.put("パッチ経過日数", patchAgeDays() < 0 ? "不明" : patchAgeDays() + " 日");
        m.put("画面ロック", isDeviceSecure(c) ? "設定あり" : "未設定");
        m.put("画面自動オフ", screenTimeoutSec(c) + " 秒");
        m.put("開発者オプション", global(c, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) ? "有効" : "無効");
        m.put("USBデバッグ", global(c, Settings.Global.ADB_ENABLED) ? "有効" : "無効");
        m.put("提供元不明アプリ", unknownSources(c) ? "許可あり" : "許可なし");
        m.put("インストール済アプリ", appCount(c) + " 件");
        m.put("WebView", pkgVersion(c, "com.google.android.webview", "com.android.webview"));
        m.put("Chrome", pkgVersion(c, "com.android.chrome", "com.google.android.chrome"));
        m.put("内部ストレージ空き", freeStorageGb() + " GB");
        m.put("収集日時", now());
        return m;
    }

    public static LinkedHashMap<String, String> network(Context c) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("接続種別", transport(c));
        m.put("SSID", ssid(c));
        m.put("VPN", vpnActive(c) ? "接続中" : "未使用");
        String sec = wifiSecurity(c);
        if (sec != null) {
            m.put("Wi-Fi暗号化", sec);
        }
        m.put("プライベートDNS", privateDns(c));
        m.put("DNSサーバー", dnsServers(c));
        m.put("IPアドレス", ipAddress(c));
        m.put("収集日時", now());
        return m;
    }

    public static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN).format(new Date());
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).format(new Date());
    }

    public static JSONObject toJson(Map<String, String> m) {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String> e : m.entrySet()) {
            try {
                o.put(e.getKey(), e.getValue());
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    // ---- individual facts ----

    public static String patchLevel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            String p = Build.VERSION.SECURITY_PATCH;
            return (p == null || p.isEmpty()) ? "不明" : p;
        }
        return "不明";
    }

    public static long patchAgeDays() {
        String p = patchLevel();
        if ("不明".equals(p)) {
            return -1;
        }
        try {
            Date d = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(p);
            if (d == null) {
                return -1;
            }
            return (System.currentTimeMillis() - d.getTime()) / 86400000L;
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean isDeviceSecure(Context c) {
        try {
            KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null) {
                return false;
            }
            return km.isDeviceSecure();
        } catch (Exception e) {
            return false;
        }
    }

    public static int screenTimeoutSec(Context c) {
        try {
            return Settings.System.getInt(c.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, 60000) / 1000;
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean global(Context c, String key) {
        try {
            return Settings.Global.getInt(c.getContentResolver(), key, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean unknownSources(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return c.getPackageManager().canRequestPackageInstalls();
            }
            return Settings.Secure.getInt(c.getContentResolver(), "install_non_market_apps", 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    public static int appCount(Context c) {
        try {
            return c.getPackageManager().getInstalledApplications(0).size();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String pkgVersion(Context c, String... names) {
        PackageManager pm = c.getPackageManager();
        for (String n : names) {
            try {
                PackageInfo pi = pm.getPackageInfo(n, 0);
                return pi.versionName;
            } catch (Exception ignored) {
            }
        }
        return "未検出";
    }

    private static String freeStorageGb() {
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            double gb = (double) fs.getAvailableBytes() / (1024.0 * 1024.0 * 1024.0);
            return String.format(Locale.US, "%.1f", gb);
        } catch (Exception e) {
            return "不明";
        }
    }

    private static NetworkCapabilities caps(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return null;
            }
            Network n = cm.getActiveNetwork();
            if (n == null) {
                return null;
            }
            return cm.getNetworkCapabilities(n);
        } catch (Exception e) {
            return null;
        }
    }

    private static LinkProperties link(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return null;
            }
            Network n = cm.getActiveNetwork();
            if (n == null) {
                return null;
            }
            return cm.getLinkProperties(n);
        } catch (Exception e) {
            return null;
        }
    }

    public static String transport(Context c) {
        NetworkCapabilities nc = caps(c);
        if (nc == null) {
            return "オフライン";
        }
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "Wi-Fi";
        }
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "モバイル回線";
        }
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "有線";
        }
        return "その他";
    }

    public static boolean isWifi(Context c) {
        return "Wi-Fi".equals(transport(c));
    }

    public static boolean vpnActive(Context c) {
        NetworkCapabilities nc = caps(c);
        if (nc == null) {
            return false;
        }
        return nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    public static String ssid(Context c) {
        try {
            if (!isWifi(c)) {
                return "-";
            }
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return "不明";
            }
            WifiInfo wi = wm.getConnectionInfo();
            if (wi == null) {
                return "不明";
            }
            String s = wi.getSSID();
            if (s == null) {
                return "不明";
            }
            s = s.replace("\"", "");
            if (s.isEmpty() || s.contains("unknown")) {
                return "不明 (位置情報の許可が必要)";
            }
            return s;
        } catch (Exception e) {
            return "不明";
        }
    }

    /** null = 判定不可(非Wi-Fi or API<31) */
    public static String wifiSecurity(Context c) {
        try {
            if (!isWifi(c) || Build.VERSION.SDK_INT < 31) {
                return null;
            }
            WifiManager wm = (WifiManager) c.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm == null) {
                return null;
            }
            WifiInfo wi = wm.getConnectionInfo();
            if (wi == null) {
                return null;
            }
            int t = wi.getCurrentSecurityType();
            switch (t) {
                case WifiInfo.SECURITY_TYPE_OPEN:
                    return "オープン(暗号化なし)";
                case WifiInfo.SECURITY_TYPE_WEP:
                    return "WEP";
                case WifiInfo.SECURITY_TYPE_PSK:
                    return "WPA/WPA2-PSK";
                case WifiInfo.SECURITY_TYPE_SAE:
                    return "WPA3-SAE";
                case WifiInfo.SECURITY_TYPE_EAP:
                case WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE:
                    return "エンタープライズ";
                case WifiInfo.SECURITY_TYPE_OWE:
                    return "OWE";
                default:
                    return "その他/不明";
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static String privateDns(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return "非対応OS";
        }
        LinkProperties lp = link(c);
        if (lp == null) {
            return "不明";
        }
        if (lp.isPrivateDnsActive()) {
            String h = lp.getPrivateDnsServerName();
            return (h == null || h.isEmpty()) ? "有効 (自動)" : "有効 (" + h + ")";
        }
        return "無効";
    }

    public static boolean privateDnsActive(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false;
        }
        LinkProperties lp = link(c);
        return lp != null && lp.isPrivateDnsActive();
    }

    public static String dnsServers(Context c) {
        LinkProperties lp = link(c);
        if (lp == null) {
            return "不明";
        }
        List<InetAddress> list = lp.getDnsServers();
        if (list == null || list.isEmpty()) {
            return "不明";
        }
        StringBuilder sb = new StringBuilder();
        for (InetAddress a : list) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(a.getHostAddress());
        }
        return sb.toString();
    }

    public static String ipAddress(Context c) {
        LinkProperties lp = link(c);
        if (lp == null) {
            return "不明";
        }
        StringBuilder sb = new StringBuilder();
        for (android.net.LinkAddress la : lp.getLinkAddresses()) {
            String h = la.getAddress().getHostAddress();
            if (h != null && h.contains(".")) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(h);
            }
        }
        return sb.length() == 0 ? "不明" : sb.toString();
    }
}
