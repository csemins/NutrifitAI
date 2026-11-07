// Security-rules tests against the local Firebase Emulator Suite.
// Run: npx firebase emulators:exec --only firestore,storage,database "node rules-tests/rules.test.mjs"
import { readFileSync } from 'node:fs';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, deleteDoc, updateDoc, writeBatch, arrayUnion, collection, query, where, getDocs, orderBy } from 'firebase/firestore';
import { ref as sref, uploadBytes, getBytes } from 'firebase/storage';
import { ref as dref, set as dset, get as dget } from 'firebase/database';

// Storage rules are only tested when the Storage emulator runs (the app itself uses the NutriFit AI server bucket).
const withStorage = process.env.SKIP_STORAGE !== '1';
const env = await initializeTestEnvironment({
  projectId: 'nutrifit-ai-6c41c',
  firestore: { rules: readFileSync('firestore.rules', 'utf8'), host: '127.0.0.1', port: 8085 },
  ...(withStorage ? { storage: { rules: readFileSync('storage.rules', 'utf8'), host: '127.0.0.1', port: 9199 } } : {}),
  database: { rules: readFileSync('database.rules.json', 'utf8'), host: '127.0.0.1', port: 9000 },
});

// Remove this suite's own fixtures from earlier runs (other emulator data, e.g. the app's, is left alone).
await env.withSecurityRulesDisabled(async (ctx) => {
  const db = ctx.firestore();
  for (const p of ['friendRequests/alice_bob', 'users/bob/friends/alice', 'users/alice/friends/bob', 'posts/p1', 'posts/p2', 'posts/p3',
    'posts/p4', 'posts/p5', 'posts/p6', 'posts/p7', 'challenges/c1', 'aiAccess/bob', 'aiAccess/carol', 'aiAccess/dave', 'aiAccess/adminUid', 'aiAccess/fakeUid', 'aiAllowList/dave@example.com',
    'publicProfiles/alice', 'publicProfiles/bob', 'usernames/alice_1', 'usernames/alice_new', 'usernames/bobby',
    'users/bob/inbox/n1', 'users/bob/inbox/n2', 'users/carol/inbox/n3', 'posts/p1/comments/k1', 'posts/p1/comments/k2',
    'chats/dm_alice_bob', 'chats/g1', 'chats/dm_alice_bob/messages/m1', 'chats/g1/messages/m2']) {
    await deleteDoc(doc(db, p));
  }
});

let passed = 0, failed = 0;
async function t(name, fn) {
  try { await fn(); passed++; console.log('  ✔', name); } catch (e) { failed++; console.log('  ✖', name, '-', e.message); }
}

const alice = env.authenticatedContext('alice');
const bob = env.authenticatedContext('bob');
const carol = env.authenticatedContext('carol');
const admin = env.authenticatedContext('adminUid', { email: 'minhaz.hredoy@gmail.com', email_verified: true });
const fakeAdmin = env.authenticatedContext('fakeUid', { email: 'cse.minhaz87@gmail.com', email_verified: false });
const dave = env.authenticatedContext('dave', { email: 'Dave@Example.com', email_verified: true });
const anon = env.unauthenticatedContext();
const now = Date.now();
const meal = (id, extra = {}) => ({ id, uid: 'alice', date: '2026-11-05', mealType: 'lunch', status: 'consumed', updatedAt: now, items: [], ...extra });

