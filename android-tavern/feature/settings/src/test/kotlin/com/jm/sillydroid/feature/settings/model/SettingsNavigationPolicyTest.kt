package com.jm.sillydroid.feature.settings.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsNavigationPolicyTest {

    @Test
    fun `canFinish blocks return while settings task is busy`() {
        assertFalse(SettingsNavigationPolicy.canFinish(isBusy = true))
    }

    @Test
    fun `canFinish allows return when settings task is idle`() {
        assertTrue(SettingsNavigationPolicy.canFinish(isBusy = false))
    }
}
