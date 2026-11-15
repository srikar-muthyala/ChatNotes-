package com.chatnotes.app.service

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
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

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var repository: NotesRepository

    private var bubbleView: View? = null
    private var scratchpadView: View? = null

    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var scratchpadParams: WindowManager.LayoutParams

    private var currentContactName: String = "Unknown"
    private var currentData: ContactNotesData? = null
    private var isScratchpadOpen = false
    private var activeTabIsChecklist = true

    // UI elements
    private var rvChecklist: RecyclerView? = null
    private var checklistAdapter: ChecklistAdapter? = null
    private var etFreeformNotes: EditText? = null
    private var tvBubbleBadge: TextView? = null
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
        createScratchpadView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_STICKY
        when (action) {
            ACTION_SHOW -> {
                val contact = intent.getStringExtra(EXTRA_CONTACT) ?: "WhatsApp Chat"
                loadContact(contact)
                showBubble()
            }
            ACTION_HIDE -> {
                hideAll()
            }
        }
        return START_STICKY
    }

    private fun loadContact(name: String) {
        currentContactName = name
        currentData = repository.getContactData(name)
        updateBadge()

        if (isScratchpadOpen) {
            updateScratchpadUI()
        }
    }

    private fun updateBadge() {
        val data = currentData ?: return
        val count = data.checklist.size
        tvBubbleBadge?.apply {
            if (count > 0) {
                text = count.toString()
                visibility = View.VISIBLE
            } else {
                visibility = View.GONE
            }
        }
    }

    private fun createBubbleView() {
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.layout_floating_bubble, null)
        tvBubbleBadge = bubbleView?.findViewById(R.id.tvBubbleBadge)

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
                        // Smoothly snap to the nearest screen edge (like Facebook Messenger)
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
            (resources.displayMetrics.widthPixels * 0.88).toInt(),
            (resources.displayMetrics.heightPixels * 0.62).toInt(),
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
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
            onDeleteClicked = { index ->
                currentData?.let { data ->
                    if (index in data.checklist.indices) {
                        data.checklist.removeAt(index)
                        repository.saveContactData(data)
                        checklistAdapter?.updateItems(data.checklist)
                        checkEmptyState()
                        updateBadge()
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
        if (items.isEmpty()) {
            rvChecklist?.visibility = View.GONE
            tvEmptyChecklist?.visibility = View.VISIBLE
        } else {
            rvChecklist?.visibility = View.VISIBLE
            tvEmptyChecklist?.visibility = View.GONE
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

        if (isDark) {
            container.setBackgroundResource(R.drawable.bg_scratchpad_card_dark)
            tvContact.setTextColor(Color.parseColor("#E9EDEF"))
            etFreeformNotes?.setTextColor(Color.parseColor("#E9EDEF"))
            etFreeformNotes?.setHintTextColor(Color.parseColor("#8696A0"))
            etNewChecklistItem?.setBackgroundResource(R.drawable.bg_tab_pill_inactive_dark)
            etNewChecklistItem?.setTextColor(Color.parseColor("#E9EDEF"))
            etNewChecklistItem?.setHintTextColor(Color.parseColor("#8696A0"))
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
            etFreeformNotes?.setTextColor(Color.parseColor("#111B21"))
            etFreeformNotes?.setHintTextColor(Color.parseColor("#667781"))
            etNewChecklistItem?.setBackgroundResource(R.drawable.bg_tab_pill_inactive_light)
            etNewChecklistItem?.setTextColor(Color.parseColor("#111B21"))
            etNewChecklistItem?.setHintTextColor(Color.parseColor("#667781"))
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
                    ?.start()
                updateBadge()
            }
            .start()
    }

    private fun hideAll() {
        isScratchpadOpen = false
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
        val btnDelete: ImageView = view.findViewById(R.id.btnDeleteItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_checklist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvText.text = item.text
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

        holder.btnDelete.setOnClickListener {
            onDeleteClicked(position)
        }
    }

    override fun getItemCount(): Int = items.size
}
