package jp.appathy.sechq;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * 無料・APIキー不要の外部APIで端末の状況を照合する。
 * すべて呼び出し側でワーカースレッドから実行すること。
 */
public class NetProbe {

    public static final String F_IPINFO = "ipinfo.json";

    private static final int TIMEOUT = 8000;

    // データセンター/ホスティング事業者を示唆する語（VPN・プロキシ経由の推定に使う）
    private static final String[] HOSTING_HINTS = {
            "hosting", "data center", "datacenter", "cloud", "server",
            "vpn", "proxy", "amazon", "google llc", "microsoft", "digitalocean",
            "linode", "vultr", "ovh", "hetzner", "leaseweb", "m247", "choopa"
    };

    // ---------------- IP ----------------

    public static class IpInfo {
        public boolean ok;
        public String error;
        public String ip = "";
        public String country = "";
        public String countryCode = "";
        public String city = "";
        public String region = "";
        public String org = "";
        public String asn = "";
        public boolean hostingLike;
        public String at = "";
    }

    public static IpInfo lookupIp(Context c) {
        IpInfo r = new IpInfo();
        try {
            String body = get("https://ipapi.co/json/");
            if (body == null) {
                r.error = "IP情報を取得できませんでした（通信を確認してください）";
                return r;
            }
            JSONObject o = new JSONObject(body);
            if (o.has("error")) {
                r.error = "IP情報APIがエラーを返しました: " + o.optString("reason", "");
                return r;
            }
            r.ip = o.optString("ip", "");
            r.country = o.optString("country_name", "");
            r.countryCode = o.optString("country_code", "");
            r.city = o.optString("city", "");
            r.region = o.optString("region", "");
            r.org = o.optString("org", "");
            r.asn = o.optString("asn", "");
            String hay = (r.org + " " + o.optString("network", "")).toLowerCase(Locale.US);
            for (String h : HOSTING_HINTS) {
                if (hay.contains(h)) {
                    r.hostingLike = true;
                    break;
                }
            }
            r.at = Collector.now();
            r.ok = true;
            save(c, r);
        } catch (Exception e) {
            r.error = "IP情報の解析に失敗しました";
        }
        return r;
    }

    private static void save(Context c, IpInfo r) {
        try {
            JSONObject o = new JSONObject();
            o.put("ip", r.ip);
            o.put("国", r.country);
            o.put("国コード", r.countryCode);
            o.put("地域", r.region);
            o.put("都市", r.city);
            o.put("回線事業者", r.org);
            o.put("ASN", r.asn);
            o.put("ホスティング系", r.hostingLike);
            o.put("取得日時", r.at);
            Store.saveObject(c, F_IPINFO, o);
        } catch (Exception ignored) {
        }
    }

    public static JSONObject saved(Context c) {
        JSONObject o = Store.loadObject(c, F_IPINFO);
        return o.has("取得日時") ? o : null;
    }

    // ---------------- email ----------------

    public static class MailInfo {
        public boolean ok;
        public String error;
        public String address = "";
        public boolean formatOk;
        public boolean disposable;
        public boolean dnsOk;
        public String domain = "";
    }

    public static MailInfo verifyEmail(String address) {
        MailInfo r = new MailInfo();
        r.address = address == null ? "" : address.trim();
        if (r.address.isEmpty()) {
            r.error = "アドレスが空です";
            return r;
        }
        try {
            String body = get("https://disify.com/api/email/"
                    + URLEncoder.encode(r.address, "UTF-8"));
            if (body == null) {
                r.error = "検証APIに接続できませんでした";
                return r;
            }
            JSONObject o = new JSONObject(body);
            r.formatOk = o.optBoolean("format", false);
            r.disposable = o.optBoolean("disposable", false);
            r.dnsOk = o.optBoolean("dns", false);
            r.domain = o.optString("domain", "");
            r.ok = true;
        } catch (Exception e) {
            r.error = "検証結果の解析に失敗しました";
        }
        return r;
    }

    public static String mailVerdict(MailInfo m) {
        if (!m.ok) {
            return m.error == null ? "検証不可" : m.error;
        }
        if (!m.formatOk) {
            return "形式が不正です";
        }
        if (m.disposable) {
            return "使い捨てメールです（業務利用は避けてください）";
        }
        if (!m.dnsOk) {
            return "メールサーバーが見つかりません（ドメイン失効の可能性）";
        }
        return "問題は見つかりませんでした";
    }

    // ---------------- reverse geocode ----------------

    public static String reverseGeocode(double lat, double lng) {
        try {
            String body = get("https://api.bigdatacloud.net/data/reverse-geocode-client"
                    + "?latitude=" + lat + "&longitude=" + lng + "&localityLanguage=ja");
            if (body == null) {
                return null;
            }
            JSONObject o = new JSONObject(body);
            StringBuilder sb = new StringBuilder();
            String country = o.optString("countryName", "");
            String p = o.optString("principalSubdivision", "");
            String city = o.optString("city", "");
            String loc = o.optString("locality", "");
            if (!country.isEmpty()) {
                sb.append(country);
            }
            if (!p.isEmpty()) {
                sb.append(' ').append(p);
            }
            if (!city.isEmpty()) {
                sb.append(' ').append(city);
            }
            if (!loc.isEmpty() && !loc.equals(city)) {
                sb.append(' ').append(loc);
            }
            String s = sb.toString().trim();
            return s.isEmpty() ? null : s;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- http ----------------

    private static String get(String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(TIMEOUT);
            con.setReadTimeout(TIMEOUT);
            con.setRequestProperty("User-Agent", "SecHQApp");
            con.setRequestProperty("Accept", "application/json");
            int code = con.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && bo.size() < 256 * 1024) {
                bo.write(buf, 0, n);
            }
            in.close();
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }
}
