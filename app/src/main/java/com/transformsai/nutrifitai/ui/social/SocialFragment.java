package com.transformsai.nutrifitai.ui.social;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.transformsai.nutrifitai.App;
import com.transformsai.nutrifitai.R;
import com.transformsai.nutrifitai.data.AppGraph;
import com.transformsai.nutrifitai.databinding.FragmentSocialBinding;
import com.transformsai.nutrifitai.social.SocialModels.Challenge;
import com.transformsai.nutrifitai.social.SocialModels.Chat;
import com.transformsai.nutrifitai.social.SocialModels.FriendRequest;
import com.transformsai.nutrifitai.social.SocialModels.Person;
import com.transformsai.nutrifitai.social.SocialModels.Post;
import com.transformsai.nutrifitai.ui.common.InsetsUtil;
import com.transformsai.nutrifitai.ui.common.ShareCards;
import com.transformsai.nutrifitai.ui.common.Ui;
import com.transformsai.nutrifitai.ui.main.MainActivity;
import com.transformsai.nutrifitai.ui.profile.EditProfileActivity;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Social tab: Feed (friends' posts, reactions, comments), Chats (direct messages and friend groups), Challenges
 * (invites, live and finished workouts) and Friends (username, search, requests, friend list). All lists are live
 * Firestore queries that stop listening when the tab's view is gone.
 */
public class SocialFragment extends Fragment implements MainActivity.ScrollToTop, SocialAdapter.PostActions {
    public static final int SECTION_FEED = 0, SECTION_CHATS = 1, SECTION_CHALLENGES = 2, SECTION_FRIENDS = 3;
    /** Section to show the next time the tab appears (set by notifications). */
    private static int pendingSection = -1;

    private FragmentSocialBinding b;
    private final AppGraph g = App.graph();
    private SocialAdapter adapter;
    private int tab = SECTION_FEED;
    private String uid;
    private List<Post> posts;
    private List<Challenge> challenges;
    private List<Chat> chats;
    private List<Person> friends = Collections.emptyList(), results = Collections.emptyList();
    private List<FriendRequest> incoming = Collections.emptyList(), outgoing = Collections.emptyList();
    private final Set<String> requested = new HashSet<>();
    private final Map<String, File> mediaCache = new HashMap<>();
    @Nullable private String handle;

    public static void showSection(int section) {
        pendingSection = section;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle s) {
        b = FragmentSocialBinding.inflate(inf, c, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle s) {
        InsetsUtil.padding(b.content, true, false);
        uid = g.session.requireUid();
        if (s != null) tab = s.getInt("tab", SECTION_FEED);
        adapter = new SocialAdapter(this);
        b.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.list.setAdapter(adapter);
        b.list.setItemAnimator(null);
        b.tabs.select(tab, false);
        b.tabs.setOnSelectListener(i -> {
            tab = i;
            render();
        });
        b.header.overline.setText(R.string.nav_social);
        b.header.title.setText(R.string.social_title);
        b.header.action2.setVisibility(View.VISIBLE);
        b.header.action2.setImageResource(R.drawable.ic_add);
        b.header.action2.setOnClickListener(x -> {
            if (tab == SECTION_CHALLENGES) newChallenge();
            else if (tab == SECTION_FRIENDS) b.search.requestFocus();
            else if (tab == SECTION_CHATS) newMessage();
            else compose(Post.STATS);
        });
        b.header.avatar.bind(getViewLifecycleOwner());
        b.header.avatar.setOnClickListener(x -> ((MainActivity) requireActivity()).select(MainActivity.TAB_PROFILE));
        Ui.onClick(b.newChallenge, x -> newChallenge());
        Ui.onClick(b.newMessage, x -> newMessage());
        Ui.onClick(b.newGroup, x -> startActivity(new Intent(requireContext(), NewGroupActivity.class)));
        b.shareStats.setOnClickListener(x -> compose(Post.STATS));
        b.shareWorkout.setOnClickListener(x -> compose(Post.WORKOUT));
        b.sharePhoto.setOnClickListener(x -> compose(Post.MEAL));
        b.shareClip.setOnClickListener(x -> compose(Post.CLIP));
        b.invite.setOnClickListener(x -> invite());
        b.editHandle.setOnClickListener(x -> startActivity(new Intent(requireContext(), EditProfileActivity.class)));
        b.search.setOnEditorActionListener((tv, id, e) -> {
            if (id != EditorInfo.IME_ACTION_SEARCH) return false;
            search();
            return true;
        });
        b.searchLayout.setEndIconOnClickListener(x -> {
            b.search.setText("");
            results = Collections.emptyList();
            render();
        });

        g.profile.observe(uid).observe(getViewLifecycleOwner(), p -> {
            handle = p == null ? null : p.username;
            b.handle.setText(handle == null ? getString(R.string.choose_username) : "@" + handle);
            b.editHandle.setVisibility(View.VISIBLE);
            b.editHandle.setText(handle == null ? R.string.set : R.string.edit);
            b.invite.setVisibility(handle == null ? View.GONE : View.VISIBLE);
        });
        g.runIo(() -> {
            try {
                g.cards.refreshCardBlocking(); // keeps my name and photo current for friends
                g.cards.republishPhotoIfMissingBlocking(); // an earlier upload may have failed (offline, server down)
            } catch (Exception ignored) {
                // offline: refreshed next time
            }
        });
        g.feed.feed().observe(getViewLifecycleOwner(), l -> {
            posts = l;
            render();
        });
        g.chats.chats().observe(getViewLifecycleOwner(), l -> {
            chats = l;
            render();
        });
        g.challenges.challenges().observe(getViewLifecycleOwner(), l -> {
            challenges = l;
            render();
        });
        g.friends.friends().observe(getViewLifecycleOwner(), l -> {
            friends = l;
            render();
        });
        g.friends.incoming().observe(getViewLifecycleOwner(), l -> {
            incoming = l;
            render();
        });
        g.friends.outgoing().observe(getViewLifecycleOwner(), l -> {
            outgoing = l;
            requested.clear();
            for (FriendRequest r : l) {
                if (FriendRequest.ACCEPTED.equals(r.status)) g.friends.completeAccepted(r); // they said yes
                else requested.add(r.to);
            }
            render();
        });
        render();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (pendingSection >= 0 && b != null) {
            tab = pendingSection;
            pendingSection = -1;
            b.tabs.select(tab, false);
        }
        render(); // unread dots of chats depend on what was opened meanwhile
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
    }

    // ------------------------------------------------------------------ rendering
    private void render() {
        if (b == null) return;
        b.composer.setVisibility(tab == SECTION_FEED ? View.VISIBLE : View.GONE);
        b.chatsBox.setVisibility(tab == SECTION_CHATS ? View.VISIBLE : View.GONE);
        b.newChallenge.setVisibility(tab == SECTION_CHALLENGES ? View.VISIBLE : View.GONE);
        b.findBox.setVisibility(tab == SECTION_FRIENDS ? View.VISIBLE : View.GONE);
        b.header.action2.setContentDescription(getString(tab == SECTION_CHALLENGES ? R.string.challenge_new
                : tab == SECTION_FRIENDS ? R.string.social_find : tab == SECTION_CHATS ? R.string.chat_new_message : R.string.social_new));
        List<SocialAdapter.Row> rows = new ArrayList<>();
        if (tab == SECTION_FEED) feedRows(rows);
        else if (tab == SECTION_CHATS) chatRows(rows);
        else if (tab == SECTION_CHALLENGES) challengeRows(rows);
        else friendRows(rows);
        b.loading.setVisibility((tab == SECTION_FEED && posts == null) || (tab == SECTION_CHALLENGES && challenges == null)
                || (tab == SECTION_CHATS && chats == null) ? View.VISIBLE : View.GONE);
        adapter.submitList(rows);
    }

    private void feedRows(List<SocialAdapter.Row> rows) {
        if (posts == null) return;
        if (posts.isEmpty()) {
            rows.add(SocialAdapter.Row.empty("feed", R.drawable.ill_progress, getString(R.string.feed_empty_title),
                    getString(friends.isEmpty() ? R.string.feed_empty_body_friends : R.string.feed_empty_body),
                    getString(friends.isEmpty() ? R.string.social_find : R.string.share_stats),
                    friends.isEmpty() ? () -> b.tabs.select(SECTION_FRIENDS, true) : () -> compose(Post.STATS)));
            return;
        }
        for (Post p : posts) rows.add(SocialAdapter.Row.post(p));
    }

    private Map<String, Person> friendMap() {
        Map<String, Person> m = new HashMap<>();
        for (Person p : friends) m.put(p.uid, p);
        return m;
    }

    private void chatRows(List<SocialAdapter.Row> rows) {
        if (chats == null) return;
        if (chats.isEmpty()) {
            rows.add(SocialAdapter.Row.empty("chats", R.drawable.ill_chat, getString(R.string.chats_empty_title),
                    getString(R.string.chats_empty_body), getString(friends.isEmpty() ? R.string.social_find : R.string.chat_new_message),
                    friends.isEmpty() ? () -> b.tabs.select(SECTION_FRIENDS, true) : this::newMessage));
            return;
        }
        Map<String, Person> people = friendMap();
        for (Chat c : chats) {
            SocialAdapter.Row r = new SocialAdapter.Row(SocialAdapter.ROW, "chat:" + c.id);
            r.title = c.displayName(uid);
            boolean group = Chat.GROUP.equals(c.type);
            if (group) {
                r.avatarText = "👥";
            } else {
                Person other = people.get(c.otherMember(uid));
                r.uid = c.otherMember(uid);
                r.photo = other == null ? null : other.photo;
            }
            String last = c.lastText == null || c.lastText.isEmpty() ? getString(group ? R.string.group_members_count : R.string.chat_say_hi, c.members.size())
                    : (uid.equals(c.lastFrom) ? getString(R.string.you) + ": "
                    : group && c.lastFrom != null && c.names.containsKey(c.lastFrom) ? c.names.get(c.lastFrom) + ": " : "") + c.lastText;
            r.subtitle = c.lastAt > 0 ? last + " · " + Ui.ago(requireContext(), c.lastAt) : last;
            r.unread = c.lastAt > g.prefs.chatReadAt(c.id) && c.lastFrom != null && !c.lastFrom.isEmpty() && !uid.equals(c.lastFrom) ? 1 : 0;
            r.onClick = () -> startActivity(ChatActivity.intent(requireContext(), c.id));
            rows.add(r);
        }
    }

    private void challengeRows(List<SocialAdapter.Row> rows) {
        if (challenges == null) return;
        if (challenges.isEmpty()) {
            rows.add(SocialAdapter.Row.empty("ch", R.drawable.ill_workouts, getString(R.string.challenge_empty_title),
                    getString(R.string.challenge_empty_body), getString(R.string.challenge_new), this::newChallenge));
            return;
        }
        List<Challenge> invites = new ArrayList<>(), active = new ArrayList<>(), done = new ArrayList<>();
        for (Challenge c : challenges) {
            if (Challenge.FINISHED.equals(c.status)) done.add(c);
            else if (!c.accepted.contains(uid) && !c.declined.contains(uid)) invites.add(c);
            else if (!c.declined.contains(uid)) active.add(c);
        }
        if (!invites.isEmpty()) rows.add(SocialAdapter.Row.section(getString(R.string.challenge_invites)));
        for (Challenge c : invites) {
            SocialAdapter.Row r = challengeRow(c);
            r.primary = getString(R.string.challenge_accept);
            r.onPrimary = () -> {
                g.challenges.answer(c, true);
                startActivity(ChallengeLobbyActivity.intent(requireContext(), c.id));
            };
            r.secondaryIcon = R.drawable.ic_close;
            r.secondaryDescription = getString(R.string.challenge_decline);
            r.onSecondary = () -> g.challenges.answer(c, false);
            rows.add(r);
        }
        if (!active.isEmpty()) rows.add(SocialAdapter.Row.section(getString(R.string.challenge_active)));
        for (Challenge c : active) {
            SocialAdapter.Row r = challengeRow(c);
            r.primary = getString(Challenge.LIVE.equals(c.status) ? R.string.challenge_join : R.string.challenge_open);
            r.onPrimary = r.onClick;
            rows.add(r);
        }
        if (!done.isEmpty()) rows.add(SocialAdapter.Row.section(getString(R.string.challenge_done)));
        for (Challenge c : done) rows.add(challengeRow(c));
    }

    private SocialAdapter.Row challengeRow(Challenge c) {
        SocialAdapter.Row r = new SocialAdapter.Row(SocialAdapter.ROW, "c:" + c.id + ":" + c.status);
        r.title = c.title;
        r.avatarText = Challenge.LIVE.equals(c.status) ? "🔥" : "🏆";
        List<String> others = new ArrayList<>();
        for (String p : c.participants) if (!p.equals(uid)) others.add(c.nameOf(p));
        String with = getString(R.string.challenge_with, String.join(", ", others));
        String status = Challenge.FINISHED.equals(c.status) ? winnerLine(c)
                : Challenge.LIVE.equals(c.status) ? getString(R.string.challenge_status_live)
                : getString(R.string.challenge_status_open, c.accepted.size(), c.participants.size());
        r.subtitle = with + " · " + status;
        r.onClick = () -> startActivity(Challenge.FINISHED.equals(c.status)
                ? ChallengeResultActivity.intent(requireContext(), c.id) : ChallengeLobbyActivity.intent(requireContext(), c.id));
        return r;
    }

    private String winnerLine(Challenge c) {
        String best = null;
        long bestScore = Long.MIN_VALUE;
        for (Map.Entry<String, Map<String, Object>> e : c.results.entrySet()) {
            Object s = e.getValue().get("score");
            long v = s instanceof Number ? ((Number) s).longValue() : Long.MIN_VALUE;
            if (v > bestScore) {
                bestScore = v;
                best = e.getKey();
            }
        }
        return best == null ? getString(R.string.challenge_status_done)
                : getString(R.string.challenge_winner, best.equals(uid) ? getString(R.string.you) : c.nameOf(best));
    }

    private void friendRows(List<SocialAdapter.Row> rows) {
        Set<String> friendIds = new HashSet<>();
        for (Person p : friends) friendIds.add(p.uid);
        if (!results.isEmpty()) {
            rows.add(SocialAdapter.Row.section(getString(R.string.social_results)));
            for (Person p : results) {
                SocialAdapter.Row r = personRow("r:", p);
                if (friendIds.contains(p.uid)) r.subtitle = getString(R.string.social_already_friends);
                else if (requested.contains(p.uid)) r.primary = getString(R.string.social_requested);
                else {
                    r.primary = getString(R.string.social_add);
                    r.onPrimary = () -> g.friends.sendRequest(p, res -> {
                        if (b != null && !res.ok()) Ui.snack(b.getRoot(), res.error);
                    });
                }
                rows.add(r);
            }
        }
        if (!incoming.isEmpty()) {
            rows.add(SocialAdapter.Row.section(getString(R.string.social_requests)));
            for (FriendRequest q : incoming) {
                SocialAdapter.Row r = new SocialAdapter.Row(SocialAdapter.ROW, "q:" + q.id());
                r.title = q.fromName;
                r.uid = q.from;
                r.photo = q.fromPhoto;
                r.subtitle = q.fromHandle == null ? getString(R.string.social_wants_friend) : "@" + q.fromHandle;
                r.primary = getString(R.string.challenge_accept);
                r.onPrimary = () -> g.friends.accept(q);
                r.secondaryIcon = R.drawable.ic_close;
                r.secondaryDescription = getString(R.string.challenge_decline);
                r.onSecondary = () -> g.friends.remove(q);
                rows.add(r);
            }
        }
        List<FriendRequest> pending = new ArrayList<>();
        for (FriendRequest q : outgoing) if (FriendRequest.PENDING.equals(q.status)) pending.add(q);
        if (!pending.isEmpty()) {
            rows.add(SocialAdapter.Row.section(getString(R.string.social_sent)));
            for (FriendRequest q : pending) {
                SocialAdapter.Row r = new SocialAdapter.Row(SocialAdapter.ROW, "o:" + q.id());
                r.title = q.toName;
                r.uid = q.to;
                r.subtitle = getString(R.string.social_waiting);
                r.secondaryIcon = R.drawable.ic_close;
                r.secondaryDescription = getString(R.string.social_withdraw);
                r.onSecondary = () -> g.friends.remove(q);
                rows.add(r);
            }
        }
        rows.add(SocialAdapter.Row.section(getString(R.string.social_friends_count, friends.size())));
        if (friends.isEmpty()) {
            rows.add(SocialAdapter.Row.empty("friends", R.drawable.ill_chat, getString(R.string.friends_empty_title),
                    getString(R.string.friends_empty_body), handle == null ? null : getString(R.string.social_invite),
                    handle == null ? null : this::invite));
        }
        for (Person p : friends) {
            SocialAdapter.Row r = personRow("f:", p);
            r.primary = getString(R.string.chat_message);
            r.onPrimary = () -> message(p);
            r.secondaryIcon = R.drawable.ic_more;
            r.secondaryDescription = getString(R.string.more);
            r.onSecondary = () -> friendMenu(p);
            r.onClick = () -> message(p);
            rows.add(r);
        }
    }

    private SocialAdapter.Row personRow(String prefix, Person p) {
        SocialAdapter.Row r = new SocialAdapter.Row(SocialAdapter.ROW, prefix + p.uid);
        r.title = p.name;
        r.uid = p.uid;
        r.photo = p.photo;
        r.subtitle = p.handle == null ? null : "@" + p.handle;
        return r;
    }

    private void friendMenu(Person p) {
        String[] items = {getString(R.string.challenge_short), getString(R.string.social_unfriend)};
        new MaterialAlertDialogBuilder(requireContext()).setTitle(p.name)
                .setItems(items, (d, w) -> {
                    if (w == 0) startActivity(NewChallengeActivity.intent(requireContext(), p.uid, null));
                    else new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(getString(R.string.social_unfriend_q, p.name))
                            .setPositiveButton(R.string.social_unfriend, (dd, ww) -> g.friends.unfriend(p.uid))
                            .setNegativeButton(R.string.cancel, null).show();
                }).show();
    }

    // ------------------------------------------------------------------ actions
    private void message(Person p) {
        g.chats.openDm(p, r -> {
            if (b == null) return;
            if (r.ok()) startActivity(ChatActivity.intent(requireContext(), r.value));
            else Ui.snack(b.getRoot(), r.error);
        });
    }

    /** Pick a friend to message. */
    private void newMessage() {
        if (friends.isEmpty()) {
            Ui.snack(b.getRoot(), getString(R.string.chat_need_friends));
            b.tabs.select(SECTION_FRIENDS, true);
            return;
        }
        String[] names = new String[friends.size()];
        for (int i = 0; i < names.length; i++) names[i] = friends.get(i).name;
        new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.chat_new_message)
                .setItems(names, (d, w) -> message(friends.get(w))).show();
    }

    private void search() {
        Ui.hideKeyboard(requireActivity());
        String q = b.search.getText() == null ? "" : b.search.getText().toString();
        b.loading.setVisibility(View.VISIBLE);
        g.friends.search(q, r -> {
            if (b == null) return;
            b.loading.setVisibility(View.GONE);
            if (!r.ok()) {
                Ui.snack(b.getRoot(), r.error);
                return;
            }
            results = r.value;
            if (results.isEmpty()) Ui.snack(b.getRoot(), getString(R.string.social_no_results));
            render();
        });
    }

    /** Implicit intent: share my username through any app (chooser). */
    private void invite() {
        if (handle == null) return;
        String text = getString(R.string.social_invite_text, "@" + handle);
        startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text), getString(R.string.social_invite)));
    }

    private void compose(String type) {
        startActivity(ComposePostActivity.intent(requireContext(), type, null));
    }

    private void newChallenge() {
        startActivity(NewChallengeActivity.intent(requireContext(), null, null));
    }

    // ------------------------------------------------------------------ post actions
    @Override
    public void react(Post p, @Nullable String reaction) {
        g.feed.react(p, reaction);
    }

    @Override
    public void openComments(Post p) {
        startActivity(CommentsActivity.intent(requireContext(), p.id));
    }

    @Override
    public void share(Post p) {
        ShareCards.sharePost(requireActivity(), p, p.media == null ? null : mediaCache.get(p.media));
    }

    @Override
    public boolean mine(Post p) {
        return uid.equals(p.author);
    }

    @Nullable
    @Override
    public String myReaction(Post p) {
        return p.reactions == null ? null : p.reactions.get(uid);
    }

    @Override
    public void more(Post p, View anchor) {
        PopupMenu m = new PopupMenu(requireContext(), anchor);
        m.getMenu().add(R.string.delete);
        m.setOnMenuItemClickListener(i -> {
            new MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.post_delete_q)
                    .setPositiveButton(R.string.delete, (d, w) -> g.feed.deletePost(p))
                    .setNegativeButton(R.string.cancel, null).show();
            return true;
        });
        m.show();
    }

    /** Photos and clip thumbnails are fetched with the user's token, cached, then shown with Glide. */
    @Override
    public void loadMedia(Post p, ImageView into) {
        String path = p.media;
        if (path == null) return;
        into.setTag(path);
        File cached = mediaCache.get(path);
        if (cached != null) {
            Glide.with(into).load(cached).centerCrop().into(into);
            return;
        }
        g.runIo(() -> {
            try {
                File f = g.feed.media(path);
                g.main.post(() -> {
                    mediaCache.put(path, f);
                    if (b != null && path.equals(into.getTag())) Glide.with(into).load(f).centerCrop().into(into);
                });
            } catch (Exception ignored) {
                // offline or removed: the card keeps its placeholder
            }
        });
    }

    @Override
    public void openMedia(Post p) {
        File f = p.media == null ? null : mediaCache.get(p.media);
        if (f == null) return;
        if (p.media.endsWith(".mp4")) startActivity(ClipPlayerActivity.intent(requireContext(), f.getAbsolutePath()));
    }

    @Override
    public void scrollToTop() {
        if (b != null) b.scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        b = null;
    }
}
