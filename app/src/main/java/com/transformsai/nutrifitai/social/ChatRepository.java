package com.transformsai.nutrifitai.social;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.social.SocialModels.Chat;
import com.transformsai.nutrifitai.social.SocialModels.InboxItem;
import com.transformsai.nutrifitai.social.SocialModels.Message;
import com.transformsai.nutrifitai.social.SocialModels.Person;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Direct messages between two friends and friend groups (up to 20), with their messages. */
public final class ChatRepository extends SocialBase {
    public static final int MAX_GROUP = 20;

    public ChatRepository(AppGraph g) {
        super(g);
    }

    /** The fixed id of the direct-message thread between two people. */
    public static String dmId(String a, String b) {
        return a.compareTo(b) < 0 ? "dm_" + a + "_" + b : "dm_" + b + "_" + a;
    }

    private DocumentReference chat(String id) {
        return fs.collection("chats").document(id);
    }

    private CollectionReference messageCol(String chatId) {
        return chat(chatId).collection("messages");
    }

    public LiveData<List<Chat>> chats() {
        return new FirestoreLive<>(fs.collection("chats").whereArrayContains("members", me())
                .orderBy("lastAt", Query.Direction.DESCENDING).limit(100), Chat.class);
    }

    public LiveData<Chat> chatLive(String id) {
        return new FirestoreLive.Doc<>(chat(id), Chat.class);
    }

    public LiveData<List<Message>> messages(String chatId) {
        return new FirestoreLive<>(messageCol(chatId).orderBy("createdAt", Query.Direction.DESCENDING).limit(200), Message.class);
    }

    /** Opens (creating once) the direct-message thread with a friend. */
    public void openDm(Person friend, Result.Callback<String> cb) {
        g.runIo(() -> {
            String id = dmId(me(), friend.uid);
            try {
                if (!await(chat(id).get()).exists()) {
                    Chat c = new Chat();
                    c.id = id;
                    c.type = Chat.DM;
                    c.createdBy = me();
                    c.members = new ArrayList<>(List.of(me(), friend.uid));
                    Collections.sort(c.members);
                    c.names.put(me(), g.cards.myName());
                    c.names.put(friend.uid, friend.name == null ? "Friend" : friend.name);
                    c.lastText = "";
                    c.lastFrom = "";
                    c.createdAt = System.currentTimeMillis();
                    c.lastAt = c.createdAt;
                    await(chat(id).set(c));
                }
                g.postResult(cb, Result.ok(id));
            } catch (Exception e) {
                g.postResult(cb, Result.fail("Couldn't open the chat. Check your connection."));
            }
        });
    }

    public void createGroup(String title, List<Person> friends, Result.Callback<String> cb) {
        g.runIo(() -> {
            try {
                Chat c = new Chat();
                c.id = "g_" + randomId();
                c.type = Chat.GROUP;
                c.title = title.length() > 40 ? title.substring(0, 40) : title;
                c.createdBy = me();
                c.members.add(me());
                c.names.put(me(), g.cards.myName());
                for (Person p : friends) {
                    if (c.members.size() >= MAX_GROUP) break;
                    c.members.add(p.uid);
                    c.names.put(p.uid, p.name == null ? "Friend" : p.name);
                }
                c.lastText = "";
                c.lastFrom = "";
                c.createdAt = System.currentTimeMillis();
                c.lastAt = c.createdAt;
                await(chat(c.id).set(c));
                for (String uid : c.members) {
                    g.inbox.send(uid, InboxItem.GROUP, g.cards.myName() + " added you to " + c.title, "Say hi to the group", c.id);
                }
                g.postResult(cb, Result.ok(c.id));
            } catch (Exception e) {
                g.postResult(cb, Result.fail("Couldn't create the group."));
            }
        });
    }

    /** Host only: rename, add or remove members (a member can leave by removing themselves via {@link #leave}). */
    public void updateGroup(Chat c, String title, List<String> members, Map<String, String> names) {
        Map<String, Object> m = new HashMap<>();
        m.put("title", title);
        m.put("members", members);
        m.put("names", names);
        chat(c.id).update(m);
    }

    public void send(Chat c, String text, String kind, @Nullable String ref) {
        g.runIo(() -> {
            Message msg = new Message();
            msg.id = randomId();
            msg.from = me();
            msg.fromName = g.cards.myName();
            msg.text = text.length() > 1000 ? text.substring(0, 1000) : text;
            msg.kind = kind;
            msg.ref = ref;
            msg.createdAt = System.currentTimeMillis();
            WriteBatch b = fs.batch();
            b.set(messageCol(c.id).document(msg.id), msg);
            Map<String, Object> last = new HashMap<>();
            last.put("lastText", msg.text.length() > 120 ? msg.text.substring(0, 120) : msg.text);
            last.put("lastFrom", msg.from);
            last.put("lastAt", msg.createdAt);
            b.update(chat(c.id), last);
            b.commit();
            String title = Chat.GROUP.equals(c.type) ? msg.fromName + " in " + c.displayName(me()) : msg.fromName;
            for (String uid : c.members) g.inbox.send(uid, InboxItem.MESSAGE, title, msg.text, c.id);
        });
    }

    /** Leaves a group; the creator deletes it instead. */
    public void leave(Chat c) {
        if (me().equals(c.createdBy)) {
            chat(c.id).delete();
            return;
        }
        List<String> rest = new ArrayList<>(c.members);
        rest.remove(me());
        Map<String, String> names = new HashMap<>(c.names);
        names.remove(me());
        Map<String, Object> m = new HashMap<>();
        m.put("members", rest);
        m.put("names", names);
        chat(c.id).update(m);
    }

    /** Account deletion: groups and threads I created. Blocking. */
    void deleteBlocking(String uid) throws Exception {
        for (DocumentSnapshot d : await(fs.collection("chats").whereArrayContains("members", uid).get()).getDocuments()) {
            if (uid.equals(d.getString("createdBy"))) await(d.getReference().delete());
        }
    }
}