console.log('Firestore');
await t('owner can write and read own meal', async () => {
  await assertSucceeds(setDoc(doc(alice.firestore(), 'users/alice/mealEntries/m1'), meal('m1')));
  await assertSucceeds(getDoc(doc(alice.firestore(), 'users/alice/mealEntries/m1')));
});
await t('other user cannot read or write', async () => {
  await assertFails(getDoc(doc(bob.firestore(), 'users/alice/mealEntries/m1')));
  await assertFails(setDoc(doc(bob.firestore(), 'users/alice/mealEntries/m2'), meal('m2')));
});
await t('unauthenticated denied', async () => {
  await assertFails(getDoc(doc(anon.firestore(), 'users/alice/mealEntries/m1')));
});
await t('uid spoofing denied', async () => {
  await assertFails(setDoc(doc(alice.firestore(), 'users/alice/mealEntries/m3'), meal('m3', { uid: 'bob' })));
});
await t('invalid fields rejected', async () => {
  await assertFails(setDoc(doc(alice.firestore(), 'users/alice/mealEntries/m4'), meal('m4', { status: 'eaten' })));
  await assertFails(setDoc(doc(alice.firestore(), 'users/alice/mealEntries/m5'), meal('m5', { updatedAt: null })));
  await assertFails(setDoc(doc(alice.firestore(), 'users/alice/water/w1'), { id: 'w1', ml: 99999, updatedAt: now }));
  await assertFails(setDoc(doc(alice.firestore(), 'users/alice/mealEntries/m6'), meal('m6', { imageRemotePath: 'users/bob/meals/m6.jpg' })));
});
await t('valid water and tombstone accepted', async () => {
  await assertSucceeds(setDoc(doc(alice.firestore(), 'users/alice/water/w1'), { id: 'w1', uid: 'alice', ml: 300, updatedAt: now }));
  await assertSucceeds(setDoc(doc(alice.firestore(), 'users/alice/water/w1'), { id: 'w1', uid: 'alice', ml: 300, deleted: true, updatedAt: now + 1 }));
});
await t('unknown sub-collection denied', async () => {
  await assertFails(setDoc(doc(alice.firestore(), 'users/alice/secrets/x'), { updatedAt: now }));
});
await t('catalog is read-only', async () => {
  await assertSucceeds(getDoc(doc(alice.firestore(), 'catalog/rice')));
  await assertFails(setDoc(doc(alice.firestore(), 'catalog/rice'), { name: 'x' }));
  await assertFails(getDoc(doc(anon.firestore(), 'catalog/rice')));
});
await t('owner can delete (account deletion cleanup)', async () => {
  await assertSucceeds(deleteDoc(doc(alice.firestore(), 'users/alice/mealEntries/m1')));
});

if (withStorage) {
console.log('Storage');
const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xd9]);
await t('owner uploads private image', async () => {
  await assertSucceeds(uploadBytes(sref(alice.storage(), 'users/alice/meals/m1.jpg'), jpeg, { contentType: 'image/jpeg' }));
});
await t('other user cannot read image', async () => {
  await assertFails(getBytes(sref(bob.storage(), 'users/alice/meals/m1.jpg')));
});
await t('non-image and oversized uploads rejected', async () => {
  await assertFails(uploadBytes(sref(alice.storage(), 'users/alice/meals/x.txt'), jpeg, { contentType: 'text/plain' }));
  await assertFails(uploadBytes(sref(alice.storage(), 'users/alice/meals/big.jpg'), new Uint8Array(6 * 1024 * 1024), { contentType: 'image/jpeg' }));
});
}

console.log('Firestore: workouts');
const workout = (id, extra = {}) => ({ id, uid: 'alice', date: '2026-11-06', activeMs: 600000, kcalEstimate: 80.5,
  title: 'Full-body starter', setsJson: '[]', updatedAt: now, deleted: false, ...extra });
await t('owner saves and reads a workout', async () => {
  await assertSucceeds(setDoc(doc(alice.firestore(), 'users/alice/workouts/k1'), workout('k1')));
  await assertSucceeds(getDoc(doc(alice.firestore(), 'users/alice/workouts/k1')));
});
await t('other user cannot read or write workouts', async () => {
  await assertFails(getDoc(doc(bob.firestore(), 'users/alice/workouts/k1')));
  await assertFails(setDoc(doc(bob.firestore(), 'users/alice/workouts/k2'), workout('k2')));
});
await t('invalid workouts rejected', async () => {
  await assertFails(setDoc(doc(alice.firestore(), 'users/alice/workouts/k3'), workout('k3', { activeMs: -5 })));
  await assertFails(setDoc(doc(alice.firestore(), 'users/alice/workouts/k4'), workout('k4', { date: 'today' })));
  await assertFails(setDoc(doc(alice.firestore(), 'users/alice/workouts/k5'), workout('k5', { uid: 'bob' })));
  await assertFails(setDoc(doc(alice.firestore(), 'users/alice/workouts/k6'), workout('other-id')));
});
await t('owner saves an AI workout plan', async () => {
  await assertSucceeds(setDoc(doc(alice.firestore(), 'users/alice/workoutPlans/p1'),
    { id: 'p1', uid: 'alice', title: 'AI plan', stepsJson: '[]', updatedAt: now, deleted: false }));
  await assertFails(getDoc(doc(bob.firestore(), 'users/alice/workoutPlans/p1')));
});


