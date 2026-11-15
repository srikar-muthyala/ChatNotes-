package com.chatnotes.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.chatnotes.app.databinding.ActivityMainBinding
import com.chatnotes.app.service.FloatingOverlayService
import com.chatnotes.app.service.WhatsAppAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    private fun setupListeners() {
        // Overlay permission button
        binding.btnOverlayPermission.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Appear on Top permission already granted!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Accessibility service button
        binding.btnAccessibilityPermission.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(this, "Find 'ChatNotes' in Installed Apps and turn it ON", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Accessibility Service is already active!", Toast.LENGTH_SHORT).show()
            }
        }

        // Test overlay button
        binding.btnTestOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Please grant 'Appear on Top' permission first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            FloatingOverlayService.showOverlay(this, "Demo Contact")
            Toast.makeText(this, "Floating bubble appeared! Tap it to test.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updatePermissionStates() {
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        val hasAccessibility = isAccessibilityServiceEnabled()

        // Update Overlay UI
        if (hasOverlay) {
            binding.btnOverlayPermission.text = getString(R.string.permission_granted)
            binding.btnOverlayPermission.isEnabled = false
        } else {
            binding.btnOverlayPermission.text = getString(R.string.grant_permission)
            binding.btnOverlayPermission.isEnabled = true
        }

        // Update Accessibility UI
        if (hasAccessibility) {
            binding.btnAccessibilityPermission.text = getString(R.string.permission_granted)
            binding.btnAccessibilityPermission.isEnabled = false
        } else {
            binding.btnAccessibilityPermission.text = getString(R.string.grant_permission)
            binding.btnAccessibilityPermission.isEnabled = true
        }

        // Overall status banner
        if (hasOverlay && hasAccessibility) {
            binding.tvStatusTitle.text = getString(R.string.service_running_title)
            binding.tvStatusTitle.setTextColor(getColor(R.color.primary))
            binding.tvStatusDesc.text = getString(R.string.service_running_desc)
        } else {
            binding.tvStatusTitle.text = "Setup in Progress"
            binding.tvStatusDesc.text = "Grant the two permissions below to activate the WhatsApp floating notes service."
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName &&
                service.resolveInfo.serviceInfo.name == WhatsAppAccessibilityService::class.java.name) {
                return true
            }
        }
        return false
    }
}
