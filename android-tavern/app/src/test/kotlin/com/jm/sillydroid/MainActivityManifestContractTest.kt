package com.jm.sillydroid

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 校验主界面的 Android Manifest 窗口契约。
 *
 * 本测试只约束影响宿主窗口行为的声明，不负责验证 GeckoView 页面内部布局。
 */
class MainActivityManifestContractTest {
    /** 确保系统键盘出现时主界面始终请求调整可用窗口高度。 */
    @Test
    fun `main activity requests resize for the software keyboard`() {
        val manifest = File("src/main/AndroidManifest.xml")
        val documentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val document = documentBuilderFactory.newDocumentBuilder().parse(manifest)
        val activities = document.getElementsByTagName("activity")
        val mainActivity = (0 until activities.length)
            .map { activities.item(it) }
            .firstOrNull { activity ->
                activity.attributes
                    .getNamedItemNS(ANDROID_NAMESPACE, "name")
                    ?.nodeValue == MAIN_ACTIVITY_NAME
            }

        assertNotNull("MainActivity must be declared in the app manifest", mainActivity)
        assertEquals(
            "adjustResize",
            mainActivity!!.attributes
                .getNamedItemNS(ANDROID_NAMESPACE, "windowSoftInputMode")
                ?.nodeValue
        )
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
        const val MAIN_ACTIVITY_NAME = "com.jm.sillydroid.feature.main.MainActivity"
    }
}
