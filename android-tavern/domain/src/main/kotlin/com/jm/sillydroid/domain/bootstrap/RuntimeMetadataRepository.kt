package com.jm.sillydroid.domain.bootstrap

interface RuntimeMetadataRepository {
    fun resolveRuntimeVersionLabel(): String?
    fun resolveServerPayloadVersionLabel(
        upstreamVersion: String,
        currentVersionName: String
    ): String?
}
