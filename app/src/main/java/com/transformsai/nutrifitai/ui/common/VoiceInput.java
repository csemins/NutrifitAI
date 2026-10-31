package com.transformsai.nutrifitai.ui.common;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.R;

import java.util.ArrayList;

/**
 * Speech-to-text for one utterance at a time (meal logging and the AI coach).
 *
 * <p>A fresh {@link SpeechRecognizer} is created for every session and destroyed when it ends.
 * Re-using one recognizer after a result makes many recognition services answer the next
 * startListening() with an immediate ERROR_CLIENT / ERROR_RECOGNIZER_BUSY / ERROR_NO_MATCH ("Didn't
 * catch that"), which is the bug this class avoids. Errors from an old session are ignored, and a busy
 * service is retried once.
 */
public final class VoiceInput {
    public interface Callback {
        void onListening(boolean listening);

        void onPartial(String text);

        void onResult(String text);

        /** @param message string resource for the user */
        void onError(int message);
    }

    private final Context context;
    private final Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private SpeechRecognizer recognizer;
    private int session;
    private boolean listening, gotResult, retried;
    private String language = "en-US";

    public VoiceInput(Context c, Callback cb) {
        this.context = c.getApplicationContext();
        this.cb = cb;
    }

    public static boolean available(Context c) {
        return SpeechRecognizer.isRecognitionAvailable(c);
    }

    public boolean listening() {
        return listening;
    }

    /** Starts a new session (any previous one is cancelled first). */
    public void start(String languageTag) {
        language = languageTag;
        retried = false;
        begin();
    }

    private void begin() {
        release();
        final int id = ++session;
        gotResult = false;
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(new Listener(id));
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        setListening(true);
        recognizer.startListening(i);
    }

    /** Ends the current utterance early; the result (if any) still arrives. */
    public void stop() {
        if (recognizer != null && listening) recognizer.stopListening();
        setListening(false);
    }

    /** Destroys the recognizer and releases the microphone. Call from onStop. */
    public void release() {
        session++;
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (RuntimeException ignored) {
                // service already gone
            }
            recognizer = null;
        }
        setListening(false);
    }

    private void setListening(boolean on) {
        if (listening == on) return;
        listening = on;
        cb.onListening(on);
    }

    private final class Listener implements RecognitionListener {
        private final int id;

        Listener(int id) {
            this.id = id;
        }

        private boolean stale() {
            return id != session;
        }

        @Override public void onReadyForSpeech(Bundle p) { }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rms) { }
        @Override public void onBufferReceived(byte[] buf) { }
        @Override public void onEvent(int t, Bundle p) { }

        @Override
        public void onEndOfSpeech() {
            if (!stale()) setListening(false); // the recognizer stops by itself; do not call stopListening again
        }

        @Override
        public void onPartialResults(Bundle r) {
            if (stale()) return;
            ArrayList<String> m = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (m != null && !m.isEmpty() && !m.get(0).isEmpty()) cb.onPartial(m.get(0));
        }

        @Override
        public void onResults(Bundle r) {
            if (stale()) return;
            gotResult = true;
            ArrayList<String> m = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = m == null || m.isEmpty() ? "" : m.get(0).trim();
            main.post(() -> {
                release(); // a fresh recognizer next time
                if (text.isEmpty()) cb.onError(R.string.voice_error);
                else cb.onResult(text);
            });
        }

        @Override
        public void onError(int error) {
            if (stale() || gotResult) return;
            if ((error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) && !retried) {
                retried = true; // the previous session had not finished closing: try once more with a new recognizer
                main.postDelayed(VoiceInput.this::begin, 350);
                return;
            }
            main.post(() -> {
                release();
                cb.onError(error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? R.string.voice_perm_denied
                        : error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
                        ? R.string.voice_network : R.string.voice_error);
            });
        }
    }
}
