package com.transformsai.nutrifitai.social;

import android.app.PendingIntent;
import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.reminders.Notifications;
import com.transformsai.nutrifitai.social.SocialModels.InboxItem;
import com.transformsai.nutrifitai.ui.social.NotificationsActivity;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.WriteBatch;

import java.util.List;

/**
 * Notifications between users: each one is a document in the receiver's inbox (users/{uid}/inbox), written by the
 * sender as themselves (firestore.rules). While the app runs, a listener keeps the unread count live and shows new
 * items as phone notifications; {@link InboxWorker} checks again about every 15 minutes in the background.
 */
public final class InboxRepository extends SocialBase {
    private final MutableLiveData<Integer> unread = new MutableLiveData<>(0);
    @Nullable private ListenerRegistration listener;
    @Nullable private String listeningFor;

    public InboxRepository(AppGraph g) {
        super(g);
    }

    private CollectionReference inbox(String uid) {
        return fs.collection("users").document(uid).collection("inbox");
    }

    /** Leaves a notification for {@code to}. Fire and forget: queued by Firestore when offline. */
    public void send(String to, String type, String title, String body, @Nullable String ref) {
        if (to == null || to.equals(g.session.uid())) return;
        g.runIo(() -> {
            InboxItem n = new InboxItem();
            n.id = randomId();
            n.from = me();
            n.fromName = g.cards.myName();
            n.type = type;
            n.title = clip(title, 80);
            n.body = clip(body, 200);
            n.ref = ref;
            n.createdAt = System.currentTimeMillis();
            inbox(to).document(n.id).set(n);
        });
    }

    private static String clip(String s, int max) {
        return s == null ? "" : s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    public LiveData<List<InboxItem>> items() {
        return new FirestoreLive<>(inbox(me()).orderBy("createdAt", Query.Direction.DESCENDING).limit(100), InboxItem.class);
    }

    /** Unread count for the badge on the profile picture. */
    public LiveData<Integer> unread() {
        return unread;
    }

    public void markRead(InboxItem n) {
        if (!n.read) inbox(me()).document(n.id).update("read", true);
    }

    public void markAllRead(List<InboxItem> items) {
        WriteBatch b = fs.batch();
        int n = 0;
        for (InboxItem i : items) {
            if (i.read || n >= 450) continue;
            b.update(inbox(me()).document(i.id), "read", true);
            n++;
        }
        if (n > 0) b.commit();
    }

    public void delete(InboxItem n) {
        inbox(me()).document(n.id).delete();
    }

    /** Starts the live unread listener for the signed-in account (idempotent). */
    public synchronized void start() {
        String uid = g.session.uid();
        if (uid == null || uid.equals(listeningFor)) return;
        stop();
        listeningFor = uid;
        listener = inbox(uid).whereEqualTo("read", false).addSnapshotListener((snap, err) -> {
            if (snap == null || err != null) return;
            unread.setValue(snap.size());
            long seen = g.prefs.inboxNotifiedAt(uid);
            long newest = seen;
            for (DocumentChange ch : snap.getDocumentChanges()) {
                if (ch.getType() != DocumentChange.Type.ADDED) continue;
                InboxItem n = ch.getDocument().toObject(InboxItem.class);
                if (n.createdAt > seen) {
                    notify(n);
                    newest = Math.max(newest, n.createdAt);
                }
            }
            if (newest > seen) g.prefs.setInboxNotifiedAt(uid, newest);
        });
    }

    public synchronized void stop() {
        if (listener != null) listener.remove();
        listener = null;
        listeningFor = null;
        unread.postValue(0);
    }

    /** Background check (InboxWorker): shows unread items newer than the last one shown. Blocking. */
    void checkBlocking() throws Exception {
        String uid = g.session.uid();
        if (uid == null) return;
        long seen = g.prefs.inboxNotifiedAt(uid);
        long newest = seen;
        for (DocumentSnapshot d : await(inbox(uid).whereEqualTo("read", false).get()).getDocuments()) {
            InboxItem n = d.toObject(InboxItem.class);
            if (n != null && n.createdAt > seen) {
                notify(n);
                newest = Math.max(newest, n.createdAt);
            }
        }
        if (newest > seen) g.prefs.setInboxNotifiedAt(uid, newest);
    }

    private void notify(InboxItem n) {
        if (!Notifications.canPost(g.context) || n.createdAt < System.currentTimeMillis() - 3L * 24 * 3600 * 1000) return;
        Intent open = NotificationsActivity.open(g.context, n).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(g.context, n.id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(g.context, Notifications.CH_SOCIAL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(n.title)
                .setContentText(n.body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(n.body))
                .setAutoCancel(true)
                .setCategory(InboxItem.CHALLENGE.equals(n.type) || InboxItem.CALL.equals(n.type)
                        ? NotificationCompat.CATEGORY_EVENT : NotificationCompat.CATEGORY_SOCIAL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(g.context).notify(n.id.hashCode(), b.build());
        } catch (SecurityException ignored) {
            // permission revoked between the check and the post
        }
    }

    /** Account deletion: clears my inbox. Blocking. */
    void deleteBlocking(String uid) throws Exception {
        for (DocumentSnapshot d : await(inbox(uid).get()).getDocuments()) await(d.getReference().delete());
    }
}
