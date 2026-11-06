package com.transformsai.nutrifitai.fitness;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * Text-to-speech voice for workouts. Short time-critical words (rep counts, "3, 2, 1") flush the
 * queue so the voice never falls behind the movement; sentences are queued. Release in onDestroy.
 */
public final class VoiceCoach implements TextToSpeech.OnInitListener {
    private TextToSpeech tts;
    private boolean ready, muted;
    private final Deque<String> early = new ArrayDeque<>();
    private int utterance;

    public VoiceCoach(Context c) {
        tts = new TextToSpeech(c.getApplicationContext(), this);
    }

    @Override
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS || tts == null) return;
        int r = tts.setLanguage(Locale.US);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault());
        tts.setSpeechRate(1.05f);
        tts.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        ready = true;
        while (!early.isEmpty()) say(early.poll(), false);
    }

    public boolean available() {
        return ready;
    }

    public void setMuted(boolean m) {
        muted = m;
        if (m && tts != null) tts.stop();
    }

    public boolean muted() {
        return muted;
    }

    public void say(String text, boolean interrupt) {
        if (muted || text == null || text.isEmpty()) return;
        if (!ready) {
            if (early.size() < 3) early.add(text);
            return;
        }
        tts.speak(text, interrupt ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "nl" + (utterance++));
    }

    public void stop() {
        if (tts != null) tts.stop();
    }

    public void release() {
        ready = false;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }
}
