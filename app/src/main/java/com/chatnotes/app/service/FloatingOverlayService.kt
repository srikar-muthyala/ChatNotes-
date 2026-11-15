package com.chatnotes.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.*
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

    // UI elements inside scratchpad
    private var rvChecklist: RecyclerView? = null
    private var checklistAdapter: ChecklistAdapter? = null
    private var etFreeformNotes: EditText? = null
    private var tvBubbleBadge: TextView? = null

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

        // Update badge count
        updateBadge()

        // If scratchpad is currently open, refresh its content
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

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 300
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
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
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
                    if (isClick) {
                        openScratchpad()
                    }
                    true
                }
                else -> false
            }
        }
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
            (resources.displayMetrics.heightPixels * 0.60).toInt(),
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
                        updateBadge()
                        updateTabLabels()
                    }
                }
            }
        )
        rvChecklist?.adapter = checklistAdapter

        val etNewItem = pad.findViewById<EditText>(R.id.etNewChecklistItem)
        val btnAdd = pad.findViewById<Button>(R.id.btnAddChecklistItem)

        val addAction = {
            val text = etNewItem.text.toString().trim()
            if (text.isNotEmpty()) {
                val data = currentData ?: ContactNotesData(contactName = currentContactName)
                data.checklist.add(ChecklistItem(text = text, isDone = false))
                repository.saveContactData(data)
                checklistAdapter?.updateItems(data.checklist)
                etNewItem.setText("")
                updateBadge()
                updateTabLabels()
            }
        }

        btnAdd.setOnClickListener { addAction() }
        etNewItem.setOnEditorActionListener { _, actionId, _ ->
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

        // Tabs
        val btnTabChecklist = pad.findViewById<Button>(R.id.btnTabChecklist)
        val btnTabNotes = pad.findViewById<Button>(R.id.btnTabNotes)
        val layoutChecklist = pad.findViewById<View>(R.id.layoutChecklistSection)
        val layoutNotes = pad.findViewById<View>(R.id.layoutNotesSection)

        btnTabChecklist.setOnClickListener {
            activeTabIsChecklist = true
            layoutChecklist.visibility = View.VISIBLE
            layoutNotes.visibility = View.GONE
        }

        btnTabNotes.setOnClickListener {
            activeTabIsChecklist = false
            layoutChecklist.visibility = View.GONE
            layoutNotes.visibility = View.VISIBLE
        }

        // Close button
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

    private fun updateScratchpadUI() {
        val pad = scratchpadView ?: return
        val data = currentData ?: return

        pad.findViewById<TextView>(R.id.tvContactName).text = data.contactName
        checklistAdapter?.updateItems(data.checklist)
        etFreeformNotes?.setText(data.freeformNotes)
        updateTabLabels()
    }

    private fun updateTabLabels() {
        val pad = scratchpadView ?: return
        val data = currentData ?: return
        val total = data.checklist.size
        val done = data.checklist.count { it.isDone }
        pad.findViewById<Button>(R.id.btnTabChecklist).text = "Checklist ($done/$total)"
    }

    private fun applyTheme(isDark: Boolean) {
        val pad = scratchpadView ?: return
        val container = pad.findViewById<View>(R.id.scratchpadContainer)

        if (isDark) {
            container.setBackgroundResource(R.drawable.bg_scratchpad_card_dark)
            pad.findViewById<TextView>(R.id.tvContactName).setTextColor(Color.parseColor("#E9EDEF"))
            etFreeformNotes?.setTextColor(Color.parseColor("#E9EDEF"))
            etFreeformNotes?.setHintTextColor(Color.parseColor("#8696A0"))
        } else {
            container.setBackgroundResource(R.drawable.bg_scratchpad_card_light)
            pad.findViewById<TextView>(R.id.tvContactName).setTextColor(Color.parseColor("#111B21"))
            etFreeformNotes?.setTextColor(Color.parseColor("#111B21"))
            etFreeformNotes?.setHintTextColor(Color.parseColor("#667781"))
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
        bubbleView?.visibility = View.GONE
        updateScratchpadUI()
        if (scratchpadView?.isAttachedToWindow == false) {
            windowManager.addView(scratchpadView, scratchpadParams)
        }
        scratchpadView?.visibility = View.VISIBLE
    }

    private fun closeScratchpad() {
        isScratchpadOpen = false
        scratchpadView?.visibility = View.GONE
        bubbleView?.visibility = View.VISIBLE
        updateBadge()
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
            holder.tvText.alpha = 0.5f
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
                holder.tvText.alpha = 0.5f
            } else {
                holder.tvText.paintFlags = holder.tvText.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                holder.tvText.alpha = 1.0f
            }
            onCheckToggled(position, isChecked)
        }

        holder.btnDelete.setOnClickListener {
            onDeleteClicked(position)
        }
    }

    override fun getItemCount(): Int = items.size
}
