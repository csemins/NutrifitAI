package com.transformsai.nutrifitai.ui.auth;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;

import com.transformsai.nutrifitai.App;

/** Survives rotation: current mode, busy flag and last error. */
public class AuthViewModel extends ViewModel {
    public static final class State {
        public final boolean busy;
        public final String error;
        public final Boolean onboarded; // non-null when sign-in finished

        State(boolean busy, String error, Boolean onboarded) {
            this.busy = busy;
            this.error = error;
            this.onboarded = onboarded;
        }
    }

    private final SavedStateHandle handle;
    private final MutableLiveData<State> state = new MutableLiveData<>(new State(false, null, null));

    public AuthViewModel(SavedStateHandle h) {
        handle = h;
    }

    public LiveData<State> state() {
        return state;
    }

    public int mode() {
        Integer m = handle.get("mode");
        return m == null ? 0 : m;
    }

    public void setMode(int m) {
        handle.set("mode", m);
        state.setValue(new State(false, null, null));
    }

    private void busy() {
        state.setValue(new State(true, null, null));
    }

    private void done(com.transformsai.nutrifitai.data.Result<Boolean> r) {
        state.setValue(r.ok() ? new State(false, null, r.value) : new State(false, r.error, null));
    }

    public void signIn(String email, String pw) {
        busy();
        App.graph().auth.signIn(email, pw, this::done);
    }

    public void signUp(String name, String email, String pw) {
        busy();
        App.graph().auth.signUp(name, email, pw, this::done);
    }

    public void google(String idToken) {
        busy();
        App.graph().auth.signInWithGoogle(idToken, this::done);
    }

    public void error(String msg) {
        state.setValue(new State(false, msg, null));
    }

    public boolean isBusy() {
        State s = state.getValue();
        return s != null && s.busy;
    }
}
