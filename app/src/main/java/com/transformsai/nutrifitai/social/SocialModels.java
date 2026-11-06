package com.transformsai.nutrifitai.social;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Firestore documents of the social features (public no-arg constructors and fields for toObject). */
public final class SocialModels {
    private SocialModels() { }

    /** publicProfiles/{uid}: what friends and search can see. "photo" is a shared bucket path. */
    public static class Person {
        public String uid, name, handle;
        @Nullable public String photo;

        public Person() { }

        public Person(String uid, String name, String handle, @Nullable String photo) {
            this.uid = uid;
            this.name = name;
            this.handle = handle;
            this.photo = photo;
        }
    }

    /** friendRequests/{from}_{to}. */
    public static class FriendRequest {
        public static final String PENDING = "pending", ACCEPTED = "accepted";
        public String from, to, fromName, toName, fromHandle, status;
        @Nullable public String fromPhoto;
        public long createdAt;

        public FriendRequest() { }

        public String id() {
            return from + "_" + to;
        }
    }

    /** posts/{id}. "data" holds the numbers shown on the card; reactions maps uid to a reaction key. */
    public static class Post {
        public static final String STATS = "stats", MEAL = "meal", WORKOUT = "workout", CLIP = "clip",
                CHALLENGE = "challenge", ACHIEVEMENT = "achievement", TEXT = "text";
        public String id, author, authorName, type, text;
        @Nullable public String authorPhoto, media;
        public Map<String, Object> data = new HashMap<>();
        public List<String> audience = new ArrayList<>();
        public Map<String, String> reactions = new HashMap<>();
        public long commentCount, createdAt;

        public Post() { }

        public double num(String key) {
            Object v = data == null ? null : data.get(key);
            return v instanceof Number ? ((Number) v).doubleValue() : 0;
        }

        @Nullable
        public String str(String key) {
            Object v = data == null ? null : data.get(key);
            return v == null ? null : String.valueOf(v);
        }
    }

    /** posts/{postId}/comments/{id}. */
    public static class Comment {
        public String id, author, authorName, text;
        @Nullable public String authorPhoto;
        public long createdAt;

        public Comment() { }
    }

    /** challenges/{id}: a workout everyone does at the same time; live progress is in RTDB live/{id}. */
    public static class Challenge {
        public static final String OPEN = "open", LIVE = "live", FINISHED = "finished";
        public String id, host, hostName, title, stepsJson, status;
        @Nullable public String groupId;
        public List<String> participants = new ArrayList<>();
        public List<String> accepted = new ArrayList<>();
        public List<String> declined = new ArrayList<>();
        public Map<String, String> names = new HashMap<>();
        /** uid -> {score, reps, faults, finishMs} reported by each player when they finish. */
        public Map<String, Map<String, Object>> results = new HashMap<>();
        public long startAt, createdAt;

        public Challenge() { }

        public String nameOf(String uid) {
            String n = names == null ? null : names.get(uid);
            return n == null || n.isEmpty() ? "Friend" : n;
        }
    }

    /** RTDB live/{challengeId}/{uid}: one player's progress, streamed while they work out. */
    public static class LivePlayer {
        public String uid, name, exercise, pose, reaction;
        public int reps, faults, step, steps, score;
        public double holdSec;
        public boolean ready, done;
        public long finishMs, at, reactionAt;

        public LivePlayer() { }
    }

    /** users/{uid}/inbox/{id}: a notification another user left for this account. */
    public static class InboxItem {
        public static final String FRIEND_REQUEST = "friend_request", FRIEND_ACCEPTED = "friend_accepted",
                CHALLENGE = "challenge", COMMENT = "comment", REACTION = "reaction", MESSAGE = "message",
                GROUP = "group", CALL = "call";
        public String id, from, fromName, type, title, body;
        @Nullable public String ref;
        public boolean read;
        public long createdAt;

        public InboxItem() { }
    }

    /** chats/{id}: a direct message thread (dm_{a}_{b}) or a friend group. */
    public static class Chat {
        public static final String DM = "dm", GROUP = "group";
        public String id, type, createdBy;
        @Nullable public String title, lastText, lastFrom;
        public List<String> members = new ArrayList<>();
        public Map<String, String> names = new HashMap<>();
        public long createdAt, lastAt;

        public Chat() { }

        /** Title shown to {@code me}: the group's name, or the other person's name. */
        public String displayName(String me) {
            if (GROUP.equals(type)) return title == null ? "Group" : title;
            for (String m : members) if (!m.equals(me)) return names.containsKey(m) ? names.get(m) : "Friend";
            return "Chat";
        }

        @Nullable
        public String otherMember(String me) {
            for (String m : members) if (!m.equals(me)) return m;
            return null;
        }
    }

    /** chats/{chatId}/messages/{id}. kind: text, challenge (ref = challenge id) or call. */
    public static class Message {
        public static final String TEXT = "text", CHALLENGE = "challenge", CALL = "call";
        public String id, from, fromName, text, kind;
        @Nullable public String ref;
        public long createdAt;

        public Message() { }
    }
}
