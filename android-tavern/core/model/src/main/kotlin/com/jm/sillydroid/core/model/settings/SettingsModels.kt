package com.jm.sillydroid.core.model.settings

import java.util.LinkedHashMap

enum class TavernConfigFieldKind {
    BOOLEAN,
    TEXT,
    MULTILINE_TEXT,
    INTEGER,
    STRING_LIST,
    PASSWORD
}

data class TavernConfigFieldSpec(
    val path: String,
    val title: String,
    val summary: String,
    val kind: TavernConfigFieldKind,
    val defaultValue: Any?,
    val visibleWhenAllEnabled: List<String> = emptyList(),
    val visibleWhenAnyEnabled: List<String> = emptyList()
)

data class TavernConfigSectionSpec(
    val title: String,
    val summary: String,
    val fields: List<TavernConfigFieldSpec>
)

data class LoadedTavernConfig(
    val root: LinkedHashMap<String, Any?>,
    val filePath: String,
    val warningMessage: String?
)

enum class TavernDataArchiveKind {
    HOST_FULL_SNAPSHOT,
    USER_BACKUP
}

data class TavernDataImportResult(
    val importedFileCount: Int,
    val archiveKind: TavernDataArchiveKind
)

data class TavernDataArchivePreview(
    val archiveKind: TavernDataArchiveKind,
    val sourceUserId: String? = null,
    val targetUserId: String? = null,
    val sourceLayoutLabel: String? = null,
    val writeTargets: List<String> = emptyList(),
    val contentStats: List<String> = emptyList()
)

data class FloatingLogBubblePosition(
    val horizontalFraction: Float,
    val verticalFraction: Float
)

object FloatingLogRefreshIntervals {
    const val REALTIME_MILLIS: Int = 250
    const val ONE_SECOND_MILLIS: Int = 1_000
    const val THREE_SECONDS_MILLIS: Int = 3_000
    const val FIVE_SECONDS_MILLIS: Int = 5_000

    val options: IntArray = intArrayOf(
        REALTIME_MILLIS,
        ONE_SECOND_MILLIS,
        THREE_SECONDS_MILLIS,
        FIVE_SECONDS_MILLIS
    )
}
