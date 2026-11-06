package com.transformsai.nutrifitai.social;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.data.remote.BucketClient;
import com.transformsai.nutrifitai.social.SocialModels.Comment;
import com.transformsai.nutrifitai.social.SocialModels.InboxItem;
import com.transformsai.nutrifitai.social.SocialModels.Post;
import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.WriteBatch;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The feed: posts visible to the author and the friends they had when posting, with reactions and comments.
 * Media (a JPEG or an MP4 clip) is uploaded first under an unguessable bucket path.
 */
public final class FeedRepository extends SocialBase {
    /** Reaction keys, in picker order (firestore.rules accepts exactly these). */
    public static final String[] REACTIONS = {"like", "love", "haha", "wow", "strong", "fire"};

    public FeedRepository(AppGraph g) {
        super(g);
    }

    public static String emoji(@Nullable String reaction) {
        if (reaction == null) return "👍";
        switch (reaction) {
            case "love": return "❤️";
            case "haha": return "😂";
            case "wow": return "😮";
            case "strong": return "💪";
            case "fire": return "🔥";
            case "clap": return "👏";
            default: return "👍";
        }
    }

    private CollectionReference comments(String postId) {
        return fs.collection("posts").document(postId).collection("comments");
    }

    public LiveData<List<Post>> feed() {
        return new FirestoreLive<>(fs.collection("posts").whereArrayContains("audience", me())
                .orderBy("createdAt", Query.Direction.DESCENDING).limit(60), Post.class);
    }

    public void post(String type, String text, Map<String, Object> data, @Nullable File media, Result.Callback<Post> cb) {
        g.runIo(() -> {
            try {
                String uid = me();
                String mediaPath = null;
                if (media != null) {
                    mediaPath = media.getName().endsWith(".mp4") ? BucketClient.clipPath(uid, randomId())
                            : BucketClient.postPhotoPath(uid, randomId());
                    new BucketClient(g.prefs).upload(mediaPath, media);
                }
                List<String> audience = new ArrayList<>();
                audience.add(uid);
                audience.addAll(g.friends.friendIdsBlocking());
                Post p = new Post();
                p.id = fs.collection("posts").document().getId();
                p.author = uid;
                p.authorName = g.cards.myName();
                p.authorPhoto = g.cards.myPhoto();
                p.type = type;
                p.text = text.length() > 500 ? text.substring(0, 500) : text;
                p.data = data;
                p.media = mediaPath;
                p.audience = audience;
                p.createdAt = System.currentTimeMillis();
                await(fs.collection("posts").document(p.id).set(p));
                g.postResult(cb, Result.ok(p));
            } catch (Exception e) {
                g.postResult(cb, Result.fail("Couldn't share the post. Check your connection and try again."));
            }
        });
    }

    /** Sets my reaction, or clears it when {@code reaction} is null or the same as before. */
    public void react(Post p, @Nullable String reaction) {
        String mine = p.reactions == null ? null : p.reactions.get(me());
        boolean clear = reaction == null || reaction.equals(mine);
        fs.collection("posts").document(p.id).update("reactions." + me(), clear ? FieldValue.delete() : reaction);
        if (!clear && mine == null) {
            g.runIo(() -> g.inbox.send(p.author, InboxItem.REACTION, g.cards.myName() + " reacted " + emoji(reaction) + " to your post",
                    p.text == null || p.text.isEmpty() ? "Tap to see it" : p.text, p.id));
        }
    }

    /** One post, live (null when deleted or not visible to me). */
    public LiveData<Post> postLive(String id) {
        return new FirestoreLive.Doc<>(fs.collection("posts").document(id), Post.class);
    }

    public LiveData<List<Comment>> comments(Post p) {
        return new FirestoreLive<>(comments(p.id).orderBy("createdAt").limit(200), Comment.class);
    }

    public void comment(Post p, String text, Result.Callback<Void> cb) {
        g.runIo(() -> {
            try {
                Comment c = new Comment();
                c.id = randomId();
                c.author = me();
                c.authorName = g.cards.myName();
                c.authorPhoto = g.cards.myPhoto();
                c.text = text.length() > 500 ? text.substring(0, 500) : text;
                c.createdAt = System.currentTimeMillis();
                WriteBatch b = fs.batch();
                b.set(comments(p.id).document(c.id), c);
                b.update(fs.collection("posts").document(p.id), "commentCount", FieldValue.increment(1));
                await(b.commit());
                g.inbox.send(p.author, InboxItem.COMMENT, c.authorName + " commented on your post", c.text, p.id);
                g.postResult(cb, Result.ok(null));
            } catch (Exception e) {
                g.postResult(cb, Result.fail("Couldn't post the comment."));
            }
        });
    }

    public void deleteComment(Post p, Comment c) {
        WriteBatch b = fs.batch();
        b.delete(comments(p.id).document(c.id));
        b.update(fs.collection("posts").document(p.id), "commentCount", FieldValue.increment(-1));
        b.commit();
    }

    public void deletePost(Post p) {
        fs.collection("posts").document(p.id).delete();
        if (p.media != null) {
            String path = p.media;
            g.runIo(() -> {
                try {
                    new BucketClient(g.prefs).delete(path);
                } catch (Exception ignored) {
                    // the post is gone; the file is unreachable without its id
                }
            });
        }
    }

    /** Post media (photo or clip) downloaded to the cache. Blocking. */
    public File media(String path) throws Exception {
        File f = g.cards.photoBlocking(path);
        if (f == null) throw new java.io.FileNotFoundException(path);
        return f;
    }

    /** Account deletion: my posts and their media. Blocking. */
    void deleteBlocking(String uid) throws Exception {
        for (DocumentSnapshot d : await(fs.collection("posts").whereEqualTo("author", uid)
                .whereArrayContains("audience", uid).get()).getDocuments()) {
            Post p = d.toObject(Post.class);
            if (p != null) deletePost(p);
        }
    }
}
