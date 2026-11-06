package com.transformsai.nutrifitai.social;

import com.transformsai.nutrifitai.data.AppGraph;

/**
 * Account deletion for everything social: posts and media, friendships and requests, chats I created,
 * challenges I hosted, my inbox, my username and public card. Blocking; called by AuthRepository.
 */
public final class SocialCleanup {
    private SocialCleanup() { }

    public static void deleteAllBlocking(AppGraph g, String uid) throws Exception {
        g.feed.deleteBlocking(uid);
        g.friends.deleteBlocking(uid);
        g.chats.deleteBlocking(uid);
        g.challenges.deleteBlocking(uid);
        g.inbox.deleteBlocking(uid);
        g.cards.deleteBlocking(uid);
    }
}
