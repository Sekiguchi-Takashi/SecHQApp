package jp.appathy.sechq;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.List;

public class DeskInspector {

    public static final String F_INSPECTIONS = "inspections.json";
    private static final int MAX_EDGE = 2000;

    public interface Callback {
        void onDone(Result r);
    }

    public static class Result {
        public boolean ok;
        public String error;
        public String label = DocClassifier.L_PUBLIC;
        public int score;
        public int lines;
        public String excerpt = "";
        public List<String> hits;
    }

    public static void inspect(final Context c, final Uri image, final Callback cb) {
        final Result r = new Result();
        Bitmap bmp;
        try {
            bmp = decode(c, image);
        } catch (Exception e) {
            r.error = "画像を読み込めませんでした";
            cb.onDone(r);
            return;
        }
        if (bmp == null) {
            r.error = "画像を読み込めませんでした";
            cb.onDone(r);
            return;
        }

        try {
            TextRecognizer recognizer = TextRecognition.getClient(
                    new JapaneseTextRecognizerOptions.Builder().build());
            InputImage input = InputImage.fromBitmap(bmp, 0);
            recognizer.process(input)
                    .addOnSuccessListener(text -> {
                        StringBuilder sb = new StringBuilder();
                        int lines = 0;
                        for (Text.TextBlock b : text.getTextBlocks()) {
                            for (Text.Line l : b.getLines()) {
                                sb.append(l.getText()).append('\n');
                                lines++;
                            }
                        }
                        String body = sb.toString();
                        DocClassifier.Score s = DocClassifier.scoreText(body);
                        r.ok = true;
                        r.lines = lines;
                        r.score = s.score;
                        r.label = s.label;
                        r.hits = s.hits;
                        r.excerpt = body.length() > 300 ? body.substring(0, 300) : body;
                        recognizer.close();
                        cb.onDone(r);
                    })
                    .addOnFailureListener(e -> {
                        r.error = "文字認識に失敗しました: " + e.getMessage();
                        recognizer.close();
                        cb.onDone(r);
                    });
        } catch (Exception e) {
            r.error = "文字認識を初期化できませんでした";
            cb.onDone(r);
        }
    }

    private static Bitmap decode(Context c, Uri uri) throws Exception {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        InputStream in = c.getContentResolver().openInputStream(uri);
        BitmapFactory.decodeStream(in, null, probe);
        if (in != null) {
            in.close();
        }
        int edge = Math.max(probe.outWidth, probe.outHeight);
        int sample = 1;
        while (edge / sample > MAX_EDGE) {
            sample *= 2;
        }
        BitmapFactory.Options opt = new BitmapFactory.Options();
        opt.inSampleSize = sample;
        InputStream in2 = c.getContentResolver().openInputStream(uri);
        Bitmap bmp = BitmapFactory.decodeStream(in2, null, opt);
        if (in2 != null) {
            in2.close();
        }
        return bmp;
    }

    public static void record(Context c, Result r) {
        JSONArray arr = Store.loadArray(c, F_INSPECTIONS);
        try {
            JSONObject o = new JSONObject();
            o.put("t", Collector.now());
            o.put("label", r.label);
            o.put("score", r.score);
            o.put("lines", r.lines);
            JSONArray h = new JSONArray();
            if (r.hits != null) {
                for (String s : r.hits) {
                    h.put(s);
                }
            }
            o.put("hits", h);
            arr.put(o);
        } catch (Exception ignored) {
        }
        while (arr.length() > 100) {
            arr.remove(0);
        }
        Store.saveArray(c, F_INSPECTIONS, arr);
    }

    public static JSONObject latest(Context c) {
        JSONArray arr = Store.loadArray(c, F_INSPECTIONS);
        if (arr.length() == 0) {
            return null;
        }
        return arr.optJSONObject(arr.length() - 1);
    }
}
