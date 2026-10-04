package com.autoclicker;

import android.graphics.Bitmap;
import android.graphics.Rect;
import android.util.Log;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;

/**
 * On-device text reading (Google ML Kit, bundled into the app: no network, no account). Hands back
 * both the whole lines it found (to read the question from) and the single words (to find the
 * keyboard's digit keys and the OK button). Reading is asynchronous; the bitmap is recycled once
 * it's done, so the caller passes ownership in and must not touch the bitmap afterwards.
 */
final class Ocr {

    private static final String TAG = "AutoClicker";

    interface Callback {
        /** Always called once, on the main thread; empty lists mean nothing was read. */
        void onText(List<MathQuestion.Line> lines, List<MathQuestion.Word> words);
    }

    private Ocr() {
    }

    static void read(Bitmap bitmap, Callback cb) {
        read(bitmap, cb, true);
    }

    /** With recycle false the caller keeps the bitmap and recycles it itself once done. */
    static void read(Bitmap bitmap, Callback cb, boolean recycle) {
        if (bitmap == null) {
            cb.onText(new ArrayList<>(), new ArrayList<>());
            return;
        }
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> {
                    List<MathQuestion.Line> lines = new ArrayList<>();
                    List<MathQuestion.Word> words = new ArrayList<>();
                    flatten(text, lines, words);
                    finish(recycle ? bitmap : null, recognizer);
                    cb.onText(lines, words);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "OCR failed", e);
                    finish(recycle ? bitmap : null, recognizer);
                    cb.onText(new ArrayList<>(), new ArrayList<>());
                });
    }

    private static void flatten(Text text, List<MathQuestion.Line> lines, List<MathQuestion.Word> words) {
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect lineBox = line.getBoundingBox();
                if (lineBox != null) lines.add(new MathQuestion.Line(line.getText(), lineBox));
                for (Text.Element element : line.getElements()) {
                    Rect box = element.getBoundingBox();
                    if (box != null) words.add(new MathQuestion.Word(element.getText(), box));
                }
            }
        }
    }

    private static void finish(Bitmap bitmap, TextRecognizer recognizer) {
        recognizer.close();
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
    }
}
