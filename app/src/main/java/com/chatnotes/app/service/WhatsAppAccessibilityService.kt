package com.chatnotes.app.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppAccessibilityService : AccessibilityService() {

    private var currentActiveContact: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingHideRunnable: Runnable? = null
    private var lastLongClickedMessageText: String? = null

    companion object {
        private const val TAG = "ChatNotes_A11y"
        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PKG = "com.whatsapp.w4b"
        private const val MY_PKG = "com.chatnotes.app"
        private const val HIDE_DELAY_MS = 600L

        var instance: WhatsAppAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "WhatsAppAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!Settings.canDrawOverlays(this)) return

        val packageName = event.packageName?.toString() ?: return

        // Ignore events from our own app, keyboards, and system UI to prevent self-hiding loops!
        if (isIgnoredPackage(packageName)) {
            return
        }

        // If the scratchpad is currently open and user is typing in it, never interrupt!
        if (FloatingOverlayService.isScratchpadOpen) {
            cancelPendingHide()
            if (packageName != WHATSAPP_PKG && packageName != WHATSAPP_BUSINESS_PKG) {
                scheduleHide()
            }
            return
        }

        // If the user navigated away to another app (e.g., Home screen, Chrome, Instagram)
        if (packageName != WHATSAPP_PKG && packageName != WHATSAPP_BUSINESS_PKG) {
            scheduleHide()
            return
        }

        // 1. Capture message text when user long-presses a message to select it
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            val text = extractMessageTextFromEvent(event)
            if (!text.isNullOrBlank() && !isIgnoredTitle(text)) {
                lastLongClickedMessageText = text
                Log.d(TAG, "Captured long-clicked message: $text")
            }
        }

        // 2. Capture message text when text selection changes
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            val text = event.text?.joinToString(" ")?.trim()
            if (!text.isNullOrBlank() && !isIgnoredTitle(text)) {
                lastLongClickedMessageText = text
            }
        }

        // 3. Detect when the user taps WhatsApp's "Copy" action bar button
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val viewId = event.source?.viewIdResourceName ?: ""
            val desc = event.contentDescription?.toString() ?: ""
            val isCopyAction = viewId.contains("menuitem_copy", ignoreCase = true) ||
                    desc.equals("Copy", ignoreCase = true) ||
                    desc.contains("copy", ignoreCase = true) ||
                    desc.equals("Copiar", ignoreCase = true) ||
                    desc.equals("Copier", ignoreCase = true)

            if (isCopyAction) {
                Log.d(TAG, "WhatsApp Copy action tapped!")
                val root = rootInActiveWindow
                val copiedText = (root?.let { findSelectedMessageText(it) } ?: lastLongClickedMessageText)
                val targetContact = currentActiveContact
                if (!copiedText.isNullOrBlank() && targetContact != null) {
                    Log.d(TAG, "Auto-capturing text for contact $targetContact: $copiedText")
                    FloatingOverlayService.triggerAutoCapture(this, targetContact, copiedText)
                }
            }
        }

        // 4. Check if WhatsApp is currently in message selection mode (e.g., "1", "2 selected")
        val rootNode = rootInActiveWindow
        val isSelectionActive = rootNode != null && isSelectionModeActive(rootNode)

        // When selection mode is active, user is selecting messages.
        // DO NOT change currentActiveContact or re-bloom the starred popup!
        if (isSelectionActive) {
            cancelPendingHide()
            return
        }

        // 5. Normal chat detection
        val detectedContact = extractContactName()

        if (!detectedContact.isNullOrBlank()) {
            val wasPendingHide = (pendingHideRunnable != null)
            cancelPendingHide()

            val isNewChat = (detectedContact != currentActiveContact)
            val isWindowStateChange = (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)

            // A chat is considered "newly opened" if we navigated into a different contact
            // or returned to WhatsApp from another app/chat list.
            val shouldTriggerNewChat = isNewChat || wasPendingHide

            if (shouldTriggerNewChat) {
                currentActiveContact = detectedContact
                Log.d(TAG, "Triggering overlay for new chat: $detectedContact")
                FloatingOverlayService.showOverlay(this, detectedContact, isNewChat = true)
            } else if (isWindowStateChange && currentActiveContact == detectedContact) {
                // Keep overlay displayed on in-chat window changes, but DO NOT re-bloom the starred popup!
                FloatingOverlayService.showOverlay(this, detectedContact, isNewChat = false)
            }
        } else {
            // In WhatsApp, but on the main chat list / search / settings
            scheduleHide()
        }
    }

    private fun scheduleHide() {
        if (currentActiveContact == null) return
        if (pendingHideRunnable != null) return

        pendingHideRunnable = Runnable {
            currentActiveContact = null
            FloatingOverlayService.hideOverlay(this)
            pendingHideRunnable = null
            Log.d(TAG, "Overlay hidden")
        }
        mainHandler.postDelayed(pendingHideRunnable!!, HIDE_DELAY_MS)
    }

    private fun cancelPendingHide() {
        pendingHideRunnable?.let {
            mainHandler.removeCallbacks(it)
            pendingHideRunnable = null
        }
    }

    private fun extractContactName(): String? {
        val rootNode = rootInActiveWindow ?: return null
        try {
            // 1. Direct search by WhatsApp conversation contact name IDs
            val knownIds = listOf(
                "com.whatsapp:id/conversation_contact_name",
                "com.whatsapp.w4b:id/conversation_contact_name",
                "com.whatsapp:id/toolbar_title",
                "com.whatsapp.w4b:id/toolbar_title"
            )

            for (id in knownIds) {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        val text = node.text?.toString()?.trim()
                        if (!text.isNullOrBlank() && !isIgnoredTitle(text)) {
                            return text
                        }
                    }
                }
            }

            // 2. Fallback: Search top action bar for the contact TextView
            val foundInToolbar = scanToolbar(rootNode)
            if (foundInToolbar != null && !isIgnoredTitle(foundInToolbar)) {
                return foundInToolbar
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting contact: ${e.message}")
        }
        return null
    }

    private fun scanToolbar(node: AccessibilityNodeInfo): String? {
        val className = node.className?.toString() ?: ""
        if (className.contains("TextView", ignoreCase = true)) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank() && !isIgnoredTitle(text)) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                // WhatsApp contact header is located at the top bar
                if (bounds.top < 350 && bounds.bottom > 40 && bounds.left > 60) {
                    return text
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = scanToolbar(child)
            if (result != null) return result
        }
        return null
    }

    private fun isIgnoredPackage(pkg: String): Boolean {
        return pkg == MY_PKG ||
                pkg == "com.android.systemui" ||
                pkg.contains("inputmethod", ignoreCase = true) ||
                pkg.contains("keyboard", ignoreCase = true) ||
                pkg.contains("gboard", ignoreCase = true) ||
                pkg.contains("swiftkey", ignoreCase = true)
    }

    private fun isIgnoredTitle(text: String): Boolean {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()

        // Message selection counts (e.g., "1", "2", "3", "1 selected", "2 selected", etc.)
        if (lower.matches(Regex("^\\d+$"))) return true
        if (lower.matches(Regex("^\\d+\\s+selected.*"))) return true
        if (lower.matches(Regex("^\\d+\\s+item.*"))) return true
        if (lower.matches(Regex("^\\d+\\s+of\\s+\\d+.*"))) return true

        return lower == "whatsapp" ||
                lower == "chats" ||
                lower == "status" ||
                lower == "calls" ||
                lower == "updates" ||
                lower == "communities" ||
                lower == "search" ||
                lower == "search…" ||
                lower.startsWith("ask meta ai") ||
                lower.startsWith("meta ai") ||
                lower == "unread" ||
                lower == "favourites" ||
                lower == "groups" ||
                lower == "all" ||
                lower.startsWith("type a message") ||
                lower == "message"
    }

    private fun isSelectionModeActive(rootNode: AccessibilityNodeInfo): Boolean {
        // When messages are selected in WhatsApp, contextual action bar displays
        val closeNodes = rootNode.findAccessibilityNodeInfosByViewId("action_mode_close_button")
        if (!closeNodes.isNullOrEmpty()) return true

        val copyNodes = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp:id/menuitem_copy")
        if (!copyNodes.isNullOrEmpty()) return true

        val w4bCopy = rootNode.findAccessibilityNodeInfosByViewId("com.whatsapp.w4b:id/menuitem_copy")
        if (!w4bCopy.isNullOrEmpty()) return true

        return false
    }

    private fun extractMessageTextFromEvent(event: AccessibilityEvent): String? {
        val source = event.source ?: return null
        return extractTextFromNode(source)
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo): String? {
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrBlank() && !isIgnoredTitle(t)) return t

        // Check if this node has message_text children
        val messageIds = listOf(
            "com.whatsapp:id/message_text",
            "com.whatsapp.w4b:id/message_text"
        )
        for (id in messageIds) {
            val nodes = node.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                val found = nodes.firstOrNull()?.text?.toString()?.trim()
                if (!found.isNullOrBlank() && !isIgnoredTitle(found)) return found
            }
        }

        // Recursively check children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childText = extractTextFromNode(child)
            if (!childText.isNullOrBlank()) return childText
        }
        return null
    }

    private fun findSelectedMessageText(root: AccessibilityNodeInfo): String? {
        val messageIds = listOf(
            "com.whatsapp:id/message_text",
            "com.whatsapp.w4b:id/message_text"
        )
        for (id in messageIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                val texts = mutableListOf<String>()
                for (node in nodes) {
                    if (isNodeOrAncestorSelected(node)) {
                        val t = node.text?.toString()?.trim()
                        if (!t.isNullOrBlank() && !isIgnoredTitle(t)) {
                            texts.add(t)
                        }
                    }
                }
                if (texts.isNotEmpty()) {
                    return texts.joinToString("\n")
                }
            }
        }
        return null
    }

    private fun isNodeOrAncestorSelected(node: AccessibilityNodeInfo?): Boolean {
        var curr = node
        var depth = 0
        while (curr != null && depth < 6) {
            if (curr.isSelected) return true
            curr = curr.parent
            depth++
        }
        return false
    }

    fun insertTextIntoWhatsApp(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        try {
            val entryIds = listOf(
                "com.whatsapp:id/entry",
                "com.whatsapp.w4b:id/entry"
            )
            for (id in entryIds) {
                val nodes = rootNode.findAccessibilityNodeInfosByViewId(id)
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        if (node.isEditable || node.isFocusable) {
                            val arguments = android.os.Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    text
                                )
                            }
                            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                            if (success) {
                                val selectionArgs = android.os.Bundle().apply {
                                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                                }
                                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
                                Log.d(TAG, "Successfully inserted text into WhatsApp entry field")
                                return true
                            }
                        }
                    }
                }
            }

            // Fallback: search tree for any editable input node
            val editableNode = findEditableNode(rootNode)
            if (editableNode != null) {
                val arguments = android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        text
                    )
                }
                editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val success = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                if (success) {
                    val selectionArgs = android.os.Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
                    }
                    editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectionArgs)
                    Log.d(TAG, "Successfully inserted text into editable node fallback")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting text into WhatsApp: ${e.message}")
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableNode(child)
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        cancelPendingHide()
        currentActiveContact = null
        FloatingOverlayService.hideOverlay(this)
    }
}
