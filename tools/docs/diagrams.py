"""Draws the README's explanatory diagrams (dark theme matching the app). Usage: python diagrams.py OUT_DIR"""
import sys
import textwrap
from PIL import Image, ImageDraw, ImageFont

OUT = sys.argv[1]
BG, CARD, EDGE = (11, 13, 11), (24, 29, 24), (52, 62, 48)
LIME, TEXT, MUTED = (184, 241, 90), (240, 244, 236), (176, 186, 170)
ACCENTS = {"lime": LIME, "coral": (255, 128, 102), "amber": (255, 196, 77), "blue": (94, 168, 255),
           "teal": (64, 214, 190), "violet": (176, 140, 255)}
F = "C:/Windows/Fonts/"
H1 = ImageFont.truetype(F + "segoeuib.ttf", 56)
H2 = ImageFont.truetype(F + "segoeuib.ttf", 36)
BODY = ImageFont.truetype(F + "segoeui.ttf", 29)
SMALL = ImageFont.truetype(F + "seguisb.ttf", 26)
W = 1800


def wrap(d, text, font, width):
    words, lines, cur = text.split(), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if d.textlength(t, font=font) <= width:
            cur = t
        else:
            lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def card_height(d, title, lines, width):
    h = 30 + 46
    for ln in lines:
        h += 38 * len(wrap(d, ln, BODY, width - 56))
    return h + 22


def card(d, x, y, w, h, title, lines, accent=LIME, num=None):
    d.rounded_rectangle((x, y, x + w, y + h), 26, fill=CARD, outline=EDGE, width=2)
    d.rounded_rectangle((x, y, x + 10, y + h), 5, fill=accent)
    tx = x + 32
    if num is not None:
        d.ellipse((tx, y + 24, tx + 46, y + 70), fill=accent)
        d.text((tx + 23, y + 47), str(num), font=SMALL, fill=BG, anchor="mm")
        tx += 60
    d.text((tx, y + 26), title, font=H2, fill=TEXT)
    yy = y + 30 + 48
    for ln in lines:
        for part in wrap(d, ln, BODY, w - 56):
            d.text((x + 32, yy), part, font=BODY, fill=MUTED)
            yy += 38


def arrow(d, a, b, color=LIME, width=5):
    d.line((a, b), fill=color, width=width)
    import math
    ang = math.atan2(b[1] - a[1], b[0] - a[0])
    s = 18
    p1 = (b[0] - s * math.cos(ang - 0.45), b[1] - s * math.sin(ang - 0.45))
    p2 = (b[0] - s * math.cos(ang + 0.45), b[1] - s * math.sin(ang + 0.45))
    d.polygon([b, p1, p2], fill=color)


def header(d, title, sub):
    d.text((60, 44), title, font=H1, fill=TEXT)
    d.text((60, 118), sub, font=BODY, fill=MUTED)
    return 180


