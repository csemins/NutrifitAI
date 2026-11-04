package com.transformsai.nutrifitai.io;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Copies finished exports into the phone's shared Downloads folder through the MediaStore content
 * provider (ContentResolver insert + stream). Needs no storage permission on Android 10+; the entry stays
 * hidden (IS_PENDING) until the copy is complete.
 */
public final class DownloadsSaver {
    private DownloadsSaver() { }

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /** Blocking; call off the main thread. Returns how many files were saved. */
    @RequiresApi(Build.VERSION_CODES.Q)
    public static int save(Context c, List<String> paths) throws IOException {
        ContentResolver cr = c.getContentResolver();
        int saved = 0;
        for (String path : paths) {
            File f = new File(path);
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, f.getName());
            v.put(MediaStore.Downloads.MIME_TYPE, f.getName().endsWith(".xml") ? "text/xml" : "text/csv");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NutriFit AI");
            v.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("MediaStore refused " + f.getName());
            try (InputStream in = new FileInputStream(f); OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new IOException("no output stream");
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            } catch (IOException e) {
                cr.delete(uri, null, null);
                throw e;
            }
            v.clear();
            v.put(MediaStore.Downloads.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            saved++;
        }
        return saved;
    }
}
