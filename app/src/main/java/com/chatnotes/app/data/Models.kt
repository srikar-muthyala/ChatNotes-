package com.chatnotes.app.data

data class ChecklistItem(
    val id: String = System.currentTimeMillis().toString(),
    var text: String,
    var isDone: Boolean = false
)

data class ContactNotesData(
    val contactName: String,
    val checklist: MutableList<ChecklistItem> = mutableListOf(),
    var freeformNotes: String = "",
    var updatedAt: Long = System.currentTimeMillis()
)
