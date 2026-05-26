package com.jm.sillydroid.feature.main.ui.home.webview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewRuntimeCompatibilityTest {

    @Test
    fun `parseChromiumVersion uses real Chromium version from user agent`() {
        val userAgent = "Mozilla/5.0 AppleWebKit/537.36 Version/4.0 Chrome/110.0.5481.65 Mobile Safari/537.36"

        assertEquals("110.0.5481.65", parseChromiumVersion(userAgent))
    }

    @Test
    fun `isOutdated warns when Chromium runtime is below 111 instead of provider version`() {
        val compatibility = WebViewRuntimeCompatibility(
            providerPackageName = "com.huawei.webview",
            providerVersionName = "114.0.5.302",
            providerVersionCode = "11273",
            userAgent = "Mozilla/5.0 Chrome/110.0.5481.65 Mobile Safari/537.36",
            chromiumVersion = "110.0.5481.65",
            chromiumMajorVersion = parseMajorVersion("110.0.5481.65"),
        )

        assertTrue(compatibility.isOutdated)
    }

    @Test
    fun `isOutdated does not warn when Chromium runtime reaches 111`() {
        val compatibility = WebViewRuntimeCompatibility(
            providerPackageName = "com.huawei.webview",
            providerVersionName = "14.0.2.319",
            providerVersionCode = "21705",
            userAgent = "Mozilla/5.0 Chrome/111.0.5563.116 Mobile Safari/537.36",
            chromiumVersion = "111.0.5563.116",
            chromiumMajorVersion = parseMajorVersion("111.0.5563.116"),
        )

        assertFalse(compatibility.isOutdated)
    }
}
