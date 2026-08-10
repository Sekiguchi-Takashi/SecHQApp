package jp.appathy.sechq;

import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DocClassifier {

    public static final String L_TOP = "極秘";
    public static final String L_CONF = "社外秘";
    public static final String L_INTERNAL = "社内限";
    public static final String L_PUBLIC = "公開相当";

    private static final int MAX_FILES = 300;
    private static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final int MAX_TEXT = 300000;

    public static class Doc {
        public String name;
        public String uri;
        public String type;
        public String label;
        public int score;
        public boolean textOk;
        public List<String> hits = new ArrayList<>();
    }

    public static class Result {
        public int scanned;
        public String error;
        public List<Doc> docs = new ArrayList<>();

        public int count(String label) {
            int n = 0;
            for (Doc d : docs) {
                if (label.equals(d.label)) {
                    n++;
                }
            }
            return n;
        }

        public int sensitive() {
            return count(L_TOP) + count(L_CONF);
        }
    }

    // keyword, weight, tag
    private static final String[][] KEYWORDS = {
            {"社外秘", "4", "社外秘表記"},
            {"極秘", "4", "極秘表記"},
            {"機密", "3", "機密表記"},
            {"confidential", "3", "Confidential表記"},
            {"秘密保持", "3", "NDA"},
            {"給与", "3", "給与情報"},
            {"賞与", "2", "賞与情報"},
            {"人事評価", "3", "人事評価"},
            {"履歴書", "3", "履歴書"},
            {"職務経歴書", "3", "職務経歴"},
            {"口座番号", "4", "口座番号"},
            {"暗証番号", "4", "暗証番号"},
            {"パスワード", "3", "パスワード記載"},
            {"マイナンバー", "4", "マイナンバー"},
            {"個人番号", "4", "個人番号"},
            {"健康保険", "2", "健康保険"},
            {"生年月日", "2", "生年月日"},
            {"住所", "1", "住所"},
            {"電話番号", "1", "電話番号"},
            {"契約書", "2", "契約書"},
            {"覚書", "2", "覚書"},
            {"見積書", "1", "見積書"},
            {"請求書", "1", "請求書"},
            {"顧客名簿", "4", "顧客名簿"},
            {"名簿", "2", "名簿"},
    };

    private static final Pattern P_CARD =
            Pattern.compile("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b");
    private static final Pattern P_MYNUMBER =
            Pattern.compile("\\b\\d{4}[ -]?\\d{4}[ -]?\\d{4}\\b");
    private static final Pattern P_MAIL =
            Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]{2,}");
    private static final Pattern P_TEL =
            Pattern.compile("\\b0\\d{1,4}-\\d{1,4}-\\d{4}\\b");
    private static final Pattern P_TAG = Pattern.compile("<[^>]*>");

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
            walk(c, root, r, 0);
            Collections.sort(r.docs, new Comparator<Doc>() {
                @Override
                public int compare(Doc a, Doc b) {
                    return b.score - a.score;
                }
            });
        } catch (Exception e) {
            r.error = "解析失敗: " + e.getMessage();
        }
        return r;
    }

    private static void walk(Context c, DocumentFile dir, Result r, int depth) {
        if (depth > 3 || r.scanned >= MAX_FILES) {
            return;
        }
        DocumentFile[] list = dir.listFiles();
        if (list == null) {
            return;
        }
        for (DocumentFile f : list) {
            if (r.scanned >= MAX_FILES) {
                return;
            }
            if (f.isDirectory()) {
                walk(c, f, r, depth + 1);
                continue;
            }
            String name = f.getName();
            if (name == null) {
                continue;
            }
            String ext = ext(name);
            if (!isTarget(ext)) {
                continue;
            }
            if (f.length() > MAX_BYTES) {
                continue;
            }
            r.scanned++;
            r.docs.add(classify(c, f, name, ext));
        }
    }

    private static boolean isTarget(String ext) {
        String[] t = {"docx", "xlsx", "pptx", "docm", "xlsm", "pptm",
                "pdf", "txt", "csv", "md", "json", "html"};
        for (String s : t) {
            if (s.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    private static Doc classify(Context c, DocumentFile f, String name, String ext) {
        Doc d = new Doc();
        d.name = name;
        d.uri = f.getUri().toString();
        d.type = ext.toUpperCase(Locale.US);

        String text = "";
        try {
            if (isOoxml(ext)) {
                text = readOoxml(c, f.getUri());
            } else if ("pdf".equals(ext)) {
                text = readPdf(c, f.getUri());
            } else {
                text = new String(readAll(c, f.getUri(), MAX_BYTES), "UTF-8");
            }
        } catch (Exception ignored) {
        }

        d.textOk = text != null && text.trim().length() > 0;
        Score sc = scoreText(name + "\n" + (text == null ? "" : text));
        d.score = sc.score;
        d.hits = sc.hits;
        d.label = sc.label;
        return d;
    }

    public static class Score {
        public int score;
        public String label;
        public List<String> hits = new ArrayList<>();
    }

    public static Score scoreText(String haystack) {
        Score s = new Score();
        if (haystack == null) {
            haystack = "";
        }
        String lower = haystack.toLowerCase(Locale.US);
        for (String[] k : KEYWORDS) {
            if (lower.contains(k[0].toLowerCase(Locale.US))) {
                s.score += Integer.parseInt(k[1]);
                s.hits.add(k[2]);
            }
        }
        if (find(P_CARD, haystack)) {
            s.score += 5;
            s.hits.add("16桁番号(カード等)");
        } else if (find(P_MYNUMBER, haystack)) {
            s.score += 4;
            s.hits.add("12桁番号(個人番号等)");
        }
        if (find(P_MAIL, haystack)) {
            s.score += 2;
            s.hits.add("メールアドレス");
        }
        if (find(P_TEL, haystack)) {
            s.score += 2;
            s.hits.add("電話番号");
        }
        s.label = label(s.score);
        return s;
    }

    public static String label(int score) {
        if (score >= 10) {
            return L_TOP;
        }
        if (score >= 6) {
            return L_CONF;
        }
        if (score >= 3) {
            return L_INTERNAL;
        }
        return L_PUBLIC;
    }

    private static boolean find(Pattern p, String s) {
        try {
            Matcher m = p.matcher(s);
            return m.find();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isOoxml(String ext) {
        return ext.startsWith("doc") || ext.startsWith("xls") || ext.startsWith("ppt");
    }

    private static String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.US);
    }

    private static byte[] readAll(Context c, Uri uri, int limit) throws Exception {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) {
            return new byte[0];
        }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0 && bo.size() < limit) {
            bo.write(buf, 0, n);
        }
        in.close();
        return bo.toByteArray();
    }

    private static String readOoxml(Context c, Uri uri) throws Exception {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) {
            return "";
        }
        ZipInputStream zis = new ZipInputStream(in);
        StringBuilder sb = new StringBuilder();
        ZipEntry e;
        byte[] buf = new byte[8192];
        while ((e = zis.getNextEntry()) != null && sb.length() < MAX_TEXT) {
            String n = e.getName();
            if (n == null || !n.endsWith(".xml")) {
                continue;
            }
            boolean wanted = n.startsWith("word/document")
                    || n.startsWith("word/header")
                    || n.startsWith("word/footer")
                    || n.startsWith("xl/sharedStrings")
                    || n.startsWith("xl/worksheets/")
                    || n.startsWith("ppt/slides/slide")
                    || n.startsWith("ppt/notesSlides/")
                    || n.startsWith("docProps/");
            if (!wanted) {
                continue;
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            int r;
            while ((r = zis.read(buf)) > 0 && bo.size() < MAX_TEXT) {
                bo.write(buf, 0, r);
            }
            String xml = bo.toString("UTF-8");
            sb.append(P_TAG.matcher(xml).replaceAll(" ")).append("\n");
        }
        zis.close();
        return sb.toString();
    }

    private static String readPdf(Context c, Uri uri) throws Exception {
        byte[] raw = readAll(c, uri, MAX_BYTES);
        String latin = new String(raw, "ISO-8859-1");
        StringBuilder out = new StringBuilder();

        int idx = 0;
        while (out.length() < MAX_TEXT) {
            int s = latin.indexOf("stream", idx);
            if (s < 0) {
                break;
            }
            int st = s + 6;
            if (st < latin.length() && latin.charAt(st) == '\r') {
                st++;
            }
            if (st < latin.length() && latin.charAt(st) == '\n') {
                st++;
            }
            int e = latin.indexOf("endstream", st);
            if (e < 0) {
                break;
            }
            idx = e + 9;
            byte[] chunk = new byte[e - st];
            System.arraycopy(raw, st, chunk, 0, Math.max(0, e - st));
            String piece = inflate(chunk);
            if (piece == null) {
                continue;
            }
            extractPdfText(piece, out);
        }

        if (out.length() == 0) {
            extractPdfText(latin, out);
        }
        return out.toString();
    }

    private static String inflate(byte[] data) {
        try {
            Inflater inf = new Inflater();
            inf.setInput(data);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished() && bo.size() < MAX_TEXT) {
                int n = inf.inflate(buf);
                if (n == 0) {
                    break;
                }
                bo.write(buf, 0, n);
            }
            inf.end();
            if (bo.size() == 0) {
                return null;
            }
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private static void extractPdfText(String content, StringBuilder out) {
        int i = 0;
        while (i < content.length() && out.length() < MAX_TEXT) {
            int a = content.indexOf('(', i);
            if (a < 0) {
                break;
            }
            int b = a + 1;
            StringBuilder cur = new StringBuilder();
            while (b < content.length()) {
                char ch = content.charAt(b);
                if (ch == '\\') {
                    b += 2;
                    continue;
                }
                if (ch == ')') {
                    break;
                }
                cur.append(ch);
                b++;
            }
            if (cur.length() > 0 && cur.length() < 500) {
                out.append(cur).append(' ');
            }
            i = b + 1;
        }
    }

    public static boolean applyLabel(Context c, Doc d) {
        try {
            if (d.name.startsWith("【")) {
                return false;
            }
            DocumentFile f = DocumentFile.fromSingleUri(c, Uri.parse(d.uri));
            if (f == null) {
                return false;
            }
            return f.renameTo("【" + d.label + "】" + d.name);
        } catch (Exception e) {
            return false;
        }
    }
}
