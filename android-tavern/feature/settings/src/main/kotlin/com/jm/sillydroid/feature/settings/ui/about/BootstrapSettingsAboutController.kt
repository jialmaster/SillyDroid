package com.jm.sillydroid.feature.settings.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class BootstrapSettingsAboutController(
    private val activity: AppCompatActivity,
    private val githubButton: ImageButton,
    private val githubRepository: String,
    private val externalBrowserFailureMessage: () -> String
) {
    fun initialize() {
        githubButton.setOnClickListener {
            openProjectHomePage()
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
