package com.transformsai.nutrifitai.util;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

/**
 * Opens a bundled data asset. The Android build (aapt2) decompresses "*.gz" assets and drops the
 * extension, so "x.json.gz" in the source tree is "x.json" in the APK. Gzip data is detected by its
 * magic bytes, so the same code works in the APK and in JVM tests that read the source files.
 */
public final class AssetFiles {
    private AssetFiles() { }

    public static InputStream open(Context c, String name) throws IOException {
        try {
            return maybeGunzip(c.getAssets().open(name));
        } catch (IOException e) {
            if (!name.endsWith(".gz")) throw e;
            return maybeGunzip(c.getAssets().open(name.substring(0, name.length() - 3)));
        }
    }

    public static InputStream maybeGunzip(InputStream raw) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(2);
        int a = in.read(), b = in.read();
        in.reset();
        return a == 0x1f && b == 0x8b ? new GZIPInputStream(in) : in;
    }
}
