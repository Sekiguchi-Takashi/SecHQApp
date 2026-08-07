package jp.appathy.sechq;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

public class Store {

    public static final String F_ACCOUNTS = "accounts.json";
    public static final String F_SNAPSHOT = "snapshot.json";
    public static final String F_LOCATION = "location.json";

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("sechq", Context.MODE_PRIVATE);
    }

    private static String read(Context c, String name) {
        try {
            InputStream in = new FileInputStream(c.getFileStreamPath(name));
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
            }
            in.close();
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(Context c, String name, String body) {
        try {
            FileOutputStream out = new FileOutputStream(c.getFileStreamPath(name));
            out.write(body.getBytes("UTF-8"));
            out.close();
        } catch (Exception e) {
            // ignore
        }
    }

    public static JSONArray loadArray(Context c, String name) {
        String s = read(c, name);
        if (s == null) {
            return new JSONArray();
        }
        try {
            return new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static void saveArray(Context c, String name, JSONArray a) {
        write(c, name, a.toString());
    }

    public static JSONObject loadObject(Context c, String name) {
        String s = read(c, name);
        if (s == null) {
            return new JSONObject();
        }
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static void saveObject(Context c, String name, JSONObject o) {
        write(c, name, o.toString());
    }
}
