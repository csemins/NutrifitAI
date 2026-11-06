package com.transformsai.nutrifitai.ui.social;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.databinding.ActivityCommentsBinding;
import com.transformsai.nutrifitai.databinding.ItemCommentBinding;
import com.transformsai.nutrifitai.social.SocialModels.Comment;
import com.transformsai.nutrifitai.social.SocialModels.Post;
import com.transformsai.nutrifitai.ui.common.Avatars;
import com.transformsai.nutrifitai.ui.common.BaseActivity;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.ShareCards;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A post with its comments: react, comment, and delete my own comments (or any, on my post). */
public class CommentsActivity extends BaseActivity implements SocialAdapter.PostActions {
    private static final String EXTRA_POST = "post_id";

    private ActivityCommentsBinding b;
    private String me;
    @Nullable private Post post;
    @Nullable private File media;
    private SocialAdapter postAdapter;
    private CommentAdapter comments;
    private boolean commentsBound, sending;

    public static Intent intent(Context c, String postId) {
        return new Intent(c, CommentsActivity.class).putExtra(EXTRA_POST, postId);
    }

    @Override
    protected void onCreate(@Nullable Bundle s) {
        super.onCreate(s);
        me = uidOrFinish();
        String id = getIntent().getStringExtra(EXTRA_POST);
        if (me == null) return;
        if (id == null) {
            finish();
            return;
        }
        b = ActivityCommentsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        InsetsUtil.padding(b.root, true, true);
        b.bar.title.setText(R.string.comments);
        b.bar.back.setOnClickListener(v -> finish());
        postAdapter = new SocialAdapter(this);
        comments = new CommentAdapter();
        b.list.setLayoutManager(new LinearLayoutManager(this));
        b.list.setAdapter(new ConcatAdapter(postAdapter, comments));
        b.list.setItemAnimator(null);
        b.send.setOnClickListener(v -> send());
        graph().profile.observe(me).observe(this, p -> Avatars.mine(b.me, p));

        graph().feed.postLive(id).observe(this, p -> {
            post = p;
            b.gone.setVisibility(p == null ? View.VISIBLE : View.GONE);
            b.inputBar.setVisibility(p == null ? View.GONE : View.VISIBLE);
            postAdapter.submitList(p == null ? Collections.emptyList() : Collections.singletonList(SocialAdapter.Row.post(p)));
            if (p != null && !commentsBound) {
                commentsBound = true;
                graph().feed.comments(p).observe(this, l -> comments.submitList(l == null ? new ArrayList<>() : l));
            }
        });
    }

    private void send() {
        String text = b.input.getText() == null ? "" : b.input.getText().toString().trim();
        if (text.isEmpty() || post == null || sending) return;
        sending = true;
        b.send.setEnabled(false);
        graph().feed.comment(post, text, r -> {
            sending = false;
            b.send.setEnabled(true);
            if (!r.ok()) {
                Ui.snack(b.getRoot(), r.error);
                return;
            }
            b.input.setText("");
            Ui.hideKeyboard(this);
            b.list.smoothScrollToPosition(Math.max(0, comments.getItemCount()));
        });
    }

    // ------------------------------------------------------------------ post actions (header card)
    @Override
    public void react(Post p, @Nullable String reaction) {
        graph().feed.react(p, reaction);
    }

    @Override
    public void openComments(Post p) {
        b.input.requestFocus();
    }

    @Override
    public void share(Post p) {
        ShareCards.sharePost(this, p, media);
    }

    @Override
    public void openMedia(Post p) {
        if (media != null && p.media != null && p.media.endsWith(".mp4")) {
            startActivity(ClipPlayerActivity.intent(this, media.getAbsolutePath()));
        }
    }

    @Override
    public void more(Post p, View anchor) {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.post_delete_q)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    graph().feed.deletePost(p);
                    finish();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    @Override
    public void loadMedia(Post p, ImageView into) {
        if (p.media == null) return;
        if (media != null) {
            Glide.with(into).load(media).centerCrop().into(into);
            return;
        }
        graph().runIo(() -> {
            try {
                File f = graph().feed.media(p.media);
                graph().main.post(() -> {
                    media = f;
                    if (!isDestroyed()) Glide.with(into).load(f).centerCrop().into(into);
                });
            } catch (Exception ignored) {
                // offline: placeholder stays
            }
        });
    }

    @Override
    public boolean mine(Post p) {
        return me.equals(p.author);
    }

    @Nullable
    @Override
    public String myReaction(Post p) {
        return p.reactions == null ? null : p.reactions.get(me);
    }

    // ------------------------------------------------------------------ comments
    private class CommentAdapter extends ListAdapter<Comment, Holder> {
        CommentAdapter() {
            super(new DiffUtil.ItemCallback<Comment>() {
                @Override
                public boolean areItemsTheSame(@NonNull Comment a, @NonNull Comment c) {
                    return a.id.equals(c.id);
                }

                @Override
                public boolean areContentsTheSame(@NonNull Comment a, @NonNull Comment c) {
                    return a.createdAt == c.createdAt;
                }
            });
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new Holder(ItemCommentBinding.inflate(LayoutInflater.from(p.getContext()), p, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            Comment c = getItem(pos);
            Avatars.of(h.v.avatar, c.author, c.authorPhoto, c.authorName);
            h.v.name.setText(me.equals(c.author) ? getString(R.string.you) : c.authorName);
            h.v.text.setText(c.text);
            h.v.time.setText(Ui.ago(CommentsActivity.this, c.createdAt));
            boolean canDelete = me.equals(c.author) || (post != null && me.equals(post.author));
            h.v.bubble.setOnLongClickListener(canDelete ? v -> {
                new MaterialAlertDialogBuilder(CommentsActivity.this).setTitle(R.string.comment_delete_q)
                        .setPositiveButton(R.string.delete, (d, w) -> {
                            if (post != null) graph().feed.deleteComment(post, c);
                        })
                        .setNegativeButton(R.string.cancel, null).show();
                return true;
            } : null);
        }
    }

    private static class Holder extends RecyclerView.ViewHolder {
        final ItemCommentBinding v;

        Holder(ItemCommentBinding v) {
            super(v.getRoot());
            this.v = v;
        }
    }
}
