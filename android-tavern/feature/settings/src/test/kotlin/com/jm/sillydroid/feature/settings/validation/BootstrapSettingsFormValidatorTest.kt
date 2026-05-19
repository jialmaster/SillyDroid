package com.jm.sillydroid.feature.settings.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BootstrapSettingsFormValidatorTest {

    private fun validBaseValues(): Map<String, Any?> {
        return mapOf(
            "git.backend" to "auto",
            "deepl.formality" to "default",
            "gemini.apiVersion" to "v1beta",
            "gemini.image.personGeneration" to "allow_adult"
        )
    }

    @Test
    fun `listen requires at least one startup security guard`() {
        val issue = BootstrapSettingsFormValidator.validate(
            values = validBaseValues() + mapOf(
                "listen" to true,
                "whitelistMode" to false,
                "basicAuthMode" to false,
                "enableUserAccounts" to false,
                "securityOverride" to false
            ),
            defaultServicePort = 8000
        )

        assertEquals("listen", issue?.fieldPath)
        assertEquals(
            "启用外部监听时，至少要启用 IP 白名单、基础认证、多用户账户之一；如仅用于排障，可改为开启“跳过启动安全检查”。",
            issue?.message
        )
    }

    @Test
    fun `listen stays valid when whitelist mode remains enabled`() {
        val issue = BootstrapSettingsFormValidator.validate(
            values = validBaseValues() + mapOf(
                "listen" to true,
                "whitelistMode" to true,
                "whitelist" to listOf("127.0.0.1")
            ),
            defaultServicePort = 8000
        )

        assertNull(issue)
    }

    @Test
    fun `listen stays valid when security override is explicitly enabled`() {
        val issue = BootstrapSettingsFormValidator.validate(
            values = validBaseValues() + mapOf(
                "listen" to true,
                "whitelistMode" to false,
                "basicAuthMode" to false,
                "enableUserAccounts" to false,
                "securityOverride" to true
            ),
            defaultServicePort = 8000
        )

        assertNull(issue)
    }
}
