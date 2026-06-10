package com.jm.sillydroid.domain.bootstrap

typealias RuntimePatchSettingOverrides = Map<String, Map<String, String>>

object RuntimePatchSettingTypes {
    private val switchTypes = setOf("switch", "toggle", "boolean")
    private val checkboxTypes = setOf("check", "checkbox")
    private val textTypes = setOf("input", "text", "string")
    private val numberTypes = setOf("number", "integer", "int", "float", "decimal")
    private val listTypes = setOf("list", "select", "radio", "choice")

    fun normalize(type: String): String {
        val normalized = type.trim().lowercase()
        return when {
            normalized in switchTypes -> "switch"
            normalized in checkboxTypes -> "checkbox"
            normalized in numberTypes -> "number"
            normalized in listTypes -> "select"
            normalized in textTypes -> "text"
            else -> "text"
        }
    }
}

object RuntimePatchSettingOverridesCodec {
    fun decode(rawJson: String?): RuntimePatchSettingOverrides {
        val raw = rawJson.orEmpty().trim()
        if (raw.isBlank()) {
            return emptyMap()
        }
        return runCatching {
            RuntimePatchOverridesJsonParser(raw).parse()
        }.getOrDefault(emptyMap())
    }

    fun encode(overrides: RuntimePatchSettingOverrides): String {
        return overrides.toSortedMap()
            .mapNotNull { (moduleId, settings) ->
                val normalizedModuleId = moduleId.trim()
                if (normalizedModuleId.isBlank()) {
                    null
                } else {
                    val encodedSettings = settings.toSortedMap()
                        .mapNotNull { (settingKey, settingValue) ->
                            val normalizedSettingKey = settingKey.trim()
                            val normalizedSettingValue = settingValue.trim()
                            if (normalizedSettingKey.isBlank() || normalizedSettingValue.isBlank()) {
                                null
                            } else {
                                "${quoteJson(normalizedSettingKey)}:${quoteJson(normalizedSettingValue)}"
                            }
                        }
                        .joinToString(separator = ",")
                    if (encodedSettings.isBlank()) {
                        null
                    } else {
                        "${quoteJson(normalizedModuleId)}:{$encodedSettings}"
                    }
                }
            }
            .joinToString(prefix = "{", postfix = "}", separator = ",")
            .takeUnless { encoded -> encoded == "{}" }
            .orEmpty()
    }

    private fun quoteJson(value: String): String {
        return buildString {
            append('"')
            value.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (character.code < 0x20) {
                            append("\\u")
                            append(character.code.toString(16).padStart(4, '0'))
                        } else {
                            append(character)
                        }
                    }
                }
            }
            append('"')
        }
    }

    fun resolveValue(
        overrides: RuntimePatchSettingOverrides,
        moduleId: String,
        setting: RuntimePatchSettingMetadataSnapshot
    ): String {
        return overrides[moduleId.trim()]
            ?.get(setting.key.trim())
            ?.trim()
            ?.takeIf { value -> value.isNotBlank() }
            ?: setting.defaultValue.trim()
    }
}

private class RuntimePatchOverridesJsonParser(private val raw: String) {
    private var index = 0

    fun parse(): RuntimePatchSettingOverrides {
        skipWhitespace()
        if (!consume('{')) {
            return emptyMap()
        }
        val result = linkedMapOf<String, Map<String, String>>()
        skipWhitespace()
        if (consume('}')) {
            return emptyMap()
        }
        while (index < raw.length) {
            val moduleId = parseString().trim()
            skipWhitespace()
            if (!consume(':')) {
                return emptyMap()
            }
            val settings = parseSettingsObject()
            if (moduleId.isNotBlank() && settings.isNotEmpty()) {
                result[moduleId] = settings
            }
            skipWhitespace()
            if (consume('}')) {
                return result
            }
            if (!consume(',')) {
                return emptyMap()
            }
        }
        return emptyMap()
    }

    private fun parseSettingsObject(): Map<String, String> {
        skipWhitespace()
        if (!consume('{')) {
            return emptyMap()
        }
        val result = linkedMapOf<String, String>()
        skipWhitespace()
        if (consume('}')) {
            return emptyMap()
        }
        while (index < raw.length) {
            val key = parseString().trim()
            skipWhitespace()
            if (!consume(':')) {
                return emptyMap()
            }
            val value = parseString().trim()
            if (key.isNotBlank() && value.isNotBlank()) {
                result[key] = value
            }
            skipWhitespace()
            if (consume('}')) {
                return result
            }
            if (!consume(',')) {
                return emptyMap()
            }
        }
        return emptyMap()
    }

    private fun parseString(): String {
        skipWhitespace()
        if (!consume('"')) {
            return ""
        }
        return buildString {
            while (index < raw.length) {
                val character = raw[index++]
                when (character) {
                    '"' -> return@buildString
                    '\\' -> append(parseEscapedCharacter())
                    else -> append(character)
                }
            }
        }
    }

    private fun parseEscapedCharacter(): Char {
        if (index >= raw.length) {
            return '\\'
        }
        return when (val escaped = raw[index++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> escaped
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (index + 4 > raw.length) {
            return '?'
        }
        val hex = raw.substring(index, index + 4)
        index += 4
        return hex.toIntOrNull(radix = 16)?.toChar() ?: '?'
    }

    private fun consume(expected: Char): Boolean {
        skipWhitespace()
        if (index >= raw.length || raw[index] != expected) {
            return false
        }
        index += 1
        return true
    }

    private fun skipWhitespace() {
        while (index < raw.length && raw[index].isWhitespace()) {
            index += 1
        }
    }
}
