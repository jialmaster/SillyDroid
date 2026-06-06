package com.jm.sillydroid.feature.settings.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import com.jm.sillydroid.domain.settings.HostPreferencesRepository

class BootstrapSettingsAboutController(
    private val activity: AppCompatActivity,
    private val githubButton: ImageButton,
    private val crashUploadSwitch: MaterialSwitch,
    private val hostPreferencesRepository: HostPreferencesRepository,
    private val githubRepository: String,
    private val externalBrowserFailureMessage: () -> String
) {
    fun initialize() {
        githubButton.setOnClickListener {
            openProjectHomePage()
        }
        crashUploadSwitch.isChecked = hostPreferencesRepository.crashLogUploadEnabled
        crashUploadSwitch.setOnCheckedChangeListener { _, isChecked ->
            hostPreferencesRepository.crashLogUploadEnabled = isChecked
        }
    }

    private fun openProjectHomePage() {
        val repository = githubRepository.trim()
        val projectUri = Uri.parse("https://github.com/$repository")
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, projectUri).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, externalBrowserFailureMessage(), Toast.LENGTH_SHORT).show()
        }
    }
}
