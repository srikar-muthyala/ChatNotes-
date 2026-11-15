package com.chatnotes.app.service

import android.accessibilityservice.AccessibilityService
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

        // Verify overlay permission
        if (!Settings.canDrawOverlays(this)) return

        val packageName = event.packageName?.toString() ?: return

        // 1. If user is in ANY app other than WhatsApp, hide the overlay immediately
        if (packageName != WHATSAPP_PKG && packageName != WHATSAPP_BUSINESS_PKG) {
            if (currentActiveContact != null) {
                currentActiveContact = null
                FloatingOverlayService.hideOverlay(this)
            }
            return
        }

        // 2. We are inside WhatsApp: check whether an actual chat conversation is active
        val detectedContact = extractContactNameFromChat()

        if (!detectedContact.isNullOrBlank()) {
            if (detectedContact != currentActiveContact) {
                currentActiveContact = detectedContact
                Log.d(TAG, "Active chat: $detectedContact")
                FloatingOverlayService.showOverlay(this, detectedContact)
            }
        } else {
            // In WhatsApp, but on the main list / settings / search -> hide overlay
            if (currentActiveContact != null) {
                currentActiveContact = null
                FloatingOverlayService.hideOverlay(this)
            }
        }
    }

    private fun extractContactNameFromChat(): String? {
        val rootNode = rootInActiveWindow ?: return null
        try {
            // First verify we are inside an actual chat conversation:
            // An active chat conversation almost always contains the message input field or back button
            val hasChatInput = findNodeByTextOrHint(rootNode, listOf("type a message", "message", "message..."))
            val hasVoiceRecord = findNodeByDescription(rootNode, listOf("voice message", "record voice message", "voice"))

            // Strategy 1: Known WhatsApp conversation title IDs
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

            // Strategy 2: If we are in a chat screen, search the top action bar for the contact name
            if (hasChatInput || hasVoiceRecord) {
                val foundTitle = scanToolbarChild(rootNode)
                if (foundTitle != null && !isIgnoredTitle(foundTitle)) {
                    return foundTitle
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in extractContactNameFromChat: ${e.message}")
        }
        return null
    }

    private fun scanToolbarChild(node: AccessibilityNodeInfo): String? {
        val className = node.className?.toString() ?: ""
        if (className.contains("TextView", ignoreCase = true)) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank() && !isIgnoredTitle(text)) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                // The contact title is located in the top header
                if (bounds.top < 320 && bounds.bottom > 40 && bounds.left > 60) {
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

    private fun findNodeByTextOrHint(root: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        for (keyword in keywords) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
            if (!nodes.isNullOrEmpty()) return true
        }
        return false
    }

    private fun findNodeByDescription(root: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        return searchContentDescription(root, keywords)
    }

    private fun searchContentDescription(node: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        for (keyword in keywords) {
            if (desc.contains(keyword)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (searchContentDescription(child, keywords)) return true
        }
        return false
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
                lower == "ask meta ai or search" ||
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
        currentActiveContact = null
        FloatingOverlayService.hideOverlay(this)
    }
}
