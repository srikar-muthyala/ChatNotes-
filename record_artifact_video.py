import os
import time
import shutil
from playwright.sync_api import sync_playwright

html_url = "file:///C:/Users/muthy/.gemini/antigravity/brain/bdc62293-3025-4e6f-8069-c57069c1e8df/reel_animation.html"
download_dir = r"C:\Users\muthy\Downloads"
temp_video_dir = os.path.join(download_dir, "_temp_rec")
os.makedirs(temp_video_dir, exist_ok=True)

print("Launching browser to record the artifact animation...")

with sync_playwright() as p:
    # Use installed Chrome or Edge
    try:
        browser = p.chromium.launch(channel="msedge", headless=True)
    except Exception:
        browser = p.chromium.launch(channel="chrome", headless=True)
    context = browser.new_context(
        viewport={"width": 480, "height": 850},
        device_scale_factor=2,
        record_video_dir=temp_video_dir,
        record_video_size={"width": 720, "height": 1280}
    )
    page = context.new_page()
    page.goto(html_url)

    # Wait for the full 13 seconds of animation to play completely
    print("Recording animation in progress (14 seconds)...")
    time.sleep(14)

    page.close()
    context.close()
    browser.close()

# Find the recorded video file
rec_files = [os.path.join(temp_video_dir, f) for f in os.listdir(temp_video_dir) if f.endswith(".webm")]
if rec_files:
    latest_rec = rec_files[0]
    final_output = os.path.join(download_dir, "ChatNotes_Instagram_Reel.webm")
    shutil.move(latest_rec, final_output)
    print("SUCCESS: Recorded video saved to:", final_output)

    # Now let's also convert it to MP4 using imageio_ffmpeg so they have both webm and mp4!
    try:
        import imageio_ffmpeg
        ffmpeg_exe = imageio_ffmpeg.get_ffmpeg_exe()
        final_mp4 = os.path.join(download_dir, "ChatNotes_Instagram_Reel.mp4")
        cmd = f'"{ffmpeg_exe}" -y -i "{final_output}" -c:v libx264 -pix_fmt yuv420p "{final_mp4}"'
        print("Converting to MP4 format...")
        os.system(cmd)
        print("SUCCESS: MP4 video saved to:", final_mp4)
    except Exception as e:
        print("MP4 conversion note:", e)

    # Clean up temp dir
    try:
        shutil.rmtree(temp_video_dir)
    except:
        pass
else:
    print("No video was captured.")
