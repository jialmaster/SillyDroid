package com.jm.sillydroid.feature.main.ui.home.io

import android.content.Intent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserFileChooserIntentFallbackTest {
    @Test
    fun `get content fallback preserves mime filters and multiple selection`() {
        val sourceIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "application/json"))
        }

        val fallback = BrowserFileChooserIntentFallback.buildGetContentFallback(
            sourceIntent = sourceIntent,
            allowMultiple = true
        )

        assertEquals(Intent.ACTION_GET_CONTENT, fallback.action)
        assertTrue(fallback.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", fallback.type)
        assertEquals(true, fallback.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertArrayEquals(
            arrayOf("image/png", "application/json"),
            fallback.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        )
    }

    @Test
    fun `get content fallback unwraps chooser target intent`() {
        val targetIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "application/zip"
        }
        val chooserIntent = Intent.createChooser(targetIntent, "Choose file")

        val fallback = BrowserFileChooserIntentFallback.buildGetContentFallback(
            sourceIntent = chooserIntent,
            allowMultiple = false
        )

        assertEquals(Intent.ACTION_GET_CONTENT, fallback.action)
        assertEquals("application/zip", fallback.type)
        assertEquals(false, fallback.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun `get content fallback uses any file type when source has no type`() {
        val fallback = BrowserFileChooserIntentFallback.buildGetContentFallback(
            sourceIntent = Intent(Intent.ACTION_OPEN_DOCUMENT),
            allowMultiple = false
        )

        assertEquals("*/*", fallback.type)
    }
}
