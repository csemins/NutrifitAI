package com.transformsai.nutrifitai.server;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Media bucket (replacement for Firebase Storage). Disk layout mirrors the logical paths.
 * users/{uid}/meals/{entryId}.jpg and avatar.jpg are private to the owner. users/{uid}/posts/{id}.jpg and
 * clips/{id}.mp4 are feed media: only the owner writes them; any signed-in user who knows the random id
 * (shared only inside posts visible to friends) may read them.
 */
public final class Bucket {
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_CLIP_BYTES = 25 * 1024 * 1024;
    private static final Pattern PATH = Pattern.compile("^users/([A-Za-z0-9_-]{6,128})/(meals/[A-Za-z0-9_-]{1,64}\\.jpg|avatar\\.jpg"
            + "|posts/[A-Za-z0-9_-]{16,64}\\.jpg|clips/[A-Za-z0-9_-]{16,64}\\.mp4)$");
    private static final Pattern SHARED = Pattern.compile("^users/[A-Za-z0-9_-]{6,128}/(posts/[A-Za-z0-9_-]{16,64}\\.jpg"
            + "|clips/[A-Za-z0-9_-]{16,64}\\.mp4)$");
    private static final Pattern USER = Pattern.compile("^users/([A-Za-z0-9_-]{6,128})/?$");

    private final Path root;

    public Bucket(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** @return the owner uid of a valid object path. */
    public static String ownerOf(String path) throws ApiException {
        Matcher m = path == null ? null : PATH.matcher(path);
        if (m == null || !m.matches()) throw new ApiException(400, "bad_path", "Invalid object path.");
        return m.group(1);
    }

    /** Feed media readable by any signed-in user. */
    public static boolean isShared(String path) {
        return path != null && SHARED.matcher(path).matches();
    }

    public static boolean isClip(String path) {
        return path != null && path.endsWith(".mp4");
    }

    public static int maxBytes(String path) {
        return isClip(path) ? MAX_CLIP_BYTES : MAX_FILE_BYTES;
    }

    /** @return uid for a "users/{uid}/" prefix, or null when the path is not a prefix. */
    public static String userPrefix(String path) {
        Matcher m = path == null ? null : USER.matcher(path);
        return m != null && m.matches() ? m.group(1) : null;
    }

    /** Only real JPEG/PNG/WebP images are stored (magic bytes, not the declared type). */
    public static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0d && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a) return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    /** MP4 / MOV: an "ftyp" box at offset 4. */
    public static String sniffVideo(byte[] b) {
        return b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p' ? "video/mp4" : null;
    }

    Path abs(String rel) throws ApiException {
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root) || p.equals(root)) throw new ApiException(400, "bad_path", "Invalid object path.");
        return p;
    }

    public String put(String rel, byte[] data) throws ApiException, IOException {
        if (data.length == 0 || data.length > maxBytes(rel)) throw new ApiException(413, "too_large", "File is empty or too large.");
        String type = isClip(rel) ? sniffVideo(data) : sniff(data);
        if (type == null) throw new ApiException(415, "bad_type", isClip(rel) ? "Only MP4 video clips are accepted."
                : "Only JPEG, PNG or WebP images are accepted.");
        Path target = abs(rel);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return type; // re-uploads to the same path are idempotent
    }

    public byte[] get(String rel) throws ApiException, IOException {
        try {
            return Files.readAllBytes(abs(rel));
        } catch (NoSuchFileException e) {
            throw new ApiException(404, "not_found", "Not found.");
        }
    }

    public void remove(String rel) throws ApiException, IOException {
        Files.deleteIfExists(abs(rel));
    }

    public void removeUser(String uid) throws ApiException, IOException {
        Path dir = abs("users/" + uid);
        if (!Files.exists(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }
}
