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
import android.graphics.Color
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

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var scratchpadParams: WindowManager.LayoutParams
    private lateinit var starredPopupParams: WindowManager.LayoutParams

    private var currentContactName: String = "Unknown"
    private var currentData: ContactNotesData? = null
    private var activeTabIsChecklist = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private val dismissPopupRunnable = Runnable { hideStarredPopup() }
    private val resetCopyFeedbackRunnable = Runnable { resetCopyFeedback() }

    private var activeStarredItems: List<ChecklistItem> = emptyList()
    private var currentStarredIndex: Int = 0

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
        const val EXTRA_CONTACT = "extra_contact_name"
        private const val CHANNEL_ID = "chatnotes_overlay_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isScratchpadOpen = false

        fun showOverlay(context: Context, contactName: String) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_SHOW
                putExtra(EXTRA_CONTACT, contactName)
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
        startForeground(NOTIFICATION_ID, createNotification())
        createBubbleView()
        createStarredPopupView()
        createScratchpadView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_STICKY
        when (action) {
            ACTION_SHOW -> {
                val contact = intent.getStringExtra(EXTRA_CONTACT) ?: "WhatsApp Chat"
                if (!isScratchpadOpen) {
                    showBubble()
                }
                loadContact(contact)
            }
            ACTION_HIDE -> {
                if (!isScratchpadOpen) {
                    hideAll()
                }
            }
        }
        return START_STICKY
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

    private fun loadContact(name: String) {
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
        } else {
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
        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - 180
            y = 350
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
        val btnNext = popup.findViewById<ImageView>(R.id.btnNextStarred)
        val tvTicker = popup.findViewById<TextView>(R.id.tvStarredTicker)

        btnDismiss?.setOnClickListener {
            btnDismiss.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            hideStarredPopup()
        }

        btnCopy?.setOnClickListener {
            val item = activeStarredItems.getOrNull(currentStarredIndex)
            val textToCopy = item?.text ?: popup.findViewById<TextView>(R.id.tvStarredValue)?.text?.toString() ?: ""
            if (textToCopy.isNotBlank()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ChatNotes Starred", textToCopy)
                clipboard.setPrimaryClip(clip)

                btnCopy.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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

        val cycleAction = View.OnClickListener {
            btnNext?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            cycleNextStarredItem()
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
        tvHeader.text = "STARRED NOTE"
        tvHeader.setTextColor(Color.parseColor("#FFB800"))
        btnCopy.setImageResource(R.drawable.ic_copy)
        btnCopy.setColorFilter(Color.parseColor("#8696A0"))
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
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    view.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                        isClick = false
                    }
                    bubbleParams.x = initialX + dx
                    bubbleParams.y = initialY + dy
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
        val currentX = bubbleParams.x
        val targetX = if (currentX + (view.width / 2) < screenWidth / 2) 20 else (screenWidth - view.width - 20)

        val animator = ValueAnimator.ofInt(currentX, targetX)
        animator.duration = 250
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            bubbleParams.x = animation.animatedValue as Int
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, bubbleParams)
            }
        }
        animator.start()
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
                        data.checklist.removeAt(index)
                        repository.saveContactData(data)
                        checklistAdapter?.updateItems(data.checklist)
                        checkEmptyState()
                        updateBadge()
                        updateBubbleStarBadge()
                        updateTabLabels()
                    }
                }
            }
        )
        rvChecklist?.adapter = checklistAdapter

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

        btnAdd.setOnClickListener { addAction() }
        etNewChecklistItem?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addAction()
                true
            } else false
        }

        pad.findViewById<TextView>(R.id.btnClearAllChecklist)?.setOnClickListener {
            currentData?.let { data ->
                if (data.checklist.isNotEmpty()) {
                    data.checklist.clear()
                    repository.saveContactData(data)
                    checklistAdapter?.updateItems(data.checklist)
                    checkEmptyState()
                    updateBadge()
                    updateBubbleStarBadge()
                    updateTabLabels()
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

        // Tabs with smooth crossfade
        btnTabChecklist?.setOnClickListener {
            if (!activeTabIsChecklist) {
                activeTabIsChecklist = true
                switchTabWithAnimation(showChecklist = true)
            }
        }

        btnTabNotes?.setOnClickListener {
            if (activeTabIsChecklist) {
                activeTabIsChecklist = false
                switchTabWithAnimation(showChecklist = false)
            }
        }

        // Close button with animated shrink
        pad.findViewById<View>(R.id.btnCloseScratchpad).setOnClickListener {
            closeScratchpad()
        }

        // Theme toggle
        pad.findViewById<View>(R.id.btnToggleTheme).setOnClickListener {
            val newIsDark = !repository.isDarkTheme()
            repository.setDarkTheme(newIsDark)
            applyTheme(newIsDark)
        }

        applyTheme(repository.isDarkTheme())
    }

    private fun switchTabWithAnimation(showChecklist: Boolean) {
        val isDark = repository.isDarkTheme()
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
            windowManager.addView(bubbleView, bubbleParams)
        }
        bubbleView?.visibility = View.VISIBLE
    }

    private fun openScratchpad() {
        isScratchpadOpen = true
        hideStarredPopup()

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
                    ?.withEndAction {
                        showStarredPopupIfNeeded()
                    }
                    ?.start()
                updateBadge()
                updateBubbleStarBadge()
            }
            .start()
    }

    private fun hideAll() {
        isScratchpadOpen = false
        mainHandler.removeCallbacks(dismissPopupRunnable)
        mainHandler.removeCallbacks(resetCopyFeedbackRunnable)
        starredPopupView?.animate()?.cancel()
        starredPopupView?.visibility = View.GONE
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
        mainHandler.removeCallbacks(dismissPopupRunnable)
        if (isStarredPopupAttached && starredPopupView != null) {
            windowManager.removeView(starredPopupView)
            isStarredPopupAttached = false
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
            item.isStarred = !item.isStarred
            if (item.isStarred) {
                holder.btnStar.setImageResource(R.drawable.ic_star_filled)
                holder.btnStar.setColorFilter(Color.parseColor("#FFB800"))
                holder.btnStar.animate().scaleX(1.3f).scaleY(1.3f).setDuration(110).withEndAction {
                    holder.btnStar.animate().scaleX(1.0f).scaleY(1.0f).setDuration(90).start()
                }.start()
            } else {
                holder.btnStar.setImageResource(R.drawable.ic_star_outline)
                holder.btnStar.setColorFilter(
                    if (isDark) Color.parseColor("#8696A0") else Color.parseColor("#667781")
                )
            }
            onStarToggled(position, item.isStarred)
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClicked(position)
        }
    }

    override fun getItemCount(): Int = items.size
}