console.log('Firestore: AI access');
const card = (uid, extra = {}) => ({ uid, email: null, name: 'X', allowed: false, requestedAt: 0, createdAt: now, updatedAt: now, ...extra });
await t('user creates own card but cannot enable AI themselves', async () => {
  await assertSucceeds(setDoc(doc(bob.firestore(), 'aiAccess/bob'), card('bob')));
  await assertFails(setDoc(doc(carol.firestore(), 'aiAccess/carol'), card('carol', { allowed: true })));
  await assertFails(updateDoc(doc(bob.firestore(), 'aiAccess/bob'), { allowed: true }));
  await assertFails(getDoc(doc(carol.firestore(), 'aiAccess/bob')));
});
await t('access request can be made once', async () => {
  await assertSucceeds(updateDoc(doc(bob.firestore(), 'aiAccess/bob'), { requestedAt: now }));
  await assertFails(updateDoc(doc(bob.firestore(), 'aiAccess/bob'), { requestedAt: now + 1 }));
});
await t('admin (verified email in rules) enables users and manages the allow list', async () => {
  await assertSucceeds(updateDoc(doc(admin.firestore(), 'aiAccess/bob'), { allowed: true }));
  await assertFails(updateDoc(doc(fakeAdmin.firestore(), 'aiAccess/bob'), { allowed: false }));
  await assertSucceeds(setDoc(doc(admin.firestore(), 'aiAllowList/dave@example.com'), { email: 'dave@example.com', addedAt: now }));
  await assertFails(setDoc(doc(bob.firestore(), 'aiAllowList/bob@example.com'), { email: 'bob@example.com', addedAt: now }));
});
await t('pre-allowed verified email may enable its own card', async () => {
  await assertSucceeds(getDoc(doc(dave.firestore(), 'aiAllowList/dave@example.com')));
  await assertFails(getDoc(doc(dave.firestore(), 'aiAllowList/other@example.com')));
  await assertSucceeds(setDoc(doc(dave.firestore(), 'aiAccess/dave'), card('dave', { email: 'Dave@Example.com', allowed: true })));
});
await t('admin emails are pre-allowed for their own card', async () => {
  await assertSucceeds(setDoc(doc(admin.firestore(), 'aiAccess/adminUid'), card('adminUid', { email: 'minhaz.hredoy@gmail.com', allowed: true })));
  await assertFails(setDoc(doc(fakeAdmin.firestore(), 'aiAccess/fakeUid'), card('fakeUid', { email: 'cse.minhaz87@gmail.com', allowed: true })));
});

