package com.chatnotes.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WhatsAppAccessibilityService : AccessibilityService() {

    private var currentActiveContact: String? = null

    companion object {
        private const val TAG = "ChatNotes_A11y"
        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PKG = "com.whatsapp.w4b"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Verify overlay permission before attempting to show overlay
        if (!Settings.canDrawOverlays(this)) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName != WHATSAPP_PKG && packageName != WHATSAPP_BUSINESS_PKG) {
            // User switched away from WhatsApp
            if (currentActiveContact != null) {
                currentActiveContact = null
                FloatingOverlayService.hideOverlay(this)
            }
            return
        }

        // We are inside WhatsApp
        val detectedContact = extractContactNameFromWindow()
        if (!detectedContact.isNullOrBlank()) {
            if (detectedContact != currentActiveContact) {
                currentActiveContact = detectedContact
                Log.d(TAG, "Active WhatsApp chat detected: $detectedContact")
                FloatingOverlayService.showOverlay(this, detectedContact)
            }
        }
    }

    private fun extractContactNameFromWindow(): String? {
        val rootNode = rootInActiveWindow ?: return null
        try {
            // Strategy 1: Check known WhatsApp contact title view IDs
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

            // Strategy 2: Scan the top toolbar area for contact name TextView
            val foundTitle = scanToolbarChild(rootNode)
            if (foundTitle != null && !isIgnoredTitle(foundTitle)) {
                return foundTitle
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error extracting contact name: ${e.message}")
        }
        return null
    }

    private fun scanToolbarChild(node: AccessibilityNodeInfo): String? {
        val className = node.className?.toString() ?: ""
        if (className.contains("TextView", ignoreCase = true)) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank() && !isIgnoredTitle(text)) {
                // Ensure it's placed near top of the screen (ActionBar/Toolbar)
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.top < 300 && bounds.bottom > 50 && bounds.left > 80) {
                    return text
                }
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val result = scanToolbarChild(child)
            if (result != null) return result
        }
        return null
    }

    private fun isIgnoredTitle(text: String): Boolean {
        val lower = text.lowercase()
        return lower == "whatsapp" ||
                lower == "chats" ||
                lower == "status" ||
                lower == "calls" ||
                lower == "updates" ||
                lower == "communities" ||
                lower == "search" ||
                lower == "search…" ||
                lower.startsWith("type a message")
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        currentActiveContact = null
        FloatingOverlayService.hideOverlay(this)
    }
}
