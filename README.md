# ChatNotes: In-Chat Floating Notes & Checklists for WhatsApp

ChatNotes is a lightweight Android companion utility that displays a floating scratchpad over WhatsApp conversations, giving each contact their own independent **Checklist** and **Freeform Notes**.

---

## Features

- **Unique Per Contact**: Automatically detects the active chat and loads talking points tied to that specific person.
- **Dual Mode**:
  - **Checklist Tab**: Action items and discussion points with checkboxes and strikethroughs.
  - **Notes Tab**: Unstructured text for memos, addresses, links, and background notes.
- **Floating Draggable Bubble**: Renders on the side of the screen like a chat head. Tapping it opens the scratchpad.
- **Badge Counter**: Displays the number of checklist items directly on the floating icon.
- **Dark & Light Theme**: Toggle on-the-fly with instant dynamic styling.
- **100% Private & Offline**: All notes are stored locally on your device with zero cloud tracking or external server transmission.

---

## How to Build the APK Online (Free on GitHub)

You do **not** need Android Studio installed locally. GitHub Actions will compile the `.apk` in the cloud for you:

### Step 1: Create a GitHub Repository
1. Log into your [GitHub account](https://github.com/).
2. Click **New Repository** (give it a name like `chatnotes-android`).
3. Set it to **Private** or **Public**, and leave "Initialize with README" **unchecked**.

### Step 2: Push this Project to GitHub
Open your terminal / PowerShell in this directory:
```bash
git init
git add .
git commit -m "Initial commit of ChatNotes"
git branch -M main
git remote add origin https://github.com/<YOUR_USERNAME>/<YOUR_REPO_NAME>.git
git push -u origin main
```

### Step 3: Download the Compiled APK
1. Go to your repository on GitHub in your web browser.
2. Click on the **Actions** tab at the top.
3. You will see the **Build Android APK** workflow running.
4. Once it finishes (typically 2–3 minutes), click on the completed run.
5. Under **Artifacts**, click **ChatNotes-APK** to download the ready-to-install `.apk` file!

---

## Installing & Granting Permissions on Your Phone

1. Transfer the `.apk` to your phone (or download directly from GitHub on your phone's browser) and tap to install.
2. Open **ChatNotes**:
   - Tap **Appear on Top**: Turn the switch ON for ChatNotes.
   - Tap **Accessibility Service**: In your phone's Accessibility settings, find *ChatNotes* under "Installed Apps" and toggle it ON.
3. Open **WhatsApp** and enter any chat—your floating notes bubble will appear automatically!
