import math
import os
import sys
from PIL import Image, ImageDraw, ImageFont

try:
    import imageio.v3 as iio
    import numpy as np
except ImportError:
    import imageio
    import numpy as np

# 720x1280 (Standard HD 9:16 portrait video for Instagram Reels)
W, H = 720, 1280
FPS = 30
DURATION = 12 # 12 seconds
TOTAL_FRAMES = FPS * DURATION

print(f"Generating {TOTAL_FRAMES} frames at {FPS} FPS...")

frames = []

for frame_idx in range(TOTAL_FRAMES):
    t = frame_idx / FPS # time in seconds
    img = Image.new("RGB", (W, H), "#070B0E")
    draw = ImageDraw.Draw(img)

    # Ambient Studio Glow
    glow_color = (0, 75, 55)
    draw.ellipse([(W//2 - 280, 200), (W//2 + 280, 800)], fill=glow_color)

    # Phone Frame
    phone_x1, phone_y1 = 60, 80
    phone_x2, phone_y2 = W - 60, H - 80
    draw.rounded_rectangle([phone_x1, phone_y1, phone_x2, phone_y2], radius=44, fill="#121B22", outline="#2A3942", width=3)

    # WhatsApp Header Bar
    draw.rectangle([phone_x1+2, phone_y1+30, phone_x2-2, phone_y1+115], fill="#1F2C34")
    # Avatar
    draw.ellipse([phone_x1+30, phone_y1+42, phone_x1+85, phone_y1+97], fill="#7C3AED")
    draw.text((phone_x1+50, phone_y1+55), "A", fill="#FFFFFF")
    # Title & status
    draw.text((phone_x1+105, phone_y1+46), "Alex Turner", fill="#E9EDEF")
    draw.text((phone_x1+105, phone_y1+72), "online", fill="#00A884")

    # Message 1 (Incoming)
    draw.rounded_rectangle([phone_x1+30, phone_y1+145, phone_x1+380, phone_y1+215], radius=16, fill="#1F2C34")
    draw.text((phone_x1+48, phone_y1+165), "Hey! Did you review the project timeline?", fill="#E9EDEF")
    draw.text((phone_x1+330, phone_y1+192), "09:38", fill="#8696A0")

    # Message 2 (Outgoing at t >= 9.5s)
    if t >= 9.5:
        draw.rounded_rectangle([phone_x2-360, phone_y1+230, phone_x2-30, phone_y1+300], radius=16, fill="#005C4B")
        draw.text((phone_x2-340, phone_y1+250), "Yes! Checking my talking points now.", fill="#FFFFFF")
        draw.text((phone_x2-80, phone_y1+278), "09:40 ✓✓", fill="#73F7D4")

    # Kinetic Banner (0.5s - 2.2s)
    if 0.5 <= t < 2.2:
        draw.rounded_rectangle([phone_x1+40, phone_y1+240, phone_x2-40, phone_y1+330], radius=16, fill="#000000", outline="#00A884", width=2)
        draw.text((phone_x1+65, phone_y1+258), "Tired of forgetting?", fill="#00A884")
        draw.text((phone_x1+65, phone_y1+285), "Private talking points inside WhatsApp", fill="#FFFFFF")

    # Floating Bubble Pin (2.2s onwards)
    if t >= 2.2 and t < 11.0:
        bubble_target_x = phone_x2 - 60
        bubble_x = min(bubble_target_x, phone_x2 + 40 - int((t - 2.2) * 250)) if t < 2.8 else bubble_target_x
        bubble_y = phone_y1 + 270

        pulse = 3 * math.sin(t * 6)
        r = 30 + pulse
        draw.ellipse([bubble_x - r, bubble_y - r, bubble_x + r, bubble_y + r], fill="#00A884", outline="#FFFFFF", width=3)
        draw.text((bubble_x - 8, bubble_y - 10), "📝", fill="#FFFFFF")

    # Scratchpad Overlay (3.6s - 9.4s)
    if 3.6 <= t < 9.4:
        pad_progress = min(1.0, (t - 3.6) * 4) # fast spring entry
        pad_w = int((phone_x2 - phone_x1 - 40) * pad_progress)
        pad_h = int(520 * pad_progress)
        pad_cx = (phone_x1 + phone_x2) // 2
        pad_cy = phone_y1 + 540
        px1 = pad_cx - pad_w // 2
        px2 = pad_cx + pad_w // 2
        py1 = pad_cy - pad_h // 2
        py2 = pad_cy + pad_h // 2

        if pad_w > 40 and pad_h > 40:
            draw.rounded_rectangle([px1, py1, px2, py2], radius=24, fill="#121B22", outline="#2A3942", width=2)

            if pad_progress >= 0.95:
                # Scratchpad Header
                draw.text((px1 + 24, py1 + 20), "📌  Alex Turner", fill="#E9EDEF")
                draw.text((px2 - 110, py1 + 20), "✓ Saved", fill="#00A884")

                # Tabs
                draw.rounded_rectangle([px1 + 24, py1 + 60, px1 + 230, py1 + 105], radius=12, fill="#00A884")
                draw.text((px1 + 55, py1 + 75), "Checklist (1/2)", fill="#FFFFFF")
                draw.rounded_rectangle([px1 + 245, py1 + 60, px2 - 24, py1 + 105], radius=12, fill="#1F2C34")
                draw.text((px1 + 310, py1 + 75), "Notes", fill="#8696A0")

                # Item 1 (Done)
                draw.rounded_rectangle([px1 + 24, py1 + 125, px2 - 24, py1 + 180], radius=12, fill="#1F2C34")
                draw.rectangle([px1 + 40, py1 + 140, px1 + 65, py1 + 165], fill="#00A884")
                draw.text((px1 + 47, py1 + 142), "✓", fill="#FFFFFF")
                draw.text((px1 + 80, py1 + 143), "Review Q4 project timeline", fill="#8696A0")

                # Item 2 (Ticks at t >= 5.5s)
                draw.rounded_rectangle([px1 + 24, py1 + 195, px2 - 24, py1 + 250], radius=12, fill="#1F2C34")
                is_item2_done = t >= 5.5
                if is_item2_done:
                    draw.rectangle([px1 + 40, py1 + 210, px1 + 65, py1 + 235], fill="#00A884")
                    draw.text((px1 + 47, py1 + 212), "✓", fill="#FFFFFF")
                    draw.text((px1 + 80, py1 + 213), "Confirm invoice payment", fill="#8696A0")
                else:
                    draw.rectangle([px1 + 40, py1 + 210, px1 + 65, py1 + 235], fill=None, outline="#00A884", width=2)
                    draw.text((px1 + 80, py1 + 213), "Confirm invoice payment", fill="#E9EDEF")

                # Item 3 (Appears at t >= 7.8s)
                if t >= 7.8:
                    draw.rounded_rectangle([px1 + 24, py1 + 265, px2 - 24, py1 + 320], radius=12, fill="#1F2C34")
                    draw.rectangle([px1 + 40, py1 + 280, px1 + 65, py1 + 305], fill=None, outline="#00A884", width=2)
                    draw.text((px1 + 80, py1 + 283), "Send $5,000 contract", fill="#73F7D4")

                # Input Bar
                draw.rounded_rectangle([px1 + 24, py2 - 80, px2 - 120, py2 - 25], radius=12, fill="#1F2C34")
                if 6.2 <= t < 7.8:
                    typed_len = int((t - 6.2) * 16)
                    full_str = "Send $5,000 contract"
                    draw.text((px1 + 40, py2 - 62), full_str[:typed_len] + "|", fill="#E9EDEF")
                else:
                    draw.text((px1 + 40, py2 - 62), "Add talking point...", fill="#8696A0")

                draw.rounded_rectangle([px2 - 105, py2 - 80, px2 - 24, py2 - 25], radius=12, fill="#00A884")
                draw.text((px2 - 85, py2 - 62), "Add", fill="#FFFFFF")

    # Outro Card (11.0s - 12.0s)
    if t >= 11.0:
        draw.rounded_rectangle([phone_x1+20, phone_y1+250, phone_x2-20, phone_y1+550], radius=24, fill="#000000", outline="#00A884", width=3)
        draw.ellipse([W//2 - 40, phone_y1+280, W//2 + 40, phone_y1+360], fill="#00A884")
        draw.text((W//2 - 12, phone_y1+305), "📝", fill="#FFFFFF")
        draw.text((W//2 - 75, phone_y1+380), "ChatNotes", fill="#FFFFFF")
        draw.text((W//2 - 145, phone_y1+415), "100% Free • Open Source • Offline", fill="#00A884")
        draw.rounded_rectangle([W//2 - 130, phone_y1+455, W//2 + 130, phone_y1+505], radius=14, fill="#00A884")
        draw.text((W//2 - 105, phone_y1+472), "Download on GitHub 🚀", fill="#070B0E")

    # Convert PIL Image to numpy array
    frames.append(np.array(img))

output_video = r"C:\Users\muthy\.gemini\antigravity\scratch\ChatNotes\screenshots\ChatNotes_Reel.mp4"
print(f"Writing MP4 to {output_video}...")

# Write MP4 video using imageio-ffmpeg
import imageio_ffmpeg
writer = imageio_ffmpeg.write_frames(
    output_video,
    (W, H),
    fps=FPS,
    codec="libx264",
    pix_fmt_in="rgb24",
    macro_block_size=16
)
writer.send(None)
for frame in frames:
    writer.send(frame.tobytes())
writer.close()

print(f"DONE! Video saved at: {output_video}")
