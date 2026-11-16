import os
import math
from PIL import Image, ImageDraw, ImageFont

# 9:16 portrait video dimensions (450 x 800 for high quality smooth GIF)
W, H = 450, 800
FPS = 15
TOTAL_SECONDS = 8
TOTAL_FRAMES = FPS * TOTAL_SECONDS

frames = []

for frame_idx in range(TOTAL_FRAMES):
    t = frame_idx / FPS # current time in seconds
    img = Image.new("RGB", (W, H), "#0B141A")
    draw = ImageDraw.Draw(img)

    # Ambient teal studio glow in background
    glow_color = (0, 60, 48)
    draw.ellipse([(W//2 - 200, 100), (W//2 + 200, 500)], fill=glow_color)

    # Phone outline
    phone_x1, phone_y1 = 30, 40
    phone_x2, phone_y2 = W - 30, H - 40
    draw.rounded_rectangle([phone_x1, phone_y1, phone_x2, phone_y2], radius=32, fill="#121B22", outline="#2A3942", width=3)

    # WhatsApp Header Bar
    draw.rectangle([phone_x1+2, phone_y1+20, phone_x2-2, phone_y1+75], fill="#1F2C34")
    # Avatar
    draw.ellipse([phone_x1+20, phone_y1+30, phone_x1+55, phone_y1+65], fill="#7C3AED")
    draw.text((phone_x1+33, phone_y1+38), "A", fill="#FFFFFF")
    # Title & status
    draw.text((phone_x1+65, phone_y1+32), "Alex Turner", fill="#E9EDEF")
    draw.text((phone_x1+65, phone_y1+50), "online", fill="#00A884")

    # Message 1
    draw.rounded_rectangle([phone_x1+20, phone_y1+100, phone_x1+260, phone_y1+145], radius=12, fill="#1F2C34")
    draw.text((phone_x1+32, phone_y1+114), "Hey! Did you check the timeline?", fill="#E9EDEF")

    # 1.5s - 8s: Bubble appears
    if t >= 1.2:
        # Floating bubble slides in from right
        bubble_target_x = phone_x2 - 45
        bubble_x = min(bubble_target_x, phone_x2 + 20 - int((t - 1.2) * 180)) if t < 1.8 else bubble_target_x
        bubble_y = phone_y1 + 180

        # Pulse effect
        pulse = 2 * math.sin(t * 5)
        r = 22 + pulse
        draw.ellipse([bubble_x - r, bubble_y - r, bubble_x + r, bubble_y + r], fill="#00A884", outline="#FFFFFF", width=2)
        draw.text((bubble_x - 6, bubble_y - 8), "📝", fill="#FFFFFF")

    # 2.8s - 8s: Scratchpad expands
    if t >= 2.8:
        pad_progress = min(1.0, (t - 2.8) * 3) # smooth spring scale
        pad_w = int((phone_x2 - phone_x1 - 30) * pad_progress)
        pad_h = int(320 * pad_progress)
        pad_cx = (phone_x1 + phone_x2) // 2
        pad_cy = phone_y1 + 330
        px1 = pad_cx - pad_w // 2
        px2 = pad_cx + pad_w // 2
        py1 = pad_cy - pad_h // 2
        py2 = pad_cy + pad_h // 2

        if pad_w > 20 and pad_h > 20:
            draw.rounded_rectangle([px1, py1, px2, py2], radius=18, fill="#121B22", outline="#2A3942", width=2)

            if pad_progress >= 0.95:
                # Scratchpad Header
                draw.text((px1 + 16, py1 + 14), "📌  Alex Turner", fill="#E9EDEF")
                draw.text((px2 - 80, py1 + 14), "✓ Saved", fill="#00A884")

                # Tabs
                draw.rounded_rectangle([px1 + 16, py1 + 40, px1 + 150, py1 + 68], radius=8, fill="#00A884")
                draw.text((px1 + 35, py1 + 47), "Checklist (1/2)", fill="#FFFFFF")
                draw.rounded_rectangle([px1 + 160, py1 + 40, px2 - 16, py1 + 68], radius=8, fill="#1F2C34")
                draw.text((px1 + 195, py1 + 47), "Notes", fill="#8696A0")

                # Item 1 (Done)
                draw.rounded_rectangle([px1 + 16, py1 + 80, px2 - 16, py1 + 115], radius=8, fill="#1F2C34")
                draw.rectangle([px1 + 26, py1 + 90, px1 + 42, py1 + 106], fill="#00A884")
                draw.text((px1 + 30, py1 + 91), "✓", fill="#FFFFFF")
                draw.text((px1 + 52, py1 + 92), "Discuss Q4 project timeline", fill="#8696A0")

                # Item 2 (Ticks at t >= 4.5)
                draw.rounded_rectangle([px1 + 16, py1 + 125, px2 - 16, py1 + 160], radius=8, fill="#1F2C34")
                is_item2_done = t >= 4.5
                if is_item2_done:
                    draw.rectangle([px1 + 26, py1 + 135, px1 + 42, py1 + 151], fill="#00A884")
                    draw.text((px1 + 30, py1 + 136), "✓", fill="#FFFFFF")
                    draw.text((px1 + 52, py1 + 137), "Confirm invoice payment", fill="#8696A0")
                else:
                    draw.rectangle([px1 + 26, py1 + 135, px1 + 42, py1 + 151], fill=None, outline="#00A884", width=2)
                    draw.text((px1 + 52, py1 + 137), "Confirm invoice payment", fill="#E9EDEF")

                # Typing input bar at bottom
                draw.rounded_rectangle([px1 + 16, py2 - 50, px2 - 80, py2 - 15], radius=8, fill="#1F2C34")
                if t >= 5.8:
                    draw.text((px1 + 26, py2 - 38), "Send $5,000 contract", fill="#E9EDEF")
                else:
                    draw.text((px1 + 26, py2 - 38), "Add talking point...", fill="#8696A0")

                draw.rounded_rectangle([px2 - 70, py2 - 50, px2 - 16, py2 - 15], radius=8, fill="#00A884")
                draw.text((px2 - 55, py2 - 38), "Add", fill="#FFFFFF")

    # Bottom Reel CTA Banner
    draw.rounded_rectangle([30, H - 95, W - 30, H - 50], radius=14, fill="#00A884")
    draw.text((W//2 - 120, H - 78), "ChatNotes  •  Free on GitHub", fill="#070B0E")

    frames.append(img)

out_path = r"C:\Users\muthy\.gemini\antigravity\scratch\ChatNotes\screenshots\chatnotes_reel.gif"
frames[0].save(out_path, save_all=True, append_images=frames[1:], duration=int(1000/FPS), loop=0, optimize=True)
print("Reel animation created successfully at:", out_path)
