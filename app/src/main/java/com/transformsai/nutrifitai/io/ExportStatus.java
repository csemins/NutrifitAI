package com.transformsai.nutrifitai.io;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/** Lifecycle-aware channel from ExportService to whichever screen is showing export progress. */
public final class ExportStatus {
    public enum State { IDLE, RUNNING, DONE, CANCELLED, FAILED }

    public static final class Snapshot {
        public final State state;
        public final int progress;
        public final String message;
        public final java.util.ArrayList<String> files;

        public Snapshot(State s, int p, String m, java.util.ArrayList<String> f) {
            state = s;
            progress = p;
            message = m;
            files = f;
        }
    }

    private static final MutableLiveData<Snapshot> LIVE = new MutableLiveData<>(new Snapshot(State.IDLE, 0, null, null));

    private ExportStatus() {
    }

    public static LiveData<Snapshot> observe() {
        return LIVE;
    }

    static void post(State s, int p, String m, java.util.ArrayList<String> files) {
        LIVE.postValue(new Snapshot(s, p, m, files));
    }
}
