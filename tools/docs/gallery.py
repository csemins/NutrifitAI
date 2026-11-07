"""Builds README screenshot strips from real app screenshots. Usage: python gallery.py SHOTS_DIR OUT_DIR"""
import sys
from PIL import Image, ImageDraw, ImageFont

SRC, OUT = sys.argv[1], sys.argv[2]
BG, TEXT, MUTED, LIME = (11, 13, 11), (240, 244, 236), (176, 186, 170), (184, 241, 90)
F = "C:/Windows/Fonts/"
TITLE = ImageFont.truetype(F + "segoeuib.ttf", 34)
SUB = ImageFont.truetype(F + "segoeui.ttf", 26)
PH = 1000  # phone image height


def phone(path):
    im = Image.open(path).convert("RGB")
    w = int(im.width * PH / im.height)
    im = im.resize((w, PH), Image.LANCZOS)
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, PH - 1), 38, fill=255)
    out = Image.new("RGB", im.size, BG)
    out.paste(im, (0, 0), mask)
    return out


def strip(name, items):
    """items: (file, title, subtitle). One row, captions under each screen."""
    ims = [phone(f"{SRC}/{f}") for f, _, _ in items]
    gap = 36
    W = sum(i.width for i in ims) + gap * (len(ims) + 1)
    H = PH + 40 + 120
    canvas = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(canvas)
    x = gap
    for im, (_, t, s) in zip(ims, items):
        canvas.paste(im, (x, 30))
        cx = x + im.width // 2
        d.text((cx, PH + 50), t, font=TITLE, fill=TEXT, anchor="ma")
        d.text((cx, PH + 96), s, font=SUB, fill=MUTED, anchor="ma")
        x += im.width + gap
    if W > 2400:
        canvas = canvas.resize((2400, int(H * 2400 / W)), Image.LANCZOS)
    canvas.save(f"{OUT}/{name}.jpg", quality=88, optimize=True)


strip("screens_start", [
    ("r2.png", "Welcome", "What the app does"),
    ("r3.png", "Sign in", "Email or Google"),
    ("s3.png", "About you", "Name, username, birth date"),
    ("s9.png", "Your body", "Height, weight, BMI"),
    ("s10.png", "Daily targets", "Explained, editable"),
])
strip("screens_nutrition", [
    ("s32_home.png", "Home (dark)", "Calories, macros, fibre"),
    ("light_home.png", "Home (light)", "Quick actions"),
    ("light_progress.png", "Progress", "Calories vs target"),
    ("s30.png", "Trends", "Weight over time"),
])
strip("screens_training", [
    ("s32_train.png", "Train", "Schedule and today's plan"),
    ("s32_plan.png", "Workout plan", "Muscles, camera, voice"),
    ("live.png", "Live workout", "Camera counts clean reps"),
    ("share_workout_muscles.png", "Workout card", "Muscles worked, to share"),
])
strip("screens_social", [
    ("s13.png", "Your circle", "Feed with friends' stats"),
    ("ch2.png", "Challenge a friend", "Duels and routines"),
    ("ch4.png", "Countdown", "Both players ready"),
    ("ch10.png", "Results", "Points and winner"),
    ("share_challenge_podium.png", "Podium card", "Crown for the winner"),
])
strip("screens_connect", [
    ("s21.png", "Chat", "Messages and calls"),
    ("s22.png", "Call", "Voice and video"),
    ("s25.png", "Profile", "Streaks and stats"),
    ("s26.png", "Edit profile", "Photo, body, units"),
    ("share_progress.png", "Progress card", "Share to any app"),
])
print("gallery ok")
