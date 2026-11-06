package com.transformsai.nutrifitai.ui.social;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.MediaController;

import androidx.annotation.Nullable;

import com.transformsai.nutrifitai.databinding.ActivityClipPlayerBinding;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;

import java.io.File;

/** Plays a downloaded feed clip (a local cache file) full screen; playback position survives rotation. */
public class ClipPlayerActivity extends BaseActivity {
    private static final String EXTRA_PATH = "path";
    private ActivityClipPlayerBinding b;
    private int position;

    public static Intent intent(Context c, String path) {
        return new Intent(c, ClipPlayerActivity.class).putExtra(EXTRA_PATH, path);
    }

    @Override
    protected boolean alwaysDark() {
        return true;
    }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || !new File(path).isFile()) {
            finish();
            return;
        }
        b = ActivityClipPlayerBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, true);
        position = s == null ? 0 : s.getInt("pos", 0);
        MediaController mc = new MediaController(this);
        mc.setAnchorView(b.video);
        b.video.setMediaController(mc);
        b.video.setVideoURI(Uri.fromFile(new File(path)));
        b.video.setOnPreparedListener(mp -> {
            mp.setLooping(true);
            b.video.seekTo(position);
            b.video.start();
        });
        b.close.setOnClickListener(v -> finish());
    }

    @Override
    protected void onPause() {
        if (b != null) {
            position = b.video.getCurrentPosition();
            b.video.pause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (b != null && position > 0) {
            b.video.seekTo(position);
            b.video.start();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("pos", position);
    }
}
