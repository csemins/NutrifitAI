package com.transformsai.nutrifitai.social;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.data.Result;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.data.remote.BucketClient;
import com.transformsai.nutrifitai.social.SocialModels.Person;
import com.transformsai.nutrifitai.util.DateUtil;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Usernames and the public card (publicProfiles/{uid}: name, @username, photo). A username is unique (claimed in
 * usernames/{name} in the same batch) and can be changed once every 30 days, as enforced by firestore.rules.
 */
public final class ProfileCards extends SocialBase {
    public static final Pattern USERNAME = Pattern.compile("^[a-z0-9_]{3,20}$");
    public static final long CHANGE_EVERY_MS = 30L * 24 * 3600 * 1000;

    /** Why a username cannot be used. */
    public enum Check { OK, INVALID, TAKEN, OFFLINE }

    private final Map<String, Person> people = new ConcurrentHashMap<>();

    public ProfileCards(AppGraph g) {
        super(g);
    }

    public static String normalize(@Nullable String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replaceFirst("^@", "");
    }

    /** A suggestion from the name (letters and digits only), for the signup field. */
    public static String suggestion(@Nullable String name) {
        String base = normalize(name).replaceAll("[^a-z0-9]", "");
        if (base.length() > 16) base = base.substring(0, 16);
        return base.length() < 3 ? "" : base;
    }

    /** Days until the username may change again (0 = now). */
    public static long daysUntilChange(@Nullable ProfileEntity p) {
        if (p == null || p.usernameChangedAt == 0) return 0;
        long left = p.usernameChangedAt + CHANGE_EVERY_MS - System.currentTimeMillis();
        return left <= 0 ? 0 : (left + 86_399_999L) / 86_400_000L;
    }

    public String myName() {
        ProfileEntity p = g.db.profiles().get(me());
        String n = p == null || p.name == null || p.name.trim().isEmpty() ? null : p.name.trim();
        if (n == null) {
            String email = g.session.email();
            n = email == null ? "NutriFit AI friend" : email.substring(0, Math.max(1, email.indexOf('@') > 0 ? email.indexOf('@') : email.length()));
        }
        return n.length() > 60 ? n.substring(0, 60) : n;
    }

    @Nullable
    public String myHandle() {
        ProfileEntity p = g.db.profiles().get(me());
        return p == null ? null : p.username;
    }

    @Nullable
    public String myPhoto() {
        ProfileEntity p = g.db.profiles().get(me());
        return p == null ? null : p.avatarPublicPath;
    }

    /** Is this name free (or already mine)? Blocking. */
    public Check checkBlocking(String raw) {
        String name = normalize(raw);
        if (!USERNAME.matcher(name).matches()) return Check.INVALID;
        try {
            DocumentSnapshot d = await(fs.collection("usernames").document(name).get());
            return !d.exists() || me().equals(d.getString("uid")) ? Check.OK : Check.TAKEN;
        } catch (Exception e) {
            return Check.OFFLINE;
        }
    }

    public void check(String raw, Result.Callback<Check> cb) {
        g.runIo(() -> g.postResult(cb, Result.ok(checkBlocking(raw))));
    }

    /**
     * Claims a username and publishes the card in one batch, then stores it on the local profile. Fails with a
     * readable message when it is taken, invalid, too soon to change, or offline.
     */
    public void claim(String raw, Result.Callback<String> cb) {
        g.runIo(() -> {
            String name = normalize(raw);
            ProfileEntity p = g.db.profiles().get(me());
            if (p != null && name.equals(p.username)) {
                g.postResult(cb, Result.ok(name));
                return;
            }
            if (p != null && p.username != null && daysUntilChange(p) > 0) {
                g.postResult(cb, Result.fail("You can change your username again in " + daysUntilChange(p) + " days."));
                return;
            }
            Check c = checkBlocking(name);
            if (c != Check.OK) {
                g.postResult(cb, Result.fail(c == Check.TAKEN ? "That username is taken." : c == Check.INVALID
                        ? "Use 3-20 lowercase letters, numbers or _." : "Check your connection and try again."));
                return;
            }
            try {
                long now = System.currentTimeMillis();
                WriteBatch b = fs.batch();
                b.set(fs.collection("usernames").document(name), Map.of("uid", me()));
                b.set(card(), cardFields(p, name, now));
                if (p != null && p.username != null && !p.username.equals(name)) {
                    b.delete(fs.collection("usernames").document(p.username));
                }
                await(b.commit());
                if (p != null) {
                    p.username = name;
                    p.usernameChangedAt = now;
                    p.updatedAt = DateUtil.nowMillis();
                    g.profile.saveBlocking(p);
                }
                g.postResult(cb, Result.ok(name));
            } catch (Exception e) {
                g.postResult(cb, Result.fail("Couldn't save your username. Try again."));
            }
        });
    }

