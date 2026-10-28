package com.transformsai.nutrifitai.data.remote;

import com.transformsai.nutrifitai.data.Prefs;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Private image storage on the NutriFit AI server (replaces Firebase Storage).
 * Paths: users/{uid}/meals/{entryId}.jpg and users/{uid}/avatar.jpg. Every call carries the
 * user's Firebase ID token; the server only lets a user touch their own folder.
 */
public final class BucketClient {
    private static final MediaType JPEG = MediaType.get("image/jpeg");
    private static final MediaType MP4 = MediaType.get("video/mp4");
    private final Prefs prefs;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();

    public BucketClient(Prefs prefs) {
        this.prefs = prefs;
    }

    public static String mealPath(String uid, String entryId) {
        return "users/" + uid + "/meals/" + entryId + ".jpg";
    }

    public static String avatarPath(String uid) {
        return "users/" + uid + "/avatar.jpg";
    }

    /** Feed photo: readable by any signed-in user who has the (random, unguessable) id from a post. */
    public static String postPhotoPath(String uid, String randomId) {
        return "users/" + uid + "/posts/" + randomId + ".jpg";
    }

    /** Feed video clip (MP4, at most 25 MB on the server). */
    public static String clipPath(String uid, String randomId) {
        return "users/" + uid + "/clips/" + randomId + ".mp4";
    }

    private String url(String path) {
        String base = prefs.aiBaseUrl();
        if (!base.endsWith("/")) base += "/";
        return base + "v1/files/" + path;
    }

    private static String token() throws IOException {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) throw new IOException("not signed in");
        try {
            String t = Tasks.await(u.getIdToken(false), 20, TimeUnit.SECONDS).getToken();
            if (t == null) throw new IOException("no token");
            return t;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("token unavailable", e);
        }
    }

    public void upload(String path, File file) throws IOException {
        Request r = new Request.Builder().url(url(path)).header("Authorization", "Bearer " + token())
                .put(RequestBody.create(file, path.endsWith(".mp4") ? MP4 : JPEG)).build();
        try (Response resp = http.newCall(r).execute()) {
            if (!resp.isSuccessful()) throw new IOException("upload failed (" + resp.code() + ")");
        }
    }

    /** @return false when the object does not exist. */
    public boolean download(String path, File out) throws IOException {
        Request r = new Request.Builder().url(url(path)).header("Authorization", "Bearer " + token()).get().build();
        try (Response resp = http.newCall(r).execute()) {
            if (resp.code() == 404) return false;
            if (!resp.isSuccessful() || resp.body() == null) throw new IOException("download failed (" + resp.code() + ")");
            File tmp = new File(out.getPath() + ".part");
            try (InputStream in = resp.body().byteStream(); FileOutputStream fo = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            if (!tmp.renameTo(out)) throw new IOException("rename failed");
            return true;
        }
    }

    public void delete(String path) throws IOException {
        Request r = new Request.Builder().url(url(path)).header("Authorization", "Bearer " + token()).delete().build();
        try (Response resp = http.newCall(r).execute()) {
            if (!resp.isSuccessful() && resp.code() != 404) throw new IOException("delete failed (" + resp.code() + ")");
        }
    }

    /** Removes every file of the account (account deletion). */
    public void deleteUser(String uid) throws IOException {
        delete("users/" + uid + "/");
    }
}
