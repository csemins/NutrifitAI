package com.transformsai.nutrifitai.data.remote;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.transformsai.nutrifitai.data.Prefs;
import com.transformsai.nutrifitai.sync.SyncScheduler;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Realtime Database is used for what it is best at: live connection state and a tiny
 * cross-device "something changed" signal. Data itself lives in Firestore.
 *
 *   status/{uid}/devices/{installId} = {online, lastSeen, model}   (onDisconnect -> offline)
 *   status/{uid}/changes            = {by: installId, at: serverTime}
 *
 * When another device writes "changes", this device immediately schedules a pull sync.
 */
public final class PresenceManager {
    private final Context context;
    private final Prefs prefs;
    private final MutableLiveData<Boolean> connected = new MutableLiveData<>(false);
    private final MutableLiveData<Long> otherDeviceChange = new MutableLiveData<>();
    private DatabaseReference connectedRef;
    private DatabaseReference changesRef;
    private ValueEventListener connectedListener;
    private ValueEventListener changesListener;
    private String uid;

    public PresenceManager(Context c, Prefs prefs) {
        this.context = c.getApplicationContext();
        this.prefs = prefs;
    }

    public LiveData<Boolean> observeConnected() {
        return connected;
    }

    public LiveData<Long> observeOtherDeviceChange() {
        return otherDeviceChange;
    }

    public synchronized void start(String uid) {
        if (uid.equals(this.uid) && connectedListener != null) return;
        stop();
        this.uid = uid;
        try {
            FirebaseConfig.database().goOnline();
            DatabaseReference device = FirebaseConfig.database().getReference("status").child(uid)
                    .child("devices").child(prefs.installId());
            Map<String, Object> offline = new HashMap<>();
            offline.put("online", false);
            offline.put("lastSeen", ServerValue.TIMESTAMP);
            offline.put("model", Build.MODEL);
            connectedRef = FirebaseConfig.database().getReference(".info/connected");
            connectedListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    boolean on = Boolean.TRUE.equals(s.getValue(Boolean.class));
                    connected.postValue(on);
                    if (on) {
                        device.onDisconnect().setValue(offline);
                        Map<String, Object> online = new HashMap<>(offline);
                        online.put("online", true);
                        device.setValue(online);
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    connected.postValue(false);
                }
            };
            connectedRef.addValueEventListener(connectedListener);

            changesRef = FirebaseConfig.database().getReference("status").child(uid).child("changes");
            changesListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot s) {
                    String by = s.child("by").getValue(String.class);
                    Long at = s.child("at").getValue(Long.class);
                    if (by != null && !by.equals(prefs.installId())) {
                        otherDeviceChange.postValue(at);
                        SyncScheduler.requestSync(context);
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError e) {
                    Log.w("NutriFit AI", "RTDB changes listener cancelled: " + e.getCode());
                }
            };
            changesRef.addValueEventListener(changesListener);
        } catch (Exception e) {
            Log.w("NutriFit AI", "Realtime Database unavailable: " + e.getClass().getSimpleName());
            connected.postValue(false);
        }
    }

    /** Called by the sync worker after a successful upload. */
    public void announceChange(String uid) {
        try {
            Map<String, Object> v = new HashMap<>();
            v.put("by", prefs.installId());
            v.put("at", ServerValue.TIMESTAMP);
            FirebaseConfig.database().getReference("status").child(uid).child("changes").setValue(v);
        } catch (Exception ignored) {
            // Presence is best-effort; Firestore sync already succeeded.
        }
    }

    public synchronized void stop() {
        try {
            if (connectedRef != null && connectedListener != null) connectedRef.removeEventListener(connectedListener);
            if (changesRef != null && changesListener != null) changesRef.removeEventListener(changesListener);
            if (uid != null) {
                DatabaseReference device = FirebaseConfig.database().getReference("status").child(uid)
                        .child("devices").child(prefs.installId());
                Map<String, Object> offline = new HashMap<>();
                offline.put("online", false);
                offline.put("lastSeen", ServerValue.TIMESTAMP);
                offline.put("model", Build.MODEL);
                device.setValue(offline);
            }
        } catch (Exception ignored) {
            // ignore
        }
        connectedListener = null;
        changesListener = null;
        uid = null;
        connected.postValue(false);
    }

    /** Removes this account's presence tree (account deletion). */
    public void deleteStatus(String uid) {
        try {
            FirebaseConfig.database().getReference("status").child(uid).removeValue();
        } catch (Exception ignored) {
            // ignore
        }
    }
}
