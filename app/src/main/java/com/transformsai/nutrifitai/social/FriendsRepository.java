package com.transformsai.nutrifitai.social;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.social.SocialModels.FriendRequest;
import com.transformsai.nutrifitai.social.SocialModels.InboxItem;
import com.transformsai.nutrifitai.social.SocialModels.Person;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Friend requests, the friend list and people search. */
public final class FriendsRepository extends SocialBase {
    public FriendsRepository(AppGraph g) {
        super(g);
    }

    public LiveData<List<Person>> friends() {
        return new FirestoreLive<>(friendsOf(me()).orderBy("name"), Person.class);
    }

    /** Current friend ids (for post audiences and groups). Blocking. */
    public List<String> friendIdsBlocking() throws Exception {
        List<String> out = new ArrayList<>();
        for (DocumentSnapshot d : await(friendsOf(me()).limit(199).get()).getDocuments()) out.add(d.getId());
        return out;
    }

    public LiveData<List<FriendRequest>> incoming() {
        return new FirestoreLive<>(fs.collection("friendRequests").whereEqualTo("to", me())
                .whereEqualTo("status", FriendRequest.PENDING), FriendRequest.class);
    }

    public LiveData<List<FriendRequest>> outgoing() {
        return new FirestoreLive<>(fs.collection("friendRequests").whereEqualTo("from", me()), FriendRequest.class);
    }

    /** By exact @username or by name prefix. */
    public void search(String q, Result.Callback<List<Person>> cb) {
        String query = ProfileCards.normalize(q);
        if (query.length() < 2) {
            cb.onResult(Result.ok(Collections.emptyList()));
            return;
        }
        g.runIo(() -> {
            try {
                Map<String, Person> found = new LinkedHashMap<>();
                for (DocumentSnapshot d : await(fs.collection("publicProfiles").whereEqualTo("handle", query).limit(5).get()).getDocuments()) add(found, d);
                for (DocumentSnapshot d : await(fs.collection("publicProfiles").orderBy("nameLower").startAt(query)
                        .endAt(query + "").limit(20).get()).getDocuments()) add(found, d);
                found.remove(me());
                g.postResult(cb, Result.ok(new ArrayList<>(found.values())));
            } catch (Exception e) {
                g.postResult(cb, Result.fail("Search needs a connection."));
            }
        });
    }

    private static void add(Map<String, Person> into, DocumentSnapshot d) {
        Person p = d.toObject(Person.class);
        if (p != null && p.uid != null) into.put(p.uid, p);
    }

    public void sendRequest(Person to, Result.Callback<Void> cb) {
        g.runIo(() -> {
            try {
                FriendRequest r = new FriendRequest();
                r.from = me();
                r.to = to.uid;
                r.fromName = g.cards.myName();
                r.fromHandle = g.cards.myHandle();
                r.fromPhoto = g.cards.myPhoto();
                r.toName = to.name == null ? "" : to.name;
                r.status = FriendRequest.PENDING;
                r.createdAt = System.currentTimeMillis();
                await(fs.collection("friendRequests").document(r.id()).set(r));
                g.inbox.send(to.uid, InboxItem.FRIEND_REQUEST, r.fromName + " sent you a friend request",
                        r.fromHandle == null ? "Tap to answer" : "@" + r.fromHandle, r.from);
                g.postResult(cb, Result.ok(null));
            } catch (Exception e) {
                g.postResult(cb, Result.fail("Couldn't send the request."));
            }
        });
    }

    /** The receiver accepts and adds the sender in one batch (the rules check both together). */
    public void accept(FriendRequest r) {
        WriteBatch b = fs.batch();
        b.update(fs.collection("friendRequests").document(r.id()), "status", FriendRequest.ACCEPTED);
        b.set(friendsOf(me()).document(r.from), friendDoc(r.from, r.fromName, r.fromHandle, r.fromPhoto));
        b.commit().addOnSuccessListener(x -> g.runIo(() -> g.inbox.send(r.from, InboxItem.FRIEND_ACCEPTED,
                g.cards.myName() + " accepted your friend request", "Say hi or challenge them", me())));
    }

    /** Decline (receiver) or withdraw (sender). */
    public void remove(FriendRequest r) {
        fs.collection("friendRequests").document(r.id()).delete();
    }

    /** Sender side of an accepted request: add the friend, then tidy the request away. */
    public void completeAccepted(FriendRequest r) {
        g.runIo(() -> {
            Person p = g.cards.personBlocking(r.to);
            friendsOf(me()).document(r.to).set(friendDoc(r.to, r.toName, p == null ? null : p.handle, p == null ? null : p.photo))
                    .addOnSuccessListener(x -> remove(r));
        });
    }

    private static Map<String, Object> friendDoc(String uid, @Nullable String name, @Nullable String handle, @Nullable String photo) {
        Map<String, Object> m = new HashMap<>();
        m.put("uid", uid);
        m.put("name", name == null || name.isEmpty() ? "Friend" : name);
        if (handle != null) m.put("handle", handle);
        if (photo != null) m.put("photo", photo);
        m.put("since", System.currentTimeMillis());
        return m;
    }

    /** Ends the friendship on both sides (the rules let either friend delete both docs). */
    public void unfriend(String uid) {
        friendsOf(me()).document(uid).delete();
        friendsOf(uid).document(me()).delete();
    }

    /** Account deletion: both sides of every friendship and all open requests. Blocking. */
    void deleteBlocking(String uid) throws Exception {
        for (DocumentSnapshot d : await(friendsOf(uid).get()).getDocuments()) {
            await(friendsOf(d.getId()).document(uid).delete());
            await(d.getReference().delete());
        }
        for (String field : new String[]{"from", "to"}) {
            for (DocumentSnapshot d : await(fs.collection("friendRequests").whereEqualTo(field, uid).get()).getDocuments()) {
                await(d.getReference().delete());
            }
        }
    }
}