console.log('Firestore: social');
const req = { from: 'alice', to: 'bob', fromName: 'Alice', toName: 'Bob', fromHandle: 'alice_1', status: 'pending', createdAt: now };
const pcard = (uid, name, handle, extra = {}) => ({ uid, name, nameLower: name.toLowerCase(), handle, photo: null, handleChangedAt: Date.now(), updatedAt: now, ...extra });
async function claim(ctx, uid, name, handle, extra = {}) {
  const b = writeBatch(ctx.firestore());
  b.set(doc(ctx.firestore(), `usernames/${handle}`), { uid });
  b.set(doc(ctx.firestore(), `publicProfiles/${uid}`), pcard(uid, name, handle, extra));
  return b.commit();
}
await t('usernames: claimed together with the public card, unique, readable', async () => {
  await assertSucceeds(claim(alice, 'alice', 'Alice', 'alice_1'));
  await assertSucceeds(getDoc(doc(bob.firestore(), 'publicProfiles/alice')));
  await assertFails(getDoc(doc(anon.firestore(), 'publicProfiles/alice')));
  await assertFails(claim(bob, 'bob', 'Bob', 'alice_1'));                     // taken
  await assertFails(setDoc(doc(bob.firestore(), 'publicProfiles/bob'), pcard('bob', 'Bob', 'bobby'))); // not claimed
  await assertFails(claim(bob, 'bob', 'Bob', 'BAD HANDLE'));
  await assertSucceeds(claim(bob, 'bob', 'Bob', 'bobby'));
  await assertFails(setDoc(doc(bob.firestore(), 'publicProfiles/alice'), pcard('alice', 'X', 'alice_1')));
});
await t('usernames: changing again within 30 days is refused', async () => {
  const b = writeBatch(alice.firestore());
  b.set(doc(alice.firestore(), 'usernames/alice_new'), { uid: 'alice' });
  b.update(doc(alice.firestore(), 'publicProfiles/alice'), { handle: 'alice_new', handleChangedAt: Date.now(), updatedAt: now + 1 });
  b.delete(doc(alice.firestore(), 'usernames/alice_1'));
  await assertFails(b.commit());
  // a profile photo and name can change any time
  await assertSucceeds(updateDoc(doc(alice.firestore(), 'publicProfiles/alice'), { name: 'Alice R', nameLower: 'alice r', updatedAt: now + 2 }));
  await assertFails(updateDoc(doc(alice.firestore(), 'publicProfiles/alice'), { photo: 'users/bob/posts/0123456789abcdefXYZ.jpg', updatedAt: now + 3 }));
});
await t('usernames: allowed after 30 days', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    await updateDoc(doc(ctx.firestore(), 'publicProfiles/alice'), { handleChangedAt: Date.now() - 31 * 24 * 3600 * 1000 });
  });
  const b = writeBatch(alice.firestore());
  b.set(doc(alice.firestore(), 'usernames/alice_new'), { uid: 'alice' });
  b.update(doc(alice.firestore(), 'publicProfiles/alice'), { handle: 'alice_new', handleChangedAt: Date.now(), updatedAt: now + 4 });
  b.delete(doc(alice.firestore(), 'usernames/alice_1'));
  await assertSucceeds(b.commit());
  await assertFails(deleteDoc(doc(alice.firestore(), 'usernames/alice_new'))); // still in use
});
await t('friend request: only sender creates, only receiver accepts', async () => {
  await assertFails(setDoc(doc(bob.firestore(), 'friendRequests/alice_bob'), req));
  await assertSucceeds(setDoc(doc(alice.firestore(), 'friendRequests/alice_bob'), req));
  await assertFails(getDoc(doc(carol.firestore(), 'friendRequests/alice_bob')));
  await assertFails(updateDoc(doc(alice.firestore(), 'friendRequests/alice_bob'), { status: 'accepted' }));
  // the app's list queries
  await assertSucceeds(getDocs(query(collection(bob.firestore(), 'friendRequests'), where('to', '==', 'bob'), where('status', '==', 'pending'))));
  await assertSucceeds(getDocs(query(collection(alice.firestore(), 'friendRequests'), where('from', '==', 'alice'))));
  await assertFails(getDocs(query(collection(carol.firestore(), 'friendRequests'), where('to', '==', 'bob'))));
});
await t('friends doc only after acceptance (batched with the acceptance)', async () => {
  await assertFails(setDoc(doc(bob.firestore(), 'users/bob/friends/carol'), { uid: 'carol', name: 'Carol', since: now }));
  const batch = writeBatch(bob.firestore());
  batch.update(doc(bob.firestore(), 'friendRequests/alice_bob'), { status: 'accepted' });
  batch.set(doc(bob.firestore(), 'users/bob/friends/alice'), { uid: 'alice', name: 'Alice', since: now });
  await assertSucceeds(batch.commit());
  await assertSucceeds(setDoc(doc(alice.firestore(), 'users/alice/friends/bob'), { uid: 'bob', name: 'Bob', since: now }));
  await assertFails(getDoc(doc(carol.firestore(), 'users/alice/friends/bob')));
  await assertSucceeds(deleteDoc(doc(bob.firestore(), 'users/alice/friends/bob'))); // either side can unfriend
});
const post = (id, extra = {}) => ({ id, author: 'alice', authorName: 'Alice', authorPhoto: null, type: 'stats', text: 'Day done', data: {}, media: null,
  audience: ['alice', 'bob'], reactions: {}, commentCount: 0, createdAt: now, ...extra });
