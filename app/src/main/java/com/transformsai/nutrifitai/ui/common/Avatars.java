package com.transformsai.nutrifitai.ui.common;

import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.data.db.entity.ProfileEntity;
import com.transformsai.nutrifitai.ui.widget.FoodThumbView;
import com.bumptech.glide.Glide;

import java.io.File;

/** Puts a person's photo in an avatar view: a local file, a shared bucket photo, or their initial. */
public final class Avatars {
    private Avatars() { }

    /** My own avatar from the local profile. */
    public static void mine(FoodThumbView v, @Nullable ProfileEntity p) {
        v.setCircle();
        if (p != null && p.avatarLocalPath != null && new File(p.avatarLocalPath).exists()) {
            Glide.with(v).load(new File(p.avatarLocalPath)).centerCrop().into(v);
        } else {
            initial(v, p == null ? null : p.name);
        }
    }

    public static void initial(FoodThumbView v, @Nullable String name) {
        v.setCircle();
        Glide.with(v).clear(v);
        v.setEmoji(Ui.initial(name), "vegetables");
    }

    /**
     * Someone else's photo from its shared path (downloaded once with the user's token, then cached), falling back
     * to their initial. Safe in recycled rows: the view is tagged with the path it is waiting for.
     */
    public static void shared(FoodThumbView v, @Nullable String path, @Nullable String name) {
        initial(v, name);
        if (path == null) return;
        v.setTag(path);
        App.graph().runIo(() -> {
            File f = App.graph().cards.photoBlocking(path);
            if (f == null) return;
            App.graph().main.post(() -> {
                if (path.equals(v.getTag()) && v.isAttachedToWindow()) Glide.with(v).load(f).centerCrop().into((ImageView) v);
            });
        });
    }

    /**
     * A person's current photo: from their public card when the uid is known (so a photo added or changed after a
     * post, friendship or message still shows), else the stored shared path, else their initial.
     */
    public static void of(FoodThumbView v, @Nullable String uid, @Nullable String path, @Nullable String name) {
        if (uid != null) person(v, uid, name);
        else shared(v, path, name);
    }

    /** Anyone's photo by uid (my own local photo for me; others from their public card), else their initial. */
    public static void person(FoodThumbView v, String uid, @Nullable String name) {
        initial(v, name);
        v.setTag(uid);
        App.graph().runIo(() -> {
            File f;
            if (uid.equals(App.graph().session.uid())) {
                ProfileEntity me = App.graph().db.profiles().get(uid);
                f = me == null || me.avatarLocalPath == null ? null : new File(me.avatarLocalPath);
                if (f != null && !f.exists()) f = null;
            } else {
                com.transformsai.nutrifitai.social.SocialModels.Person p = App.graph().cards.personBlocking(uid);
                f = p == null ? null : App.graph().cards.photoBlocking(p.photo);
            }
            File photo = f;
            if (photo == null) return;
            App.graph().main.post(() -> {
                if (uid.equals(v.getTag()) && v.isAttachedToWindow()) Glide.with(v).load(photo).centerCrop().into((ImageView) v);
            });
        });
    }
}
