package com.jm.sillydroid.data.runtime

internal data class BootstrapReadyWatchdogPolicySnapshot(
    val name: String,
    val failureThreshold: Int
)

internal object BootstrapReadyWatchdogPolicy {
    const val foregroundFailureThreshold = 6
    const val backgroundFailureThreshold = 18

    fun resolve(isAppInForeground: Boolean): BootstrapReadyWatchdogPolicySnapshot {
        return if (isAppInForeground) {
            BootstrapReadyWatchdogPolicySnapshot(
                name = "foreground",
                failureThreshold = foregroundFailureThreshold
            )
        } else {
            BootstrapReadyWatchdogPolicySnapshot(
                name = "background-relaxed",
                failureThreshold = backgroundFailureThreshold
            )
        }
    }
}