await t('posts: audience reads, others cannot', async () => {
  await assertSucceeds(setDoc(doc(alice.firestore(), 'posts/p1'), post('p1')));
  await assertSucceeds(getDoc(doc(bob.firestore(), 'posts/p1')));
  await assertFails(getDoc(doc(carol.firestore(), 'posts/p1')));
  await assertSucceeds(getDocs(query(collection(bob.firestore(), 'posts'), where('audience', 'array-contains', 'bob'), orderBy('createdAt', 'desc'))));
  await assertFails(getDocs(query(collection(carol.firestore(), 'posts'), orderBy('createdAt', 'desc'))));
});
await t('posts: no spoofed author, foreign media or pre-filled reactions', async () => {
  await assertFails(setDoc(doc(bob.firestore(), 'posts/p2'), post('p2')));
  await assertFails(setDoc(doc(alice.firestore(), 'posts/p3'), post('p3', { media: 'users/bob/clips/0123456789abcdefXYZ.mp4' })));
  await assertSucceeds(setDoc(doc(alice.firestore(), 'posts/p4'), post('p4', { type: 'clip', media: 'users/alice/clips/0123456789abcdefXYZ.mp4' })));
  await assertFails(setDoc(doc(alice.firestore(), 'posts/p5'), post('p5', { reactions: { bob: 'like' } })));
  await assertSucceeds(setDoc(doc(alice.firestore(), 'posts/p6'), post('p6', { type: 'achievement', data: { badge: 'streak_7', title: 'Week warrior' } })));
  await assertFails(setDoc(doc(alice.firestore(), 'posts/p7'), post('p7', { type: 'advert' })));
});
await t('reactions: an audience member sets only their own reaction', async () => {
  await assertSucceeds(updateDoc(doc(bob.firestore(), 'posts/p1'), { 'reactions.bob': 'fire' }));
  await assertFails(updateDoc(doc(bob.firestore(), 'posts/p1'), { 'reactions.alice': 'like' }));
  await assertFails(updateDoc(doc(bob.firestore(), 'posts/p1'), { 'reactions.bob': 'angry-spam' }));
  await assertFails(updateDoc(doc(bob.firestore(), 'posts/p1'), { text: 'edited' }));
  await assertFails(updateDoc(doc(carol.firestore(), 'posts/p1'), { 'reactions.carol': 'like' }));
});
await t('comments: audience comments, count moves by one, author or post owner deletes', async () => {
  const c = (id, by, extra = {}) => ({ id, author: by, authorName: by, text: 'Nice!', createdAt: now, ...extra });
  await assertSucceeds(setDoc(doc(bob.firestore(), 'posts/p1/comments/k1'), c('k1', 'bob')));
  await assertSucceeds(updateDoc(doc(bob.firestore(), 'posts/p1'), { commentCount: 1 }));
  await assertFails(updateDoc(doc(bob.firestore(), 'posts/p1'), { commentCount: 5 }));
  await assertFails(setDoc(doc(carol.firestore(), 'posts/p1/comments/k2'), c('k2', 'carol')));
  await assertFails(setDoc(doc(bob.firestore(), 'posts/p1/comments/k2'), c('k2', 'alice')));
  await assertFails(getDoc(doc(carol.firestore(), 'posts/p1/comments/k1')));
  await assertSucceeds(deleteDoc(doc(alice.firestore(), 'posts/p1/comments/k1')));
  await assertFails(deleteDoc(doc(bob.firestore(), 'posts/p1')));
  await assertSucceeds(deleteDoc(doc(alice.firestore(), 'posts/p1')));
});
await t('inbox: friends notify, strangers only send friend requests, owner reads', async () => {
  const n = (id, from, type, extra = {}) => ({ id, from, fromName: from, type, title: 'Hi', body: 'Body', ref: null, read: false, createdAt: now, ...extra });
  await assertSucceeds(setDoc(doc(alice.firestore(), 'users/bob/inbox/n1'), n('n1', 'alice', 'challenge'))); // friends
  await assertSucceeds(setDoc(doc(alice.firestore(), 'users/carol/inbox/n3'), n('n3', 'alice', 'friend_request')));
  await assertFails(setDoc(doc(carol.firestore(), 'users/bob/inbox/n2'), n('n2', 'carol', 'message')));     // not a friend
  await assertFails(setDoc(doc(alice.firestore(), 'users/bob/inbox/n2'), n('n2', 'bob', 'challenge')));     // spoofed sender
  await assertSucceeds(getDoc(doc(bob.firestore(), 'users/bob/inbox/n1')));
  await assertFails(getDoc(doc(alice.firestore(), 'users/bob/inbox/n1')));
  await assertSucceeds(updateDoc(doc(bob.firestore(), 'users/bob/inbox/n1'), { read: true }));
  await assertFails(updateDoc(doc(bob.firestore(), 'users/bob/inbox/n1'), { title: 'x' }));
});
await t('chats: members only; direct-message ids are fixed; groups up to 20', async () => {
  const chat = (id, type, members, extra = {}) => ({ id, type, members, names: {}, title: type === 'group' ? 'Gym squad' : null,
    createdBy: members[0], createdAt: now, lastText: '', lastFrom: '', lastAt: now, ...extra });
  await assertSucceeds(getDoc(doc(alice.firestore(), 'chats/dm_alice_bob'))); // missing: allowed, so the app can check first
  await assertSucceeds(setDoc(doc(alice.firestore(), 'chats/dm_alice_bob'), chat('dm_alice_bob', 'dm', ['alice', 'bob'])));
  await assertFails(setDoc(doc(alice.firestore(), 'chats/dm_bob_alice'), chat('dm_bob_alice', 'dm', ['bob', 'alice'], { createdBy: 'alice' })));
  await assertSucceeds(getDoc(doc(bob.firestore(), 'chats/dm_alice_bob')));
  await assertFails(getDoc(doc(carol.firestore(), 'chats/dm_alice_bob')));
  const m = (id, from, extra = {}) => ({ id, from, fromName: from, text: 'Leg day?', kind: 'text', createdAt: now, ...extra });
  await assertSucceeds(setDoc(doc(bob.firestore(), 'chats/dm_alice_bob/messages/m1'), m('m1', 'bob')));
  await assertFails(setDoc(doc(carol.firestore(), 'chats/dm_alice_bob/messages/m3'), m('m3', 'carol')));
  await assertSucceeds(updateDoc(doc(bob.firestore(), 'chats/dm_alice_bob'), { lastText: 'Leg day?', lastFrom: 'bob', lastAt: now + 1 }));
  await assertFails(updateDoc(doc(bob.firestore(), 'chats/dm_alice_bob'), { members: ['alice', 'bob', 'carol'] }));
  await assertSucceeds(setDoc(doc(alice.firestore(), 'chats/g1'), chat('g1', 'group', ['alice', 'bob', 'carol'])));
  await assertSucceeds(setDoc(doc(carol.firestore(), 'chats/g1/messages/m2'), m('m2', 'carol')));
  await assertFails(updateDoc(doc(bob.firestore(), 'chats/g1'), { title: 'Mine now' }));
  await assertFails(updateDoc(doc(bob.firestore(), 'chats/g1'), { members: ['bob'] }));          // can't remove others
  await assertSucceeds(updateDoc(doc(carol.firestore(), 'chats/g1'), { members: ['alice', 'bob'], names: {} })); // carol leaves
  await assertSucceeds(updateDoc(doc(alice.firestore(), 'chats/g1'), { title: 'Squad' }));
  await assertFails(getDoc(doc(carol.firestore(), 'chats/g1')));
});
const ch = { id: 'c1', host: 'alice', hostName: 'Alice', title: 'Squat duel', stepsJson: '[]', participants: ['alice', 'bob'],
  names: { alice: 'Alice', bob: 'Bob' }, accepted: ['alice'], declined: [], status: 'open', startAt: 0, createdAt: now, results: {} };
