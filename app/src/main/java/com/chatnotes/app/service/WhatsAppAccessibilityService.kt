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

    companion object {
        private const val TAG = "ChatNotes_A11y"
        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PKG = "com.whatsapp.w4b"
        private const val MY_PKG = "com.chatnotes.app"
        private const val HIDE_DELAY_MS = 600L
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

        // We are inside WhatsApp: check for active conversation
        val detectedContact = extractContactName()

        if (!detectedContact.isNullOrBlank()) {
            val wasPendingHide = (pendingHideRunnable != null)
            cancelPendingHide()

            val isNewChat = (detectedContact != currentActiveContact)
            val isWindowStateChange = (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)

            if (isNewChat || wasPendingHide || isWindowStateChange) {
                currentActiveContact = detectedContact
                Log.d(TAG, "Triggering overlay for: $detectedContact (isNewChat=$isNewChat, wasPendingHide=$wasPendingHide, windowChanged=$isWindowStateChange)")
                FloatingOverlayService.showOverlay(this, detectedContact)
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
        val lower = text.lowercase().trim()
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

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        cancelPendingHide()
        currentActiveContact = null
        FloatingOverlayService.hideOverlay(this)
    }
}
