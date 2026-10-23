package com.transformsai.nutrifitai.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Normalises a captured or picked photo: applies EXIF rotation, downsizes to at most 1280 px on the
 * long side, re-encodes JPEG q=85 and drops all metadata (including GPS). Output stays in the app's
 * private storage; only this processed copy is ever uploaded or analysed.
 */
public final class ImageUtils {
    public static final int MAX_SIDE = 1280;
    public static final int QUALITY = 85;

    private ImageUtils() {
    }

    public static File mealImageDir(Context c) {
        File d = new File(c.getFilesDir(), "meal_images");
        if (!d.exists() && !d.mkdirs()) throw new IllegalStateException("cannot create image dir");
        return d;
    }

    public static File newMealImageFile(Context c) {
        return new File(mealImageDir(c), Ids.newId() + ".jpg");
    }

    public static File process(Context c, Uri source, File out) throws IOException {
        int orientation;
        try (InputStream in = c.getContentResolver().openInputStream(source)) {
            if (in == null) throw new IOException("cannot open image");
            orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(source)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("not an image");
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_SIDE * 2);
        Bitmap bmp;
        try (InputStream in = c.getContentResolver().openInputStream(source)) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) throw new IOException("decode failed");
        Bitmap oriented = rotate(bmp, orientation);
        Bitmap scaled = scaleDown(oriented, MAX_SIDE);
        try (FileOutputStream fo = new FileOutputStream(out)) {
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, fo);
        }
        if (scaled != oriented) scaled.recycle();
        if (oriented != bmp) oriented.recycle();
        bmp.recycle();
        return out;
    }

    public static int sampleSize(int w, int h, int target) {
        int s = 1;
        while (Math.max(w, h) / (s * 2) >= target) s *= 2;
        return s;
    }

    private static Bitmap rotate(Bitmap b, int orientation) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: m.postRotate(90); m.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: m.postRotate(270); m.postScale(-1, 1); break;
            default: return b;
        }
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    private static Bitmap scaleDown(Bitmap b, int max) {
        int w = b.getWidth();
        int h = b.getHeight();
        if (Math.max(w, h) <= max) return b;
        float f = (float) max / Math.max(w, h);
        return Bitmap.createScaledBitmap(b, Math.round(w * f), Math.round(h * f), true);
    }
}
