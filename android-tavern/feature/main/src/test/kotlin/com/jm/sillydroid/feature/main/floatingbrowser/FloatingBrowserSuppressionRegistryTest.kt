package com.jm.sillydroid.feature.main.floatingbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证系统页面抑制令牌的引用计数和无内容诊断输出。 */
class FloatingBrowserSuppressionRegistryTest {

    /** 所有并行系统流程都结束后才允许重新显示 overlay。 */
    @Test
    fun `suppression remains active until every token closes`() {
        val registry = FloatingBrowserSuppressionRegistry()
        val fileChooser = registry.acquire("file_chooser")
        val feedbackPicker = registry.acquire("feedback_image_picker")

        assertTrue(registry.isSuppressed())
        assertEquals("feedback_image_picker|file_chooser", registry.diagnosticReasons())

        fileChooser.close()
        assertTrue(registry.isSuppressed())
        feedbackPicker.close()
        assertFalse(registry.isSuppressed())
    }
}
