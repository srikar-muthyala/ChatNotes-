package com.chatnotes.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class NotesRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREFS_NAME = "chatnotes_storage"
        private const val KEY_THEME = "chatnotes_is_dark_theme"

        @Volatile
        private var INSTANCE: NotesRepository? = null

        fun getInstance(context: Context): NotesRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NotesRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private fun sanitizeKey(contactName: String): String {
        return "contact_" + contactName.trim().lowercase().replace("[^a-z0-9_]".toRegex(), "_")
    }

    fun getContactData(contactName: String): ContactNotesData {
        val key = sanitizeKey(contactName)
        val json = prefs.getString(key, null)
        return if (!json.isNullOrEmpty()) {
            try {
                gson.fromJson(json, ContactNotesData::class.java)
            } catch (e: Exception) {
                ContactNotesData(contactName = contactName)
            }
        } else {
            ContactNotesData(contactName = contactName)
        }
    }

    fun saveContactData(data: ContactNotesData) {
        val key = sanitizeKey(data.contactName)
        data.updatedAt = System.currentTimeMillis()
        val json = gson.toJson(data)
        prefs.edit().putString(key, json).apply()
    }

    fun isDarkTheme(): Boolean {
        return prefs.getBoolean(KEY_THEME, true)
    }

    fun setDarkTheme(isDark: Boolean) {
        prefs.edit().putBoolean(KEY_THEME, isDark).apply()
    }
}