await t('challenges: participants only; guests answer, host runs', async () => {
  await assertFails(setDoc(doc(bob.firestore(), 'challenges/c1'), ch));
  await assertSucceeds(setDoc(doc(alice.firestore(), 'challenges/c1'), ch));
  await assertSucceeds(getDoc(doc(bob.firestore(), 'challenges/c1')));
  await assertFails(getDoc(doc(carol.firestore(), 'challenges/c1')));
  await assertSucceeds(getDocs(query(collection(bob.firestore(), 'challenges'), where('participants', 'array-contains', 'bob'), orderBy('createdAt', 'desc'))));
  await assertSucceeds(updateDoc(doc(bob.firestore(), 'challenges/c1'), { accepted: ['alice', 'bob'] }));
  await assertFails(updateDoc(doc(bob.firestore(), 'challenges/c1'), { status: 'live' }));
  await assertFails(updateDoc(doc(bob.firestore(), 'challenges/c1'), { participants: ['alice', 'bob', 'carol'] }));
  await assertSucceeds(updateDoc(doc(alice.firestore(), 'challenges/c1'), { status: 'live', startAt: now + 5000 }));
});

console.log('Realtime Database');
await t('live challenge: each player writes only their own node', async () => {
  const cid = 'c_0123456789abcdef';
  await assertSucceeds(dset(dref(alice.database(), `live/${cid}/alice`), { name: 'Alice', at: now, reps: 3, faults: 1, score: 15, pose: '1,2;3,4' }));
  await assertSucceeds(dget(dref(bob.database(), `live/${cid}`)));
  await assertFails(dset(dref(bob.database(), `live/${cid}/alice`), { name: 'Alice', at: now, reps: 999 }));
  await assertFails(dset(dref(alice.database(), `live/${cid}/alice`), { name: 'Alice', at: now, reps: -1 }));
  await assertFails(dset(dref(alice.database(), `live/${cid}/alice`), { name: 'Alice', at: now, cheat: true }));
  await assertFails(dget(dref(anon.database(), `live/${cid}`)));
});
await t('calls: members publish only their own state; signals reach only their target', async () => {
  const room = 'g_0123456789abcdefghijkl';
  await assertSucceeds(dset(dref(alice.database(), `rtc/${room}/members/alice`), { name: 'Alice', at: now, video: true, audio: false }));
  await assertSucceeds(dget(dref(bob.database(), `rtc/${room}/members`)));
  await assertFails(dset(dref(bob.database(), `rtc/${room}/members/alice`), { name: 'X', at: now }));
  await assertSucceeds(dset(dref(alice.database(), `rtc/${room}/sig/bob/alice/sdp`), { type: 'offer', sdp: 'v=0' }));
  await assertSucceeds(dget(dref(bob.database(), `rtc/${room}/sig/bob`)));
  await assertFails(dget(dref(carol.database(), `rtc/${room}/sig/bob`)));
  await assertFails(dset(dref(carol.database(), `rtc/${room}/sig/bob/alice/sdp`), { type: 'offer', sdp: 'spoof' }));
  await assertFails(dset(dref(alice.database(), `rtc/short/members/alice`), { name: 'A', at: now }));
  // 1.2.3 signalling: per-call session ids ride in members.at and inside sdp (works with the deployed rules)
  const sid = 1762500000000123;
  await assertSucceeds(dset(dref(alice.database(), `rtc/${room}/members/alice`), { name: 'Alice', at: sid, video: true, audio: true }));
  await assertSucceeds(dset(dref(alice.database(), `rtc/${room}/sig/bob/alice`), { sdp: { type: 'offer', sdp: 'v=0', from: sid, to: sid + 1, n: 1 } }));
  await assertSucceeds(dset(dref(alice.database(), `rtc/${room}/sig/bob/alice/ice/k1`), { mid: '0', i: 0, c: 'candidate:1 1 udp 1 1.2.3.4 5 typ host' }));
  await assertSucceeds(dset(dref(alice.database(), `rtc/${room}/sig/bob/alice`), { sdp: { type: 'bye', sdp: '', from: sid, to: sid + 1, n: 0 } }));
  await assertFails(dset(dref(alice.database(), `rtc/${room}/sig/bob/alice`), { sdp: { type: 'offer', sdp: 'v=0' }, junk: 1 }));
});

await t('owner writes presence', async () => {
  await assertSucceeds(dset(dref(alice.database(), 'status/alice/devices/d1'), { online: true, lastSeen: now, model: 'Pixel' }));
});
await t('other user cannot read presence', async () => {
  await assertFails(dget(dref(bob.database(), 'status/alice')));
});
await t('invalid presence shape rejected', async () => {
  await assertFails(dset(dref(alice.database(), 'status/alice/devices/d1'), { online: 'yes', lastSeen: now }));
  await assertFails(dset(dref(alice.database(), 'status/alice/other'), 1));
  await assertFails(dset(dref(alice.database(), 'anything'), 1));
});

await env.cleanup();
console.log(`\n${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
