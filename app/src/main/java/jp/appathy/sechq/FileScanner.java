package jp.appathy.sechq;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FileScanner {

    public static final String[] DANGER_EXT = {
            "exe", "scr", "com", "pif", "bat", "cmd", "ps1", "vbs", "vbe",
            "js", "jse", "wsf", "hta", "jar", "msi", "reg", "lnk", "apk",
            "iso", "img", "docm", "xlsm", "pptm", "xlsb", "chm"
    };

    public static final String[] RANSOM_EXT = {
            "locked", "encrypted", "crypt", "crypted", "enc", "lockbit",
            "wncry", "wnry", "cerber", "ryk", "phobos", "makop", "deadbolt"
    };

    public static class Result {
        public int total;
        public List<String> danger = new ArrayList<>();
        public List<String> ransom = new ArrayList<>();
        public List<String> doubleExt = new ArrayList<>();
        public boolean scanned;
        public String error;
    }

    public static Result scan(Context c, String treeUri) {
        Result r = new Result();
        if (treeUri == null || treeUri.isEmpty()) {
            r.error = "監視フォルダが未選択です";
            return r;
        }
        try {
            DocumentFile root = DocumentFile.fromTreeUri(c, Uri.parse(treeUri));
            if (root == null || !root.canRead()) {
                r.error = "フォルダを読み取れません。再選択してください";
                return r;
            }
            walk(root, r, 0);
            r.scanned = true;
        } catch (Exception e) {
            r.error = "スキャン失敗: " + e.getMessage();
        }
        return r;
    }

    private static void walk(DocumentFile dir, Result r, int depth) {
        if (depth > 3 || r.total > 3000) {
            return;
        }
        DocumentFile[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (DocumentFile f : list) {
            if (f.isDirectory()) {
                walk(f, r, depth + 1);
                continue;
            }
            String name = f.getName();
            if (name == null) {
                continue;
            }
            r.total++;
            String lower = name.toLowerCase(Locale.US);
            String ext = ext(lower);
            if (match(ext, RANSOM_EXT)) {
                r.ransom.add(name);
            } else if (match(ext, DANGER_EXT)) {
                r.danger.add(name);
            }
            if (isDoubleExt(lower)) {
                r.doubleExt.add(name);
            }
        }
    }

    private static String ext(String lower) {
        int i = lower.lastIndexOf('.');
        return i < 0 ? "" : lower.substring(i + 1);
    }

    private static boolean match(String ext, String[] arr) {
        for (String a : arr) {
            if (a.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDoubleExt(String lower) {
        String[] docLike = {"pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "jpg", "png", "zip"};
        String ext = ext(lower);
        if (ext.isEmpty()) {
            return false;
        }
        String head = lower.substring(0, lower.length() - ext.length() - 1);
        String prev = ext(head);
        if (prev.isEmpty()) {
            return false;
        }
        boolean prevIsDoc = false;
        for (String d : docLike) {
            if (d.equals(prev)) {
                prevIsDoc = true;
                break;
            }
        }
        return prevIsDoc && match(ext, DANGER_EXT);
    }
}
