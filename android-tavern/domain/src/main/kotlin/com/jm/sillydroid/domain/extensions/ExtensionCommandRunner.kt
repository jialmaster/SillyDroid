package com.jm.sillydroid.domain.extensions

data class ExtensionCommandRequest(
    val requestName: String,
    val commandFileName: String,
    val commandContent: String,
    val launchScriptContent: String,
    val environment: Map<String, String>
)

data class ExtensionCommandResult(
    val logPath: String
)

interface ExtensionCommandRunner {
    fun run(
        request: ExtensionCommandRequest,
        onProgressPayload: ((String) -> Unit)? = null,
        failureMessage: (String) -> String
    ): ExtensionCommandResult
}
