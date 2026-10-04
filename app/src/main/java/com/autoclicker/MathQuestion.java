package com.autoclicker;

import android.graphics.Rect;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the game's "Please verify this simple questions, what is 4 + 3" out of the text an OCR pass
 * found on screen, works out the answer, and says which on-screen number keys to tap and which
 * button submits it.
 *
 * The equation is read only from the question line itself (the one with "what"/"verify"), so the
 * game's HUD numbers (CW Time, HP, EXP, level) can't be mistaken for it. The digit keys and the OK
 * button are found by where OCR saw them; the caller hides its own ring overlays first, so the only
 * digits below the keyboard are the real keys. If the question can't be read with confidence, or a
 * digit key or the OK button is missing, solve() returns null and the caller just alerts you
 * instead of typing a guess.
 */
final class MathQuestion {

    /** A whole line OCR read, e.g. "Please verify this simple questions, what is 4 + 3". */
    static final class Line {
        final String text;
        final Rect box;

        Line(String text, Rect box) {
            this.text = text;
            this.box = box;
        }
    }

    /** One word/element OCR read, with where it sits on screen (pixels). */
    static final class Word {
        final String text;
        final Rect box;

        Word(String text, Rect box) {
            this.text = text;
            this.box = box;
        }
    }

    /** What to tap: the key for each digit of the answer, in order, then the submit button. */
    static final class Plan {
        final int answer;
        final List<Rect> digitKeys;
        final Rect submit;

        Plan(int answer, List<Rect> digitKeys, Rect submit) {
            this.answer = answer;
            this.digitKeys = digitKeys;
            this.submit = submit;
        }
    }

    // The question line always says one of these; used to pick it out from the HUD.
    private static final String[] QUESTION_MARKERS = {"what", "verify", "simple question"};
    // Words that mean "send this answer": the game's own OK, or the keyboard's enter key.
    private static final String[] SUBMIT_WORDS = {
            "ok", "okay", "confirm", "submit", "send", "enter", "done", "go", "yes", "accept", "확인"
    };

    private MathQuestion() {
    }

    static Plan solve(List<Line> lines, List<Word> words, int keyboardTop) {
        long answer = readAnswer(lines);
        if (answer < 0 || answer > 9999) return null;   // nothing to read, or can't type it

        String digits = Long.toString(answer);
        List<Rect> keys = new ArrayList<>();
        for (int i = 0; i < digits.length(); i++) {
            Rect key = keyFor(words, keyboardTop, digits.charAt(i));
            if (key == null) return null;               // a key we need isn't on the keyboard
            keys.add(key);
        }
        Rect submit = submitButton(words);
        if (submit == null) return null;                // nothing we're sure will send it
        return new Plan((int) answer, keys, submit);
    }

    /** The button to tap on the 4-choice panel, and what it says. */
    static final class Choice {
        final long answer;
        final String label;
        final Rect button;

        Choice(long answer, String label, Rect button) {
            this.answer = answer;
            this.label = label;
            this.button = button;
        }
    }

    // "A) 18", "b ) 9", "C. 12"; or just the number when OCR splits the letter off.
    private static final Pattern OPTION = Pattern.compile("^\\s*(?:[A-Da-d]\\s*[).:]\\s*)?(\\d{1,5})\\s*$");

    /**
     * The anti-bot panel since 2026-10-04: "Please verify this simple questions, what is 6 + 6",
     * then "Tap the correct answer:" over four buttons "A) 18" "B) 9" "C) 12" "D) 22". Works out the
     * answer and returns the one button showing it. Null unless it's sure: the question read, at
     * least 3 options seen below it, and exactly one of them equal to the answer.
     */
    static Choice solveChoice(List<Line> lines) {
        Line question = null;
        long answer = -1;
        for (Line line : lines) {
            if (!isQuestionLine(line.text)) continue;
            answer = equationIn(line.text);
            if (answer >= 0) {
                question = line;
                break;
            }
        }
        if (question == null) return null;
        // The buttons sit a few line-heights under the question (~7 and ~11 on the panel).
        int reach = Math.max(1, question.box.height()) * 16;
        int options = 0;
        Choice match = null;
        for (Line line : lines) {
            if (line == question || line.box.top < question.box.bottom
                    || line.box.top > question.box.bottom + reach) continue;
            Matcher m = OPTION.matcher(line.text);
            if (!m.matches()) continue;
            options++;
            if (Long.parseLong(m.group(1)) != answer) continue;
            if (match != null) return null;             // two buttons say it: don't guess
            match = new Choice(answer, line.text.trim(), line.box);
        }
        return options >= 3 ? match : null;
    }