    private DocumentReference card() {
        return fs.collection("publicProfiles").document(me());
    }

    private Map<String, Object> cardFields(@Nullable ProfileEntity p, String handle, long handleChangedAt) {
        String name = myName();
        Map<String, Object> m = new HashMap<>();
        m.put("uid", me());
        m.put("name", name);
        m.put("nameLower", name.toLowerCase(Locale.ROOT));
        m.put("handle", handle);
        m.put("photo", p == null ? null : p.avatarPublicPath);
        m.put("handleChangedAt", handleChangedAt);
        m.put("updatedAt", System.currentTimeMillis());
        return m;
    }

    /** Re-publishes name and photo (the username stays). Blocking; no-op until a username exists. */
    public void refreshCardBlocking() throws Exception {
        ProfileEntity p = g.db.profiles().get(me());
        if (p == null || p.username == null) return;
        await(card().set(cardFields(p, p.username, p.usernameChangedAt)));
    }

    /** Uploads a shareable copy of the avatar, then updates the card. Blocking. */
    public void publishPhotoBlocking(File jpeg) throws Exception {
        ProfileEntity p = g.db.profiles().get(me());
        if (p == null) return;
        String path = BucketClient.postPhotoPath(me(), randomId());
        new BucketClient(g.prefs).upload(path, jpeg);
        String old = p.avatarPublicPath;
        p.avatarPublicPath = path;
        p.updatedAt = DateUtil.nowMillis();
        g.profile.saveBlocking(p);
        refreshCardBlocking();
        if (old != null) {
            try {
                new BucketClient(g.prefs).delete(old);
            } catch (Exception ignored) {
                // the old copy is unreachable once the card points elsewhere
            }
        }
    }

    /** Publishes my photo again when I have one on the phone but friends can't see it yet. Blocking. */
    public void republishPhotoIfMissingBlocking() throws Exception {
        ProfileEntity p = g.db.profiles().get(me());
        if (p == null || p.username == null || p.avatarPublicPath != null || p.avatarLocalPath == null) return;
        File f = new File(p.avatarLocalPath);
        if (f.isFile()) publishPhotoBlocking(f);
    }

    /** Someone's public card, cached for the session. Blocking. */
    @Nullable
    public Person personBlocking(String uid) {
        Person cached = people.get(uid);
        if (cached != null) return cached;
        try {
            Person p = await(fs.collection("publicProfiles").document(uid).get()).toObject(Person.class);
            if (p != null) people.put(uid, p);
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    /** A shared photo (avatar or post image) downloaded to the cache, or null. Blocking. */
    @Nullable
    public File photoBlocking(@Nullable String path) {
        if (path == null) return null;
        File dir = new File(g.context.getCacheDir(), "feed");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, path.replace('/', '_'));
        if (f.length() > 0) return f;
        try {
            return new BucketClient(g.prefs).download(path, f) ? f : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Account deletion: frees the username and removes the card. Blocking. */
    void deleteBlocking(String uid) throws Exception {
        ProfileEntity p = g.db.profiles().get(uid);
        await(fs.collection("publicProfiles").document(uid).delete());
        if (p != null && p.username != null) await(fs.collection("usernames").document(p.username).delete());
        if (p != null && p.avatarPublicPath != null) {
            try {
                new BucketClient(g.prefs).delete(p.avatarPublicPath);
            } catch (Exception ignored) {
                // the bucket folder is removed with the account anyway
            }
        }
    }
}