def flow(name, title, sub, steps, cols=3, accent="lime"):
    """Numbered steps in a snake grid with arrows."""
    tmp = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    gap = 70
    cw = (W - 120 - gap * (cols - 1)) // cols
    rows = [steps[i:i + cols] for i in range(0, len(steps), cols)]
    heights = [max(card_height(tmp, t, l, cw) for t, l in r) for r in rows]
    H = 180 + sum(heights) + gap * (len(rows) - 1) + 50
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    y = header(d, title, sub)
    n = 1
    pos = []
    for ri, r in enumerate(rows):
        order = list(range(len(r))) if ri % 2 == 0 else list(range(cols - 1, cols - 1 - len(r), -1))
        for k, (t, l) in enumerate(r):
            x = 60 + order[k] * (cw + gap)
            card(d, x, y, cw, heights[ri], t, l, ACCENTS[accent], n)
            pos.append((x, y, cw, heights[ri]))
            n += 1
        y += heights[ri] + gap
    for i in range(len(pos) - 1):
        x1, y1, w1, h1 = pos[i]
        x2, y2, w2, h2 = pos[i + 1]
        if y1 == y2:
            if x2 > x1:
                arrow(d, (x1 + w1 + 6, y1 + 50), (x2 - 8, y2 + 50))
            else:
                arrow(d, (x1 - 6, y1 + 50), (x2 + w2 + 8, y2 + 50))
        else:
            arrow(d, (x1 + w1 // 2, y1 + h1 + 6), (x2 + w2 // 2, y2 - 8))
    im.save(f"{OUT}/{name}.png", optimize=True)


def columns(name, title, sub, cols, accent_cycle=("lime", "blue", "amber", "teal", "coral", "violet")):
    """Side-by-side cards (no arrows), each column a stack of cards."""
    tmp = ImageDraw.Draw(Image.new("RGB", (10, 10)))
    gap = 40
    n = len(cols)
    cw = (W - 120 - gap * (n - 1)) // n
    col_h = [sum(card_height(tmp, t, l, cw) + 30 for t, l in c) for c in cols]
    H = 180 + max(col_h) + 30
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    y0 = header(d, title, sub)
    for i, c in enumerate(cols):
        x, y = 60 + i * (cw + gap), y0
        for t, l in c:
            h = card_height(tmp, t, l, cw)
            card(d, x, y, cw, h, t, l, ACCENTS[accent_cycle[i % len(accent_cycle)]])
            y += h + 30
    im.save(f"{OUT}/{name}.png", optimize=True)


# ------------------------------------------------------------------ architecture
def architecture():
    H = 1330
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    header(d, "System architecture", "Android app (Java + XML), a small Java server on your machine, and Firebase.")
    tmp = d
    # app column
    ax, aw = 60, 760
    d.rounded_rectangle((ax - 20, 180, ax + aw + 20, 1300), 30, outline=LIME, width=3)
    d.text((ax, 196), "Android app", font=H2, fill=LIME)
    layers = [
        ("UI layer", ["Activities, Fragments, XML layouts, custom Views (calorie ring, macro chart, water gauge, muscle map, podium)"], "lime"),
        ("ViewModels + LiveData", ["Screen state, survives rotation; observers tied to the lifecycle"], "teal"),
        ("Repositories", ["Diary, foods, water, weight, workouts, steps, social, chat, challenges, auth"], "blue"),
        ("Room (source of truth)", ["Schema v6, migrations 1→6, offline reads and writes, outbox of pending changes"], "amber"),
        ("Background work", ["WorkManager sync and retries, foreground services (export, walk), reminders, boot/time receivers"], "violet"),
    ]
    y = 250
    for t, l, a in layers:
        h = card_height(tmp, t, l, aw)
        card(d, ax, y, aw, h, t, l, ACCENTS[a])
        y += h + 22
    # right column
    rx, rw = 980, 760
    srv = [("NutriFit AI server (Java 17)", ["AI proxy: /v1/analyze, /v1/assistant, /v1/workout",
                                              "Media bucket: /v1/files (photos, avatars, clips)",
                                              "Call relay: /v1/turn + /v1/relay (TURN over HTTPS)",
                                              "Firebase token check, rate limits, AI bulkhead"], "lime"),
           ("Firebase", ["Authentication (email, Google)", "Cloud Firestore: profiles, diary sync, social",
                         "Realtime Database: presence, live challenges, call signalling"], "amber"),
           ("External services", ["OpenAI-compatible LLM gateway (key only on the server)",
                                  "CARTO / OpenStreetMap map tiles", "Health Connect on the phone"], "blue")]
    y = 250
    for t, l, a in srv:
        h = card_height(tmp, t, l, rw)
        card(d, rx, y, rw, h, t, l, ACCENTS[a])
        y += h + 40
    arrow(d, (ax + aw + 24, 420), (rx - 10, 420))
    d.text((ax + aw + 40, 372), "HTTPS", font=SMALL, fill=LIME)
    arrow(d, (ax + aw + 24, 760), (rx - 10, 760))
    d.text((ax + aw + 40, 712), "SDK", font=SMALL, fill=LIME)
    im.save(f"{OUT}/architecture.png", optimize=True)


architecture()

flow("food_ai_pipeline", "How food photo analysis works",
     "From the camera to your diary. Nothing is saved until you confirm it.", [
         ("Capture", ["CameraX photo, or pick one from the gallery (Photo Picker)", "Runtime camera permission, asked in context"]),
         ("Prepare", ["EXIF orientation fixed, resized to 1280 px, JPEG ~80%", "Optional note: portion, oil, ingredients"]),
         ("Send", ["POST /v1/analyze with the Firebase ID token", "Runs in WorkManager; survives the app closing"]),
         ("Server", ["Checks sign-in, size and rate limits", "Asks the LLM for strict JSON (schema 1.0)"]),
         ("Validate", ["Types, ranges, 0–1 confidence, ordered calorie range, max items", "Totals are recomputed, never trusted"]),
         ("Review", ["Each food editable: name, grams, servings, nutrients", "Labelled as an estimate with assumptions"]),
         ("Confirm", ["Saved to Room with a nutrition snapshot", "Queued for Firestore sync (idempotent id)"]),
         ("Outcomes", ["Success, uncertain (asks a question) or not food", "Timeout, offline and malformed replies show retry"]),
         ("Privacy", ["Only the confirmed photo is sent; no video frames", "The LLM key never reaches the phone"]),
     ])

flow("sync", "Offline-first sync (Room ↔ Firestore)",
     "One source of truth on the phone; the cloud is a durable, per-account copy.", [
         ("Write locally", ["Every add, edit or delete goes to Room first", "Screens update at once, even offline"]),
         ("Outbox", ["The same transaction records a sync operation", "Stable UUIDs make uploads idempotent"]),
         ("SyncWorker", ["WorkManager, network-constrained, exponential backoff", "Restarts after reboot or process death"]),
         ("Upload", ["users/{uid}/... documents; deletes become tombstones", "Media goes to the server bucket, paths only in docs"]),
         ("Download", ["Changes since the last cursor, per collection", "Applied with the ConflictResolver"]),
         ("Conflicts", ["Newer updatedAt wins; tombstones beat edits", "History keeps its nutrition and goal snapshots"]),
     ])

flow("live_challenge", "Live challenges and camera rep counting",
     "Friends work out together; the camera counts reps and the app keeps score.", [
         ("Create", ["Pick a friend, a quick duel or a full routine", "Custom workouts with sets and reps"]),
         ("Lobby", ["Both players ready up; live state in Realtime Database", "Presence shows who is online"]),
         ("Pose", ["MediaPipe BlazePose: 33 landmarks per frame, on the phone", "Nothing is uploaded for counting"]),
         ("Count", ["WorkoutEngine follows joint angles per exercise", "Only clean reps count; form tips are spoken"]),
         ("Score", ["+10 per rep, +2 per second held, bonus for finishing first", "Scores stream to the other player live"]),
         ("Talk", ["Optional voice/video between players (WebRTC)", "Relay through the server when needed"]),
         ("Finish", ["Podium with crown for the winner", "Shareable result card and workout summary"]),
     ])

flow("calls", "Voice and video calls",
     "Peer-to-peer WebRTC, with a relay on your own server for networks that block direct connections.", [
         ("Join", ["Each join gets a session id in rtc/{room}/members", "Old data from earlier calls is ignored"]),
         ("Signal", ["Offer, answer and ICE candidates via Realtime Database", "The lower user id makes the offer"]),
         ("Direct first", ["STUN finds a direct path (most Wi-Fi works)"]),
         ("Relay", ["Phone bridge: turn:127.0.0.1 → HTTPS tunnel → TurnRelay", "Offsets make uploads and the download stream lossless"]),
         ("Recover", ["Short network drops keep the call", "Failed links restart ICE automatically"]),
         ("Leave", ["Camera and mic released; others told at once"]),
     ])

columns("steps", "Steps and walks without double counting",
        "Daily steps take the larger trusted source; walks show their steps but never add them again.", [
            [("Phone step sensor", ["TYPE_STEP_COUNTER, sampled by StepWorker", "Works without any account"]),
             ("Health Connect", ["Reads Google Fit / Health Connect step totals", "Exports walks only as exercise sessions"])],
            [("Daily total", ["max(phone sensor, Health Connect)", "Never the sum of the two"]),
             ("Battery saver", ["Power-save broadcast pauses tracking and tells you"])],
            [("Mapped walk", ["GPS route every 2 s, accuracy ≤ 25 m", "Distance, pace, speed, climb, km splits"]),
             ("Stored on the walk", ["Steps live on the walk session only", "The day total is unchanged"])],
        ])

columns("features", "Everything in the app", "Every feature below is implemented and connected to real data.", [
    [("Nutrition", ["Calorie ring, macros, fibre", "Meals: breakfast to snacks, planned vs eaten", "Food search, English + Bangla", "Custom foods, recipes, favourites", "Barcode scan (ML Kit)"]),
     ("AI", ["Photo, voice and text logging", "Nutrition coach chat", "AI workout plans"])],
    [("Body & habits", ["Hydration gauge, reminders", "Weight history and BMI", "Goals with transparent formulas", "Fasting timer", "Achievements and streaks"]),
     ("Progress", ["Week / month / year trends", "Missing days are not zeros", "Shareable cards"])],
    [("Training", ["Exercise library with muscle map", "Camera rep counting and voice coach", "Programs and custom workouts", "Mapped walks and step tracking"]),
     ("Social", ["Friends, usernames, feed", "Chats and groups, calls", "Live challenges with podium"])],
    [("Data", ["Offline-first Room database", "Firestore sync with tombstones", "CSV and XML export, XML import", "Share via FileProvider"]),
     ("Account", ["Email and Google sign-in", "Profile, units, appearance", "Full account deletion"])],
])

columns("data_model", "Data model", "Room tables on the phone (left) and their cloud homes (right). All rows carry ids, owner and timestamps.", [
    [("Profile & goals", ["profiles, goals (effective dates)"]),
     ("Food", ["foods, servings, favorites, recipes, recipe_ingredients"]),
     ("Diary", ["meal_entries, meal_items (nutrition snapshot)"]),
     ("Body", ["water_entries, weight_entries, daily_steps, fasts"])],
    [("Training", ["workout_plans, workout_sessions (routes, splits)"]),
     ("AI", ["ai_analyses, conversations, chat_messages"]),
     ("Settings", ["reminder_settings, import_batches"]),
     ("Sync", ["sync_ops (outbox), sync_meta (cursors)"])],
    [("Firestore", ["users/{uid}/{collection}/{id}", "publicProfiles, usernames, friendRequests", "posts + comments, chats + messages, challenges"]),
     ("Realtime Database", ["status/{uid} presence", "live/{challenge} scores", "rtc/{room} call signalling"]),
     ("Server bucket", ["users/{uid}/meals, avatars, posts, clips"])],
])
print("diagrams ok")
