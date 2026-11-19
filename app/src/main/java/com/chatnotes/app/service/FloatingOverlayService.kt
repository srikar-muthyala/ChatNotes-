package com.chatnotes.app.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chatnotes.app.R
import com.chatnotes.app.data.ChecklistItem
import com.chatnotes.app.data.ContactNotesData
import com.chatnotes.app.data.NotesRepository
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var repository: NotesRepository

    private var bubbleView: View? = null
    private var scratchpadView: View? = null
    private var starredPopupView: View? = null
    private var isStarredPopupAttached = false
    private var autoCapturePopupView: View? = null
    private var isAutoCaptureAttached = false

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var scratchpadParams: WindowManager.LayoutParams
    private lateinit var starredPopupParams: WindowManager.LayoutParams
    private lateinit var autoCaptureParams: WindowManager.LayoutParams

    private var currentContactName: String = "Unknown"
    private var currentData: ContactNotesData? = null
    private var activeTabIsChecklist = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dismissPopupRunnable = Runnable { hideStarredPopup() }
    private val resetCopyFeedbackRunnable = Runnable { resetCopyFeedback() }
    private val dismissAutoCaptureRunnable = Runnable { hideAutoCapturePrompt() }
    private val parkBubbleRunnable = Runnable { parkBubbleToEdge() }
    private var isBubbleParked = false

    private var clipboardManager: ClipboardManager? = null
    private var lastSelfCopiedText: String? = null
    private var lastCapturedClipText: String? = null
    private var pendingCapturedText: String = ""

    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    private var activeStarredItems: List<ChecklistItem> = emptyList()
    private var currentStarredIndex: Int = 0

    private var lastChatSessionContact: String? = null
    private var hasShownStarredInCurrentSession: Boolean = false

    private var lastDeletedItem: ChecklistItem? = null
    private var lastDeletedPosition: Int = -1
    private var lastDeletedItemsList: List<ChecklistItem>? = null
    private val dismissUndoRunnable = Runnable { hideUndoBanner() }

    // Bubble UI elements
    private var ivBubbleIcon: ImageView? = null
    private var tvBubbleInitial: TextView? = null
    private var tvBubbleBadge: TextView? = null
    private var ivBubbleStarBadge: ImageView? = null

    // Scratchpad UI elements
    private var rvChecklist: RecyclerView? = null
    private var checklistAdapter: ChecklistAdapter? = null
    private var etFreeformNotes: EditText? = null
    private var btnTabChecklist: TextView? = null
    private var btnTabNotes: TextView? = null
    private var layoutChecklistSection: View? = null
    private var layoutNotesSection: View? = null
    private var etNewChecklistItem: EditText? = null
    private var tvEmptyChecklist: TextView? = null

    companion object {
        const val ACTION_SHOW = "com.chatnotes.app.ACTION_SHOW"
        const val ACTION_HIDE = "com.chatnotes.app.ACTION_HIDE"
        const val ACTION_AUTO_CAPTURE = "com.chatnotes.app.ACTION_AUTO_CAPTURE"
        const val EXTRA_CONTACT = "extra_contact_name"
        const val EXTRA_CAPTURED_TEXT = "extra_captured_text"
        const val EXTRA_IS_NEW_CHAT = "extra_is_new_chat"
        private const val CHANNEL_ID = "chatnotes_overlay_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "chatnotes_overlay_prefs"
        private const val PREF_BUBBLE_Y = "bubble_y_pos"
        private const val DEFAULT_BUBBLE_Y = 350
        private const val PARK_INACTIVITY_DELAY_MS = 4000L

        @Volatile
        var isScratchpadOpen = false

        fun showOverlay(context: Context, contactName: String, isNewChat: Boolean = false) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_CONTACT, contactName)
                putExtra(EXTRA_IS_NEW_CHAT, isNewChat)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun triggerAutoCapture(context: Context, contactName: String, text: String) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_AUTO_CAPTURE
                putExtra(EXTRA_CONTACT, contactName)
                putExtra(EXTRA_CAPTURED_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun hideOverlay(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        repository = NotesRepository.getInstance(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipChangedListener)
        startForeground(NOTIFICATION_ID, createNotification())
        createBubbleView()
        createStarredPopupView()
        createAutoCapturePopupView()
        createScratchpadView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_STICKY
        when (action) {
            ACTION_SHOW -> {
                val contact = intent.getStringExtra(EXTRA_CONTACT) ?: "WhatsApp Chat"
                val isNewChat = intent.getBooleanExtra(EXTRA_IS_NEW_CHAT, false)
                if (!isScratchpadOpen) {
                    showBubble()
                }
                loadContact(contact, triggerStarredPopup = isNewChat)
            }
            ACTION_AUTO_CAPTURE -> {
                val contact = intent.getStringExtra(EXTRA_CONTACT) ?: currentContactName
                val text = intent.getStringExtra(EXTRA_CAPTURED_TEXT) ?: ""
                if (text.isNotBlank()) {
                    if (contact.isNotBlank() && contact != "Unknown" && contact != "WhatsApp Chat") {
                        currentContactName = contact
                        currentData = repository.getContactData(contact)
                    }
                    if (!isScratchpadOpen) {
                        showBubble()
                        mainHandler.postDelayed({
                            showAutoCapturePrompt(text)
                        }, 80L)
                    }
                }
            }
            ACTION_HIDE -> {
                if (!isScratchpadOpen) {
                    hideAll()
                }
            }
        }
        return START_STICKY
    }

    private fun View.bounceClick(onEnd: (() -> Unit)? = null) {
        this.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        this.animate()
            .scaleX(0.85f)
            .scaleY(0.85f)
            .setDuration(75)
            .withEndAction {
                this.animate()
                    .scaleX(1.06f)
                    .scaleY(1.06f)
                    .setDuration(95)
                    .setInterpolator(OvershootInterpolator(1.3f))
                    .withEndAction {
                        this.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(75)
                            .withEndAction {
                                onEnd?.invoke()
                            }
                            .start()
                    }
                    .start()
            }
            .start()
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun getContactInitial(name: String): String? {
        val trimmed = name.trim()
        for (ch in trimmed) {
            if (Character.isLetterOrDigit(ch)) {
                return ch.toString().uppercase()
            }
        }
        return null
    }

    private fun loadContact(name: String, triggerStarredPopup: Boolean = false) {
        val contactChanged = (name != currentContactName)
        if (contactChanged || lastChatSessionContact != name) {
            lastChatSessionContact = name
            hasShownStarredInCurrentSession = false
        }
        currentContactName = name
        currentData = repository.getContactData(name)

        // Contact Initial Monogram on Bubble
        val initial = getContactInitial(name)
        if (!initial.isNullOrEmpty()) {
            tvBubbleInitial?.text = initial
            tvBubbleInitial?.visibility = View.VISIBLE
            ivBubbleIcon?.visibility = View.GONE
        } else {
            tvBubbleInitial?.visibility = View.GONE
            ivBubbleIcon?.visibility = View.VISIBLE
        }

        updateBadge()
        updateBubbleStarBadge()

        if (isScratchpadOpen) {
            updateScratchpadUI()
        } else if (triggerStarredPopup && !hasShownStarredInCurrentSession) {
            hasShownStarredInCurrentSession = true
            // Post so the bubble is measured, positioned, and attached in WindowManager
            mainHandler.removeCallbacks(dismissPopupRunnable)
            mainHandler.postDelayed({
                if (!isScratchpadOpen && bubbleView?.visibility == View.VISIBLE) {
                    showStarredPopupIfNeeded()
                }
            }, 120L)
        }
    }

    private fun updateBadge() {
        val data = currentData ?: return
        // Only count pending (uncompleted) items for the badge
        val count = data.checklist.count { !it.isDone }
        tvBubbleBadge?.apply {
            if (count > 0) {
                text = count.toString()
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
    }

    private fun updateBubbleStarBadge() {
        val data = currentData ?: return
        val hasStarred = data.checklist.any { it.isStarred }
        ivBubbleStarBadge?.visibility = if (hasStarred) View.VISIBLE else View.GONE
    }

    private fun createBubbleView() {
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)
        ivBubbleIcon = bubbleView?.findViewById(R.id.ivBubbleIcon)
        tvBubbleInitial = bubbleView?.findViewById(R.id.tvBubbleInitial)
        tvBubbleBadge = bubbleView?.findViewById(R.id.tvBubbleBadge)
        ivBubbleStarBadge = bubbleView?.findViewById(R.id.ivBubbleStarBadge)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedY = prefs.getInt(PREF_BUBBLE_Y, DEFAULT_BUBBLE_Y)
        val minY = 50.dpToPx()
        val maxY = screenHeight - 120.dpToPx()

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - 180
            y = savedY.coerceIn(minY, maxY)
        }

        setupBubbleTouchListener()
    }

    private fun createStarredPopupView() {
        val inflater = LayoutInflater.from(this)
        starredPopupView = inflater.inflate(R.layout.layout_starred_popup, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        starredPopupParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val popup = starredPopupView ?: return
        val btnDismiss = popup.findViewById<ImageView>(R.id.btnDismissStarredPopup)
        val btnCopy = popup.findViewById<ImageView>(R.id.btnCopyStarred)
        val btnInsert = popup.findViewById<ImageView>(R.id.btnInsertStarred)
        val btnNext = popup.findViewById<ImageView>(R.id.btnNextStarred)
        val tvTicker = popup.findViewById<TextView>(R.id.tvStarredTicker)

        btnDismiss?.setOnClickListener {
            btnDismiss.bounceClick {
                hideStarredPopup()
            }
        }

        btnInsert?.setOnClickListener {
            btnInsert.bounceClick {
                val item = activeStarredItems.getOrNull(currentStarredIndex)
                val textToInsert = item?.text ?: popup.findViewById<TextView>(R.id.tvStarredValue)?.text?.toString() ?: ""
                if (textToInsert.isNotBlank()) {
                    val inserted = WhatsAppAccessibilityService.instance?.insertTextIntoWhatsApp(textToInsert) ?: false
                    val tvHeader = popup.findViewById<TextView>(R.id.tvStarredHeader)

                    if (inserted) {
                        btnInsert.setImageResource(R.drawable.ic_check)
                        btnInsert.setColorFilter(Color.parseColor("#25D366"))
                        btnInsert.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120).withEndAction {
                            btnInsert.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                        }.start()

                        tvHeader?.text = "INSERTED INTO WHATSAPP!"
                        tvHeader?.setTextColor(Color.parseColor("#25D366"))

                        mainHandler.removeCallbacks(resetCopyFeedbackRunnable)
                        mainHandler.postDelayed(resetCopyFeedbackRunnable, 2500L)

                        mainHandler.removeCallbacks(dismissPopupRunnable)
                        mainHandler.postDelayed(dismissPopupRunnable, 2800L)
                    } else {
                        // Fallback to clipboard if accessibility service couldn't locate input field
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        lastSelfCopiedText = textToInsert
                        val clip = ClipData.newPlainText("ChatNotes Starred", textToInsert)
                        clipboard.setPrimaryClip(clip)

                        btnInsert.setImageResource(R.drawable.ic_check)
                        btnInsert.setColorFilter(Color.parseColor("#25D366"))
                        tvHeader?.text = "COPIED (TAP PASTE IN CHAT)"
                        tvHeader?.setTextColor(Color.parseColor("#25D366"))

                        mainHandler.removeCallbacks(resetCopyFeedbackRunnable)
                        mainHandler.postDelayed(resetCopyFeedbackRunnable, 2500L)

                        mainHandler.removeCallbacks(dismissPopupRunnable)
                        mainHandler.postDelayed(dismissPopupRunnable, 4500L)
                    }
                }
            }
        }

        btnCopy?.setOnClickListener {
            btnCopy.bounceClick {
                val item = activeStarredItems.getOrNull(currentStarredIndex)
                val textToCopy = item?.text ?: popup.findViewById<TextView>(R.id.tvStarredValue)?.text?.toString() ?: ""
                if (textToCopy.isNotBlank()) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    lastSelfCopiedText = textToCopy
                    val clip = ClipData.newPlainText("ChatNotes Starred", textToCopy)
                    clipboard.setPrimaryClip(clip)

                    btnCopy.setImageResource(R.drawable.ic_check)
                    btnCopy.setColorFilter(Color.parseColor("#25D366"))
                    btnCopy.animate().scaleX(1.3f).scaleY(1.3f).setDuration(120).withEndAction {
                        btnCopy.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    }.start()

                    val tvHeader = popup.findViewById<TextView>(R.id.tvStarredHeader)
                    tvHeader?.text = "COPIED TO MESSAGE BAR!"
                    tvHeader?.setTextColor(Color.parseColor("#25D366"))

                    mainHandler.removeCallbacks(resetCopyFeedbackRunnable)
                    mainHandler.postDelayed(resetCopyFeedbackRunnable, 2000L)

                    mainHandler.removeCallbacks(dismissPopupRunnable)
                    mainHandler.postDelayed(dismissPopupRunnable, 4500L)
                }
            }
        }

        val cycleAction = View.OnClickListener { v ->
            v?.bounceClick {
                cycleNextStarredItem()
            }
        }
        btnNext?.setOnClickListener(cycleAction)
        tvTicker?.setOnClickListener(cycleAction)

        setupStarredPopupTouchListener()
    }

    private fun setupStarredPopupTouchListener() {
        val popup = starredPopupView ?: return
        var touchDownX = 0f
        var touchDownY = 0f
        var isSwiping = false

        popup.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.rawX
                    touchDownY = event.rawY
                    isSwiping = false
                    mainHandler.removeCallbacks(dismissPopupRunnable)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchDownX
                    val dy = event.rawY - touchDownY
                    if (abs(dx) > 12.dpToPx() && abs(dx) > abs(dy) * 1.3f) {
                        isSwiping = true
                        v.translationX = dx * 0.45f
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - touchDownX
                    val dy = event.rawY - touchDownY
                    val screenWidth = resources.displayMetrics.widthPixels
                    val isBubbleOnRight = (bubbleParams.x + 25.dpToPx()) >= screenWidth / 2
                    val threshold = 32.dpToPx()

                    // Swipe towards the docked screen edge/bubble dismisses (Flick-to-Dismiss)
                    val isDismissSwipe = if (isBubbleOnRight) (dx > threshold) else (dx < -threshold)
                    // Swipe away from the bubble cycles to next starred note
                    val isCycleSwipe = if (isBubbleOnRight) (dx < -threshold) else (dx > threshold)

                    v.animate().translationX(0f).setDuration(140).start()

                    if (isDismissSwipe) {
                        v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        hideStarredPopup()
                    } else if (isCycleSwipe && activeStarredItems.size > 1) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        cycleNextStarredItem()
                    } else if (!isSwiping && abs(dx) < 10.dpToPx() && abs(dy) < 10.dpToPx()) {
                        // Regular tap on popup body: open scratchpad
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        hideStarredPopup()
                        openScratchpad()
                    } else {
                        mainHandler.postDelayed(dismissPopupRunnable, 5000L)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.animate().translationX(0f).setDuration(140).start()
                    mainHandler.postDelayed(dismissPopupRunnable, 5000L)
                    true
                }
                else -> false
            }
        }
    }

    private fun resetCopyFeedback() {
        val popup = starredPopupView ?: return
        val tvHeader = popup.findViewById<TextView>(R.id.tvStarredHeader) ?: return
        val btnCopy = popup.findViewById<ImageView>(R.id.btnCopyStarred) ?: return
        val btnInsert = popup.findViewById<ImageView>(R.id.btnInsertStarred) ?: return
        tvHeader.text = "STARRED NOTE"
        tvHeader.setTextColor(Color.parseColor("#FFB800"))
        btnCopy.setImageResource(R.drawable.ic_copy)
        btnCopy.setColorFilter(Color.parseColor("#8696A0"))
        btnInsert.setImageResource(R.drawable.ic_insert_chat)
        btnInsert.setColorFilter(Color.parseColor("#8696A0"))
    }

    private fun cycleNextStarredItem() {
        if (activeStarredItems.size <= 1) return
        currentStarredIndex = (currentStarredIndex + 1) % activeStarredItems.size
        updateStarredPopupContent(animated = true)
        mainHandler.removeCallbacks(dismissPopupRunnable)
        mainHandler.postDelayed(dismissPopupRunnable, 6000L)
    }

    private fun updateStarredPopupContent(animated: Boolean) {
        val popup = starredPopupView ?: return
        val item = activeStarredItems.getOrNull(currentStarredIndex) ?: return
        val tvValue = popup.findViewById<TextView>(R.id.tvStarredValue)
        val tvTicker = popup.findViewById<TextView>(R.id.tvStarredTicker)
        val btnNext = popup.findViewById<ImageView>(R.id.btnNextStarred)

        if (activeStarredItems.size > 1) {
            tvTicker?.text = "${currentStarredIndex + 1}/${activeStarredItems.size}"
            tvTicker?.visibility = View.VISIBLE
            btnNext?.visibility = View.VISIBLE
        } else {
            tvTicker?.visibility = View.GONE
            btnNext?.visibility = View.GONE
        }

        if (animated) {
            tvValue?.animate()
                ?.alpha(0f)
                ?.translationY(-6.dpToPx().toFloat())
                ?.setDuration(90)
                ?.withEndAction {
                    tvValue.text = item.text
                    tvValue.translationY = 6.dpToPx().toFloat()
                    tvValue.animate()
                        ?.alpha(1f)
                        ?.translationY(0f)
                        ?.setDuration(130)
                        ?.start()
                }?.start()
        } else {
            tvValue?.text = item.text
            tvValue?.alpha = 1f
            tvValue?.translationY = 0f
        }
    }

    private fun showStarredPopupIfNeeded() {
        val data = currentData ?: return
        val pendingStarred = data.checklist.filter { it.isStarred && !it.isDone }
        activeStarredItems = if (pendingStarred.isNotEmpty()) pendingStarred else data.checklist.filter { it.isStarred }

        if (activeStarredItems.isNotEmpty()) {
            currentStarredIndex = 0
            showStarredPopup()
        } else {
            hideStarredPopup()
        }
    }

    private fun showStarredPopup() {
        val popup = starredPopupView ?: return
        val bubble = bubbleView ?: return
        if (bubble.visibility != View.VISIBLE) return

        if (bubble.isAttachedToWindow == false) {
            try {
                windowManager.addView(bubble, bubbleParams)
            } catch (ignored: Exception) {}
        }

        mainHandler.removeCallbacks(dismissPopupRunnable)
        resetCopyFeedback()
        updateStarredPopupContent(animated = false)

        val screenWidth = resources.displayMetrics.widthPixels
        popup.measure(
            View.MeasureSpec.makeMeasureSpec((screenWidth * 0.76).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = popup.measuredWidth
        val popupHeight = popup.measuredHeight
        val bubbleSize = 50.dpToPx()

        val isBubbleOnRight = (bubbleParams.x + bubbleSize / 2) >= screenWidth / 2

        if (isBubbleOnRight) {
            starredPopupParams.x = max(12.dpToPx(), bubbleParams.x - popupWidth - 8.dpToPx())
            popup.pivotX = popupWidth.toFloat()
        } else {
            starredPopupParams.x = min(screenWidth - popupWidth - 12.dpToPx(), bubbleParams.x + bubbleSize + 8.dpToPx())
            popup.pivotX = 0f
        }
        starredPopupParams.y = bubbleParams.y + (bubbleSize - popupHeight) / 2
        popup.pivotY = popupHeight / 2f

        if (!isStarredPopupAttached) {
            try {
                windowManager.addView(popup, starredPopupParams)
                isStarredPopupAttached = true
            } catch (e: Exception) {
                isStarredPopupAttached = true
            }
        } else {
            try {
                windowManager.updateViewLayout(popup, starredPopupParams)
            } catch (e: Exception) {
                try {
                    windowManager.addView(popup, starredPopupParams)
                    isStarredPopupAttached = true
                } catch (ignored: Exception) {}
            }
        }

        popup.visibility = View.VISIBLE
        popup.translationX = 0f
        popup.alpha = 0f
        popup.scaleX = 0.0f
        popup.scaleY = 0.0f
        popup.animate()
            .alpha(1f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()

        // Auto-dismiss after 6.5 seconds
        mainHandler.postDelayed(dismissPopupRunnable, 6500L)
    }

    private fun hideStarredPopup() {
        mainHandler.removeCallbacks(dismissPopupRunnable)
        mainHandler.removeCallbacks(resetCopyFeedbackRunnable)
        val popup = starredPopupView ?: return
        if (isStarredPopupAttached && popup.visibility == View.VISIBLE) {
            popup.animate()
                .alpha(0f)
                .scaleX(0.0f)
                .scaleY(0.0f)
                .setDuration(180)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    popup.visibility = View.GONE
                    popup.translationX = 0f
                }
                .start()
        }
    }

    private fun createAutoCapturePopupView() {
        val inflater = LayoutInflater.from(this)
        autoCapturePopupView = inflater.inflate(R.layout.layout_auto_capture_prompt, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        autoCaptureParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val prompt = autoCapturePopupView ?: return
        val btnConfirm = prompt.findViewById<TextView>(R.id.btnConfirmAddCapture)
        val btnDismiss = prompt.findViewById<ImageView>(R.id.btnDismissCapture)

        btnDismiss?.setOnClickListener {
            btnDismiss.bounceClick {
                hideAutoCapturePrompt()
            }
        }

        btnConfirm?.setOnClickListener {
            btnConfirm.bounceClick {
                if (pendingCapturedText.isNotBlank()) {
                    val data = currentData ?: repository.getContactData(currentContactName)
                    val newItem = ChecklistItem(text = pendingCapturedText, isDone = false, isStarred = false)
                    data.checklist.add(newItem)
                    repository.saveContactData(data)
                    currentData = data

                    val tvHeader = prompt.findViewById<TextView>(R.id.tvCaptureHeader)
                    tvHeader?.text = "ADDED TO CHECKLIST!"
                    tvHeader?.setTextColor(Color.parseColor("#25D366"))
                    btnConfirm.visibility = View.GONE

                    updateBadge()
                    updateTabLabels()

                    mainHandler.removeCallbacks(dismissAutoCaptureRunnable)
                    mainHandler.postDelayed({ hideAutoCapturePrompt() }, 900L)
                }
            }
        }
    }

    private fun onClipboardChanged() {
        // Only scan when overlay bubble is active and scratchpad is not open
        if (bubbleView?.visibility != View.VISIBLE || isScratchpadOpen) return
        val clip = clipboardManager?.primaryClip ?: return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0)?.text?.toString()?.trim() ?: return
        if (text.isBlank()) return

        // Prevent echo if ChatNotes copied it itself
        if (text == lastSelfCopiedText) return

        // Prevent repeating the same captured text multiple times
        if (text == lastCapturedClipText) return

        // Ignore excessively long text (> 350 chars) or single characters
        if (text.length > 350 || text.length < 2) return

        lastCapturedClipText = text
        pendingCapturedText = text

        showAutoCapturePrompt(text)
    }

    private fun showAutoCapturePrompt(text: String) {
        val prompt = autoCapturePopupView ?: return
        val bubble = bubbleView ?: return
        if (bubble.visibility != View.VISIBLE || isScratchpadOpen) return

        // Dismiss starred popup to avoid overlapping
        hideStarredPopup()

        mainHandler.removeCallbacks(dismissAutoCaptureRunnable)

        val tvHeader = prompt.findViewById<TextView>(R.id.tvCaptureHeader)
        val tvSnippet = prompt.findViewById<TextView>(R.id.tvCaptureSnippet)
        val btnConfirm = prompt.findViewById<TextView>(R.id.btnConfirmAddCapture)

        lastCapturedClipText = text
        pendingCapturedText = text

        val contactFirst = currentContactName.split(" ").firstOrNull() ?: currentContactName
        val displayName = if (contactFirst.isBlank() || contactFirst.equals("Unknown", ignoreCase = true) || contactFirst.equals("WhatsApp Chat", ignoreCase = true)) "THIS CHAT" else contactFirst.uppercase()
        tvHeader?.text = "ADD TO $displayName'S CHECKLIST?"
        tvHeader?.setTextColor(Color.parseColor("#00A884"))
        tvSnippet?.text = text.replace("\n", " ").trim()
        btnConfirm?.visibility = View.VISIBLE

        val screenWidth = resources.displayMetrics.widthPixels
        prompt.measure(
            View.MeasureSpec.makeMeasureSpec((screenWidth * 0.76).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val promptWidth = prompt.measuredWidth
        val promptHeight = prompt.measuredHeight
        val bubbleSize = 50.dpToPx()

        val isBubbleOnRight = (bubbleParams.x + bubbleSize / 2) >= screenWidth / 2

        if (isBubbleOnRight) {
            autoCaptureParams.x = max(12.dpToPx(), bubbleParams.x - promptWidth - 8.dpToPx())
            prompt.pivotX = promptWidth.toFloat()
        } else {
            autoCaptureParams.x = min(screenWidth - promptWidth - 12.dpToPx(), bubbleParams.x + bubbleSize + 8.dpToPx())
            prompt.pivotX = 0f
        }
        autoCaptureParams.y = bubbleParams.y + (bubbleSize - promptHeight) / 2
        prompt.pivotY = promptHeight / 2f

        if (!isAutoCaptureAttached) {
            try {
                windowManager.addView(prompt, autoCaptureParams)
                isAutoCaptureAttached = true
            } catch (e: Exception) {
                isAutoCaptureAttached = true
            }
        } else {
            try {
                windowManager.updateViewLayout(prompt, autoCaptureParams)
            } catch (e: Exception) {
                try {
                    windowManager.addView(prompt, autoCaptureParams)
                    isAutoCaptureAttached = true
                } catch (ignored: Exception) {}
            }
        }

        prompt.visibility = View.VISIBLE
        prompt.translationX = 0f
        prompt.alpha = 0f
        prompt.scaleX = 0.0f
        prompt.scaleY = 0.0f
        prompt.animate()
            .alpha(1f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(260)
            .setInterpolator(OvershootInterpolator(1.15f))
            .start()

        mainHandler.postDelayed(dismissAutoCaptureRunnable, 4500L)
    }

    private fun hideAutoCapturePrompt() {
        mainHandler.removeCallbacks(dismissAutoCaptureRunnable)
        val prompt = autoCapturePopupView ?: return
        if (isAutoCaptureAttached && prompt.visibility == View.VISIBLE) {
            prompt.animate()
                .alpha(0f)
                .scaleX(0.0f)
                .scaleY(0.0f)
                .setDuration(180)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    prompt.visibility = View.GONE
                    prompt.translationX = 0f
                }
                .start()
        }
    }

    private fun setupBubbleTouchListener() {
        val view = bubbleView ?: return
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    hideStarredPopup()
                    hideAutoCapturePrompt()
                    mainHandler.removeCallbacks(parkBubbleRunnable)
                    if (isBubbleParked) {
                        unparkBubble(animated = true)
                    }
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    view.animate().scaleX(1.15f).scaleY(1.15f).alpha(1.0f).setDuration(120).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isClick = false
                    }
                    bubbleParams.x = initialX + dx
                    val screenHeight = resources.displayMetrics.heightPixels
                    val minY = 50.dpToPx()
                    val maxY = screenHeight - 120.dpToPx()
                    bubbleParams.y = (initialY + dy).coerceIn(minY, maxY)

                    if (view.isAttachedToWindow) {
                        windowManager.updateViewLayout(view, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    if (isClick) {
                        openScratchpad()
                    } else {
                        snapBubbleToEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun snapBubbleToEdge() {
        val view = bubbleView ?: return
        val screenWidth = resources.displayMetrics.widthPixels
        val bubbleWidth = if (view.width > 0) view.width else 50.dpToPx()
        val currentX = bubbleParams.x
        val targetX = if (currentX + (bubbleWidth / 2) < screenWidth / 2) 16.dpToPx() else (screenWidth - bubbleWidth - 16.dpToPx())

        // Smart Y-Position Memory: Save vertical height across sessions
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(PREF_BUBBLE_Y, bubbleParams.y).apply()

        val animator = ValueAnimator.ofInt(currentX, targetX)
        animator.duration = 240
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            bubbleParams.x = animation.animatedValue as Int
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, bubbleParams)
            }
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                scheduleBubbleParking()
            }
        })
        animator.start()
    }

    private fun scheduleBubbleParking() {
        mainHandler.removeCallbacks(parkBubbleRunnable)
        if (!isScratchpadOpen && bubbleView?.visibility == View.VISIBLE && !isBubbleParked) {
            mainHandler.postDelayed(parkBubbleRunnable, PARK_INACTIVITY_DELAY_MS)
        }
    }

    private fun parkBubbleToEdge() {
        val view = bubbleView ?: return
        if (isScratchpadOpen || !view.isAttachedToWindow || view.visibility != View.VISIBLE || isBubbleParked) return
        if (starredPopupView?.visibility == View.VISIBLE || autoCapturePopupView?.visibility == View.VISIBLE) {
            scheduleBubbleParking()
            return
        }

        isBubbleParked = true
        val screenWidth = resources.displayMetrics.widthPixels
        val bubbleWidth = if (view.width > 0) view.width else 50.dpToPx()
        val isBubbleOnRight = (bubbleParams.x + bubbleWidth / 2) >= screenWidth / 2

        val parkOffset = (bubbleWidth * 0.40f).toInt() // 40% into screen margin
        val currentX = bubbleParams.x
        val targetX = if (isBubbleOnRight) (screenWidth - bubbleWidth + parkOffset) else (-parkOffset)

        val animator = ValueAnimator.ofInt(currentX, targetX)
        animator.duration = 320
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            bubbleParams.x = animation.animatedValue as Int
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, bubbleParams)
            }
        }
        animator.start()

        view.animate().alpha(0.72f).scaleX(0.92f).scaleY(0.92f).setDuration(320).start()
    }

    private fun unparkBubble(animated: Boolean = true) {
        mainHandler.removeCallbacks(parkBubbleRunnable)
        val view = bubbleView ?: return
        if (!isBubbleParked) {
            scheduleBubbleParking()
            return
        }

        isBubbleParked = false
        val screenWidth = resources.displayMetrics.widthPixels
        val bubbleWidth = if (view.width > 0) view.width else 50.dpToPx()
        val isBubbleOnRight = (bubbleParams.x + bubbleWidth / 2) >= screenWidth / 2
        val currentX = bubbleParams.x
        val targetX = if (isBubbleOnRight) (screenWidth - bubbleWidth - 16.dpToPx()) else 16.dpToPx()

        if (animated) {
            val animator = ValueAnimator.ofInt(currentX, targetX)
            animator.duration = 220
            animator.interpolator = OvershootInterpolator(1.1f)
            animator.addUpdateListener { animation ->
                bubbleParams.x = animation.animatedValue as Int
                if (view.isAttachedToWindow) {
                    windowManager.updateViewLayout(view, bubbleParams)
                }
            }
            animator.start()
            view.animate().alpha(1.0f).scaleX(1.0f).scaleY(1.0f).setDuration(220).start()
        } else {
            bubbleParams.x = targetX
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, bubbleParams)
            }
            view.alpha = 1.0f
            view.scaleX = 1.0f
            view.scaleY = 1.0f
        }

        scheduleBubbleParking()
    }

    private fun createScratchpadView() {
        val inflater = LayoutInflater.from(this)
        scratchpadView = inflater.inflate(R.layout.layout_floating_scratchpad, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        scratchpadParams = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.90).toInt(),
            (resources.displayMetrics.heightPixels * 0.58).toInt(),
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        }

        initScratchpadWidgets()
    }

    private fun initScratchpadWidgets() {
        val pad = scratchpadView ?: return

        rvChecklist = pad.findViewById(R.id.rvChecklist)
        tvEmptyChecklist = pad.findViewById(R.id.tvEmptyChecklist)
        btnTabChecklist = pad.findViewById(R.id.btnTabChecklist)
        btnTabNotes = pad.findViewById(R.id.btnTabNotes)
        layoutChecklistSection = pad.findViewById(R.id.layoutChecklistSection)
        layoutNotesSection = pad.findViewById(R.id.layoutNotesSection)
        etNewChecklistItem = pad.findViewById(R.id.etNewChecklistItem)
        val btnAdd = pad.findViewById<TextView>(R.id.btnAddChecklistItem)

        rvChecklist?.layoutManager = LinearLayoutManager(this)
        checklistAdapter = ChecklistAdapter(
            items = mutableListOf(),
            onCheckToggled = { _, _ ->
                currentData?.let { repository.saveContactData(it) }
                updateBadge()
                updateTabLabels()
            },
            onStarToggled = { _, _ ->
                currentData?.let {
                    repository.saveContactData(it)
                    updateBubbleStarBadge()
                }
            },
            onDeleteClicked = { index ->
                currentData?.let { data ->
                    if (index in data.checklist.indices) {
                        val removed = data.checklist.removeAt(index)
                        repository.saveContactData(data)
                        checklistAdapter?.updateItems(data.checklist)
                        checkEmptyState()
                        updateBadge()
                        updateBubbleStarBadge()
                        updateTabLabels()
                        showUndoDeleteBanner(removed, index)
                    }
                }
            }
        )
        rvChecklist?.adapter = checklistAdapter
        rvChecklist?.let { setupChecklistSwipeGestures(it) }

        val btnUndo = pad.findViewById<TextView>(R.id.btnUndoDelete)
        btnUndo?.setOnClickListener {
            btnUndo.bounceClick {
                val data = currentData ?: return@bounceClick
                var restoredPosition = -1

                if (lastDeletedItemsList != null) {
                    data.checklist.clear()
                    data.checklist.addAll(lastDeletedItemsList!!)
                    lastDeletedItemsList = null
                } else if (lastDeletedItem != null) {
                    val targetPos = lastDeletedPosition.coerceIn(0, data.checklist.size)
                    data.checklist.add(targetPos, lastDeletedItem!!)
                    restoredPosition = targetPos
                    lastDeletedItem = null
                    lastDeletedPosition = -1
                }

                repository.saveContactData(data)
                checklistAdapter?.updateItems(data.checklist)
                checkEmptyState()
                updateBadge()
                updateBubbleStarBadge()
                updateTabLabels()
                hideUndoBanner()

                if (restoredPosition >= 0) {
                    rvChecklist?.smoothScrollToPosition(restoredPosition)
                }
            }
        }

        val addAction = {
            val text = etNewChecklistItem?.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                val data = currentData ?: ContactNotesData(contactName = currentContactName)
                data.checklist.add(ChecklistItem(text = text, isDone = false))
                repository.saveContactData(data)
                checklistAdapter?.updateItems(data.checklist)
                etNewChecklistItem?.setText("")
                checkEmptyState()
                updateBadge()
                updateBubbleStarBadge()
                updateTabLabels()
                rvChecklist?.smoothScrollToPosition(data.checklist.size - 1)
            }
        }

        btnAdd.setOnClickListener {
            btnAdd.bounceClick { addAction() }
        }
        etNewChecklistItem?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addAction()
                true
            } else false
        }

        val btnClear = pad.findViewById<TextView>(R.id.btnClearAllChecklist)
        btnClear?.setOnClickListener {
            btnClear.bounceClick {
                currentData?.let { data ->
                    if (data.checklist.isNotEmpty()) {
                        val backedUpList = ArrayList(data.checklist)
                        data.checklist.clear()
                        repository.saveContactData(data)
                        checklistAdapter?.updateItems(data.checklist)
                        checkEmptyState()
                        updateBadge()
                        updateBubbleStarBadge()
                        updateTabLabels()
                        showUndoClearAllBanner(backedUpList)
                    }
                }
            }
        }

        // Freeform Notes
        etFreeformNotes = pad.findViewById(R.id.etFreeformNotes)
        val tvCharCount = pad.findViewById<TextView>(R.id.tvNotesCharCount)

        etFreeformNotes?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString() ?: ""
                currentData?.let { data ->
                    data.freeformNotes = text
                    repository.saveContactData(data)
                }
                tvCharCount.text = "${text.length} characters"
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Tabs with smooth bouncy feedback
        btnTabChecklist?.setOnClickListener {
            btnTabChecklist?.bounceClick {
                if (!activeTabIsChecklist) {
                    activeTabIsChecklist = true
                    switchTabWithAnimation(showChecklist = true)
                }
            }
        }

        btnTabNotes?.setOnClickListener {
            btnTabNotes?.bounceClick {
                if (activeTabIsChecklist) {
                    activeTabIsChecklist = false
                    switchTabWithAnimation(showChecklist = false)
                }
            }
        }

        // Close button with animated shrink and bounce
        val btnClose = pad.findViewById<View>(R.id.btnCloseScratchpad)
        btnClose?.setOnClickListener {
            btnClose.bounceClick {
                closeScratchpad()
            }
        }

        // Theme toggle with 180 degree spin animation
        val btnTheme = pad.findViewById<View>(R.id.btnToggleTheme)
        btnTheme?.setOnClickListener {
            btnTheme.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            btnTheme.animate()
                .rotationBy(180f)
                .scaleX(0.82f)
                .scaleY(0.82f)
                .setDuration(120)
                .withEndAction {
                    btnTheme.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }
                .start()
            val newIsDark = !repository.isDarkTheme()
            repository.setDarkTheme(newIsDark)
            applyTheme(newIsDark)
        }

        applyTheme(repository.isDarkTheme())
    }

    private fun setupChecklistSwipeGestures(rv: RecyclerView) {
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val data = currentData ?: return
                if (position !in data.checklist.indices) return

                if (direction == ItemTouchHelper.RIGHT) {
                    // Swipe Right: Toggle completed
                    val item = data.checklist[position]
                    item.isDone = !item.isDone
                    repository.saveContactData(data)
                    viewHolder.itemView.translationX = 0f
                    checklistAdapter?.notifyItemChanged(position)
                    viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    updateBadge()
                    updateTabLabels()
                } else if (direction == ItemTouchHelper.LEFT) {
                    // Swipe Left: Delete item
                    val removed = data.checklist.removeAt(position)
                    repository.saveContactData(data)
                    checklistAdapter?.updateItems(data.checklist)
                    viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    checkEmptyState()
                    updateBadge()
                    updateBubbleStarBadge()
                    updateTabLabels()
                    showUndoDeleteBanner(removed, position)
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val background = Paint()

                if (dX > 0) {
                    // Swiping Right: Green background for complete toggle
                    background.color = Color.parseColor("#00A884")
                    c.drawRoundRect(
                        itemView.left.toFloat(),
                        itemView.top.toFloat() + 2.dpToPx(),
                        itemView.left.toFloat() + dX,
                        itemView.bottom.toFloat() - 4.dpToPx(),
                        8.dpToPx().toFloat(),
                        8.dpToPx().toFloat(),
                        background
                    )
                    val icon = ContextCompat.getDrawable(this@FloatingOverlayService, R.drawable.ic_check)
                    icon?.let {
                        val iconSize = 22.dpToPx()
                        val iconMargin = (itemView.height - iconSize) / 2
                        val iconTop = itemView.top + iconMargin
                        val iconBottom = iconTop + iconSize
                        val iconLeft = itemView.left + 16.dpToPx()
                        val iconRight = iconLeft + iconSize
                        it.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        it.setTint(Color.WHITE)
                        it.draw(c)
                    }
                } else if (dX < 0) {
                    // Swiping Left: Red background for delete
                    background.color = Color.parseColor("#EA0038")
                    c.drawRoundRect(
                        itemView.right.toFloat() + dX,
                        itemView.top.toFloat() + 2.dpToPx(),
                        itemView.right.toFloat(),
                        itemView.bottom.toFloat() - 4.dpToPx(),
                        8.dpToPx().toFloat(),
                        8.dpToPx().toFloat(),
                        background
                    )
                    val icon = ContextCompat.getDrawable(this@FloatingOverlayService, R.drawable.ic_delete)
                    icon?.let {
                        val iconSize = 22.dpToPx()
                        val iconMargin = (itemView.height - iconSize) / 2
                        val iconTop = itemView.top + iconMargin
                        val iconBottom = iconTop + iconSize
                        val iconRight = itemView.right - 16.dpToPx()
                        val iconLeft = iconRight - iconSize
                        it.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        it.setTint(Color.WHITE)
                        it.draw(c)
                    }
                }

                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        ItemTouchHelper(swipeHandler).attachToRecyclerView(rv)
    }

    private fun showUndoDeleteBanner(item: ChecklistItem, position: Int) {
        lastDeletedItem = item
        lastDeletedPosition = position
        lastDeletedItemsList = null

        val pad = scratchpadView ?: return
        val banner = pad.findViewById<View>(R.id.layoutUndoBanner) ?: return
        val tvMsg = pad.findViewById<TextView>(R.id.tvUndoMessage) ?: return

        mainHandler.removeCallbacks(dismissUndoRunnable)

        val snippet = if (item.text.length > 20) item.text.take(18) + "…" else item.text
        tvMsg.text = "Deleted \"$snippet\""

        banner.visibility = View.VISIBLE
        banner.alpha = 0f
        banner.translationY = 24.dpToPx().toFloat()
        banner.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

        mainHandler.postDelayed(dismissUndoRunnable, 4500L)
    }

    private fun showUndoClearAllBanner(items: List<ChecklistItem>) {
        lastDeletedItem = null
        lastDeletedPosition = -1
        lastDeletedItemsList = items

        val pad = scratchpadView ?: return
        val banner = pad.findViewById<View>(R.id.layoutUndoBanner) ?: return
        val tvMsg = pad.findViewById<TextView>(R.id.tvUndoMessage) ?: return

        mainHandler.removeCallbacks(dismissUndoRunnable)

        tvMsg.text = "Cleared ${items.size} item${if (items.size > 1) "s" else ""}"

        banner.visibility = View.VISIBLE
        banner.alpha = 0f
        banner.translationY = 24.dpToPx().toFloat()
        banner.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()

        mainHandler.postDelayed(dismissUndoRunnable, 4500L)
    }

    private fun hideUndoBanner() {
        mainHandler.removeCallbacks(dismissUndoRunnable)
        val pad = scratchpadView ?: return
        val banner = pad.findViewById<View>(R.id.layoutUndoBanner) ?: return
        if (banner.visibility == View.VISIBLE) {
            banner.animate()
                .alpha(0f)
                .translationY(24.dpToPx().toFloat())
                .setDuration(160)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    banner.visibility = View.GONE
                    banner.translationY = 0f
                }
                .start()
        }
    }

    private fun switchTabWithAnimation(showChecklist: Boolean) {
        val isDark = repository.isDarkTheme()
        hideUndoBanner()
        if (showChecklist) {
            btnTabChecklist?.setBackgroundResource(R.drawable.bg_tab_pill_active)
            btnTabChecklist?.setTextColor(Color.WHITE)

            btnTabNotes?.setBackgroundResource(if (isDark) R.drawable.bg_tab_pill_inactive_dark else R.drawable.bg_tab_pill_inactive_light)
            btnTabNotes?.setTextColor(if (isDark) Color.parseColor("#8696A0") else Color.parseColor("#667781"))

            layoutNotesSection?.animate()?.alpha(0f)?.setDuration(120)?.withEndAction {
                layoutNotesSection?.visibility = View.GONE
                layoutChecklistSection?.visibility = View.VISIBLE
                layoutChecklistSection?.alpha = 0f
                layoutChecklistSection?.animate()?.alpha(1f)?.setDuration(160)?.start()
            }?.start()
        } else {
            btnTabNotes?.setBackgroundResource(R.drawable.bg_tab_pill_active)
            btnTabNotes?.setTextColor(Color.WHITE)

            btnTabChecklist?.setBackgroundResource(if (isDark) R.drawable.bg_tab_pill_inactive_dark else R.drawable.bg_tab_pill_inactive_light)
            btnTabChecklist?.setTextColor(if (isDark) Color.parseColor("#8696A0") else Color.parseColor("#667781"))

            layoutChecklistSection?.animate()?.alpha(0f)?.setDuration(120)?.withEndAction {
                layoutChecklistSection?.visibility = View.GONE
                layoutNotesSection?.visibility = View.VISIBLE
                layoutNotesSection?.alpha = 0f
                layoutNotesSection?.animate()?.alpha(1f)?.setDuration(160)?.start()
            }?.start()
        }
    }

    private fun updateScratchpadUI() {
        val pad = scratchpadView ?: return
        val data = currentData ?: return

        pad.findViewById<TextView>(R.id.tvContactName).text = data.contactName
        checklistAdapter?.updateItems(data.checklist)
        checkEmptyState()
        etFreeformNotes?.setText(data.freeformNotes)
        updateTabLabels()
    }

    private fun checkEmptyState() {
        val items = currentData?.checklist ?: emptyList()
        val btnClearAll = scratchpadView?.findViewById<View>(R.id.btnClearAllChecklist)
        if (items.isEmpty()) {
            rvChecklist?.visibility = View.GONE
            tvEmptyChecklist?.visibility = View.VISIBLE
            btnClearAll?.visibility = View.GONE
        } else {
            rvChecklist?.visibility = View.VISIBLE
            tvEmptyChecklist?.visibility = View.GONE
            btnClearAll?.visibility = View.VISIBLE
        }
    }

    private fun updateTabLabels() {
        val data = currentData ?: return
        val total = data.checklist.size
        val done = data.checklist.count { it.isDone }
        btnTabChecklist?.text = "Checklist ($done/$total)"
    }

    private fun applyTheme(isDark: Boolean) {
        val pad = scratchpadView ?: return
        val container = pad.findViewById<View>(R.id.scratchpadContainer)
        val tvContact = pad.findViewById<TextView>(R.id.tvContactName)
        val btnClose = pad.findViewById<ImageView>(R.id.btnCloseScratchpad)
        val btnTheme = pad.findViewById<ImageView>(R.id.btnToggleTheme)
        val ivHeader = pad.findViewById<ImageView>(R.id.ivHeaderIcon)

        if (isDark) {
            container.setBackgroundResource(R.drawable.bg_scratchpad_card_dark)
            tvContact.setTextColor(Color.parseColor("#E9EDEF"))
            btnClose.setColorFilter(Color.parseColor("#E9EDEF"))
            btnTheme.setColorFilter(Color.parseColor("#E9EDEF"))
            ivHeader.setColorFilter(Color.parseColor("#00A884"))

            etFreeformNotes?.setTextColor(Color.parseColor("#E9EDEF"))
            etFreeformNotes?.setHintTextColor(Color.parseColor("#8696A0"))
            etNewChecklistItem?.setBackgroundResource(R.drawable.bg_tab_pill_inactive_dark)
            etNewChecklistItem?.setTextColor(Color.parseColor("#E9EDEF"))
            etNewChecklistItem?.setHintTextColor(Color.parseColor("#8696A0"))
            tvEmptyChecklist?.setTextColor(Color.parseColor("#8696A0"))
            pad.findViewById<TextView>(R.id.btnClearAllChecklist)?.setTextColor(Color.parseColor("#8696A0"))

            if (!activeTabIsChecklist) {
                btnTabChecklist?.setBackgroundResource(R.drawable.bg_tab_pill_inactive_dark)
                btnTabChecklist?.setTextColor(Color.parseColor("#8696A0"))
            }
            if (activeTabIsChecklist) {
                btnTabNotes?.setBackgroundResource(R.drawable.bg_tab_pill_inactive_dark)
                btnTabNotes?.setTextColor(Color.parseColor("#8696A0"))
            }
        } else {
            container.setBackgroundResource(R.drawable.bg_scratchpad_card_light)
            tvContact.setTextColor(Color.parseColor("#111B21"))
            btnClose.setColorFilter(Color.parseColor("#111B21"))
            btnTheme.setColorFilter(Color.parseColor("#111B21"))
            ivHeader.setColorFilter(Color.parseColor("#00A884"))

            etFreeformNotes?.setTextColor(Color.parseColor("#111B21"))
            etFreeformNotes?.setHintTextColor(Color.parseColor("#667781"))
            etNewChecklistItem?.setBackgroundResource(R.drawable.bg_tab_pill_inactive_light)
            etNewChecklistItem?.setTextColor(Color.parseColor("#111B21"))
            etNewChecklistItem?.setHintTextColor(Color.parseColor("#667781"))
            tvEmptyChecklist?.setTextColor(Color.parseColor("#667781"))
            pad.findViewById<TextView>(R.id.btnClearAllChecklist)?.setTextColor(Color.parseColor("#667781"))

            if (!activeTabIsChecklist) {
                btnTabChecklist?.setBackgroundResource(R.drawable.bg_tab_pill_inactive_light)
                btnTabChecklist?.setTextColor(Color.parseColor("#667781"))
            }
            if (activeTabIsChecklist) {
                btnTabNotes?.setBackgroundResource(R.drawable.bg_tab_pill_inactive_light)
                btnTabNotes?.setTextColor(Color.parseColor("#667781"))
            }
        }
        checklistAdapter?.setDarkTheme(isDark)
    }

    private fun showBubble() {
        if (bubbleView?.isAttachedToWindow == false) {
            try {
                windowManager.addView(bubbleView, bubbleParams)
            } catch (ignored: Exception) {}
        }
        bubbleView?.visibility = View.VISIBLE
        unparkBubble(animated = false)
        scheduleBubbleParking()
    }

    private fun openScratchpad() {
        isScratchpadOpen = true
        hasShownStarredInCurrentSession = true
        mainHandler.removeCallbacks(parkBubbleRunnable)
        hideStarredPopup()
        hideAutoCapturePrompt()

        bubbleView?.animate()?.scaleX(0.4f)?.scaleY(0.4f)?.alpha(0f)?.setDuration(150)?.withEndAction {
            bubbleView?.visibility = View.GONE
            bubbleView?.scaleX = 1f
            bubbleView?.scaleY = 1f
            bubbleView?.alpha = 1f
        }?.start()

        updateScratchpadUI()
        val pad = scratchpadView ?: return

        if (pad.isAttachedToWindow == false) {
            windowManager.addView(pad, scratchpadParams)
        }
        pad.visibility = View.VISIBLE
        pad.alpha = 0f
        pad.scaleX = 0.78f
        pad.scaleY = 0.78f
        pad.animate()
            .alpha(1f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(240)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    private fun closeScratchpad() {
        hideUndoBanner()
        val pad = scratchpadView ?: return
        pad.animate()
            .alpha(0f)
            .scaleX(0.82f)
            .scaleY(0.82f)
            .setDuration(180)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                isScratchpadOpen = false
                pad.visibility = View.GONE
                bubbleView?.visibility = View.VISIBLE
                bubbleView?.scaleX = 0.5f
                bubbleView?.scaleY = 0.5f
                bubbleView?.alpha = 0f
                bubbleView?.animate()
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.alpha(1f)
                    ?.setDuration(200)
                    ?.setInterpolator(OvershootInterpolator())
                    ?.start()
                updateBadge()
                updateBubbleStarBadge()
                scheduleBubbleParking()
            }
            .start()
    }

    private fun hideAll() {
        isScratchpadOpen = false
        lastChatSessionContact = null
        hasShownStarredInCurrentSession = false
        lastDeletedItem = null
        lastDeletedPosition = -1
        lastDeletedItemsList = null
        mainHandler.removeCallbacks(dismissUndoRunnable)
        hideUndoBanner()
        mainHandler.removeCallbacks(dismissPopupRunnable)
        mainHandler.removeCallbacks(resetCopyFeedbackRunnable)
        mainHandler.removeCallbacks(dismissAutoCaptureRunnable)
        mainHandler.removeCallbacks(parkBubbleRunnable)
        isBubbleParked = false
        starredPopupView?.animate()?.cancel()
        starredPopupView?.visibility = View.GONE
        autoCapturePopupView?.animate()?.cancel()
        autoCapturePopupView?.visibility = View.GONE
        bubbleView?.visibility = View.GONE
        scratchpadView?.visibility = View.GONE
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ChatNotes Active Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows WhatsApp floating notes when active"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ChatNotes is active")
            .setContentText("Listening for active WhatsApp chats to load your notes")
            .setSmallIcon(R.drawable.ic_note)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager?.removePrimaryClipChangedListener(clipChangedListener)
        mainHandler.removeCallbacks(dismissPopupRunnable)
        mainHandler.removeCallbacks(dismissAutoCaptureRunnable)
        mainHandler.removeCallbacks(dismissUndoRunnable)
        if (isStarredPopupAttached && starredPopupView != null) {
            windowManager.removeView(starredPopupView)
            isStarredPopupAttached = false
        }
        if (isAutoCaptureAttached && autoCapturePopupView != null) {
            windowManager.removeView(autoCapturePopupView)
            isAutoCaptureAttached = false
        }
        if (bubbleView?.isAttachedToWindow == true) {
            windowManager.removeView(bubbleView)
        }
        if (scratchpadView?.isAttachedToWindow == true) {
            windowManager.removeView(scratchpadView)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class ChecklistAdapter(
    private var items: MutableList<ChecklistItem>,
    private val onCheckToggled: (Int, Boolean) -> Unit,
    private val onStarToggled: (Int, Boolean) -> Unit,
    private val onDeleteClicked: (Int) -> Unit
) : RecyclerView.Adapter<ChecklistAdapter.ViewHolder>() {

    private var isDark = true

    fun setDarkTheme(dark: Boolean) {
        isDark = dark
        notifyDataSetChanged()
    }

    fun updateItems(newItems: List<ChecklistItem>) {
        items = newItems.toMutableList()
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cbDone: CheckBox = view.findViewById(R.id.cbItemDone)
        val tvText: TextView = view.findViewById(R.id.tvItemText)
        val btnStar: ImageView = view.findViewById(R.id.btnStarItem)
        val btnDelete: ImageView = view.findViewById(R.id.btnDeleteItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_checklist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvText.text = item.text

        // Checkbox state
        holder.cbDone.setOnCheckedChangeListener(null)
        holder.cbDone.isChecked = item.isDone
        if (item.isDone) {
            holder.tvText.paintFlags = holder.tvText.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            holder.tvText.alpha = 0.45f
        } else {
            holder.tvText.paintFlags = holder.tvText.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            holder.tvText.alpha = 1.0f
        }

        holder.tvText.setTextColor(
            if (isDark) Color.parseColor("#E9EDEF") else Color.parseColor("#111B21")
        )

        holder.cbDone.setOnCheckedChangeListener { _, isChecked ->
            item.isDone = isChecked
            if (isChecked) {
                holder.tvText.paintFlags = holder.tvText.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                holder.tvText.animate().alpha(0.45f).setDuration(160).start()
            } else {
                holder.tvText.paintFlags = holder.tvText.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.tvText.animate().alpha(1.0f).setDuration(160).start()
            }
            onCheckToggled(position, isChecked)
        }

        // Star button state
        if (item.isStarred) {
            holder.btnStar.setImageResource(R.drawable.ic_star_filled)
            holder.btnStar.setColorFilter(Color.parseColor("#FFB800"))
        } else {
            holder.btnStar.setImageResource(R.drawable.ic_star_outline)
            holder.btnStar.setColorFilter(
                if (isDark) Color.parseColor("#8696A0") else Color.parseColor("#667781")
            )
        }

        holder.btnStar.setOnClickListener {
            holder.btnStar.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            item.isStarred = !item.isStarred
            if (item.isStarred) {
                holder.btnStar.setImageResource(R.drawable.ic_star_filled)
                holder.btnStar.setColorFilter(Color.parseColor("#FFB800"))
                holder.btnStar.animate().scaleX(1.35f).scaleY(1.35f).setDuration(110).withEndAction {
                    holder.btnStar.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
                }.start()
            } else {
                holder.btnStar.setImageResource(R.drawable.ic_star_outline)
                holder.btnStar.setColorFilter(
                    if (isDark) Color.parseColor("#8696A0") else Color.parseColor("#667781")
                )
                holder.btnStar.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
                    holder.btnStar.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                }.start()
            }
            onStarToggled(position, item.isStarred)
        }

        holder.btnDelete.setOnClickListener {
            holder.btnDelete.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            holder.btnDelete.animate()
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(70)
                .withEndAction {
                    holder.btnDelete.animate().scaleX(1.0f).scaleY(1.0f).setDuration(70).start()
                    onDeleteClicked(position)
                }
                .start()
        }
    }

    override fun getItemCount(): Int = items.size
}