    private static boolean isQuestionLine(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String marker : QUESTION_MARKERS) if (lower.contains(marker)) return true;
        return false;
    }

    /** -1 when the question line couldn't be found or read as a single "A op B". */
    private static long readAnswer(List<Line> lines) {
        for (Line line : lines) {
            String lower = line.text.toLowerCase(Locale.ROOT);
            boolean isQuestion = false;
            for (String marker : QUESTION_MARKERS) {
                if (lower.contains(marker)) {
                    isQuestion = true;
                    break;
                }
            }
            if (!isQuestion) continue;
            long answer = equationIn(line.text);
            if (answer >= 0) return answer;
        }
        return -1;
    }

    /** The result of the first "A op B" in a string, or -1 if there's no valid one. */
    private static long equationIn(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = normalise(text.charAt(i));
            boolean keep = Character.isDigit(c) || c == '+' || c == '-' || c == '*' || c == '/';
            sb.append(keep ? c : ' ');
        }
        String s = sb.toString();
        int n = s.length();
        for (int i = 0; i < n; ) {
            if (!Character.isDigit(s.charAt(i))) {
                i++;
                continue;
            }
            int aEnd = i;
            while (aEnd < n && Character.isDigit(s.charAt(aEnd))) aEnd++;
            int op = aEnd;
            while (op < n && s.charAt(op) == ' ') op++;
            if (op >= n || "+-*/".indexOf(s.charAt(op)) < 0) {
                i = aEnd + 1;
                continue;
            }
            int bStart = op + 1;
            while (bStart < n && s.charAt(bStart) == ' ') bStart++;
            if (bStart >= n || !Character.isDigit(s.charAt(bStart))) {
                i = aEnd + 1;
                continue;
            }
            int bEnd = bStart;
            while (bEnd < n && Character.isDigit(s.charAt(bEnd))) bEnd++;
            try {
                long a = Long.parseLong(s.substring(i, aEnd));
                long b = Long.parseLong(s.substring(bStart, bEnd));
                return compute(a, s.charAt(op), b);
            } catch (NumberFormatException e) {
                return -1;                              // a run of digits too long to be real
            }
        }
        return -1;
    }

    private static long compute(long a, char op, long b) {
        switch (op) {
            case '+': return a + b;
            case '-': return a - b;                     // a negative answer is dropped by the caller
            case '*': return a * b;
            case '/': return b != 0 && a % b == 0 ? a / b : -1;
            default: return -1;
        }
    }

    private static char normalise(char c) {
        switch (c) {
            case '×':  // ×
            case 'x':
            case 'X':
            case '✕':  // ✕
                return '*';
            case '−':  // −
            case '–':  // –
            case '—':  // —
                return '-';
            case '÷':  // ÷
                return '/';
            default:
                return c;
        }
    }

    /** The key for one digit: a single-character word sitting on the keyboard. */
    private static Rect keyFor(List<Word> words, int keyboardTop, char digit) {
        for (Word w : words) {
            if (w.box.centerY() < keyboardTop) continue;
            String t = w.text.trim();
            if (t.length() == 1 && t.charAt(0) == digit) return w.box;
        }
        return null;
    }

    private static Rect submitButton(List<Word> words) {
        for (Word w : words) {
            String t = letters(w.text);
            for (String s : SUBMIT_WORDS) {
                if (t.equals(s)) return w.box;
            }
        }
        return null;
    }

    /** Just the letters of a word, lower-cased, so "OK!" and "Confirm " match cleanly. */
    private static String letters(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.length() > 0 ? sb.toString() : s.trim().toLowerCase(Locale.ROOT);
    }
}
