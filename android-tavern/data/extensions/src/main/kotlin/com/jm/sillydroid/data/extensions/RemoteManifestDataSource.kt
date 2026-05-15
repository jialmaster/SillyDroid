package com.jm.sillydroid.data.extensions

import android.net.Uri
import com.jm.sillydroid.core.model.extensions.NormalizedExtensionRepository
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.json.JSONObject

data class ResolvedRemoteManifest(
    val repository: NormalizedExtensionRepository,
    val payload: JSONObject
)

class RemoteManifestDataSource {
    fun isSupportedRepositoryUrl(repositoryUrl: String): Boolean {
        val normalizedRepository = normalizeRepositoryUrl(repositoryUrl) ?: return false
        return resolveRemoteManifestSource(normalizedRepository) != null
    }

    fun requiresGithubReachabilityCheck(repository: NormalizedExtensionRepository): Boolean {
        return resolveRemoteManifestSource(repository)?.hosting == RemoteManifestHosting.GITHUB
    }

    fun githubReachabilityFailures(normalizedRepositories: List<NormalizedExtensionRepository>): List<String> {
        if (normalizedRepositories.none(::requiresGithubReachabilityCheck)) {
            return emptyList()
        }

        val checks = listOf(
            ReachabilityCheck("github.com", "https://github.com/robots.txt", setOf(200, 301, 302, 307, 308)),
            ReachabilityCheck("raw.githubusercontent.com", "https://raw.githubusercontent.com/github/gitignore/main/README.md", setOf(200, 301, 302, 307, 308)),
            ReachabilityCheck("api.github.com", "https://api.github.com/rate_limit", setOf(200, 301, 302, 307, 308, 403))
        )

        return checks.mapNotNull { check ->
            val statusCode = runCatching { fetchReachabilityStatusCode(check.targetUrl) }.getOrElse { error ->
                return@mapNotNull "${check.label} (${error.message ?: "连接失败"})"
            }

            if (statusCode in check.acceptedStatusCodes) {
                null
            } else {
                "${check.label} (HTTP $statusCode)"
            }
        }
    }

    fun normalizeRepositoryUrl(repositoryUrl: String): NormalizedExtensionRepository? {
        val uri = runCatching { Uri.parse(repositoryUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme.orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) {
            return null
        }
        if (host.isBlank()) {
            return null
        }

        val segments = uri.pathSegments.orEmpty()
            .map { segment -> segment.trim() }
            .filter { segment -> segment.isNotEmpty() }
        if (segments.isEmpty()) {
            return null
        }

        return when (host) {
            "github.com", "www.github.com" -> buildHostedRepositoryUrl(
                host = "github.com",
                ownerAndRepo = segments.take(2),
                branch = segments.getOrNull(2)
                    ?.takeIf { marker -> marker.equals("blob", ignoreCase = true) || marker.equals("tree", ignoreCase = true) || marker.equals("raw", ignoreCase = true) }
                    ?.let { segments.getOrNull(3) }
            )

            "raw.githubusercontent.com" -> buildHostedRepositoryUrl(
                host = "github.com",
                ownerAndRepo = segments.take(2),
                branch = segments.getOrNull(2)
            )

            "gitlab.com", "www.gitlab.com" -> {
                val markerIndex = segments.indexOf("-")
                val repoSegments = if (markerIndex >= 0) segments.subList(0, markerIndex) else segments
                val branch = if (
                    markerIndex >= 0 &&
                    segments.getOrNull(markerIndex + 1)?.let { marker ->
                        marker.equals("blob", ignoreCase = true) || marker.equals("tree", ignoreCase = true) || marker.equals("raw", ignoreCase = true)
                    } == true
                ) {
                    segments.getOrNull(markerIndex + 2)
                } else {
                    null
                }
                buildHostedRepositoryUrl("gitlab.com", repoSegments, branch)
            }

            else -> {
                val lastSegment = segments.last()
                if (lastSegment.equals("manifest.json", ignoreCase = true)) {
                    return null
                }
                val cloneUrl = buildString {
                    append(scheme.lowercase())
                    append("://")
                    append(uri.encodedAuthority)
                    append(uri.encodedPath?.trimEnd('/'))
                }
                NormalizedExtensionRepository(
                    cloneUrl = cloneUrl,
                    branch = null
                )
            }
        }
    }

    fun repositoryDisplayLabel(
        repositoryUrl: String,
        normalizedRepository: NormalizedExtensionRepository? = normalizeRepositoryUrl(repositoryUrl)
    ): String {
        val cloneUrl = normalizedRepository?.cloneUrl ?: repositoryUrl.trim()
        val pathSegments = runCatching { Uri.parse(cloneUrl).pathSegments.orEmpty() }
            .getOrDefault(emptyList())
            .map(::stripGitSuffix)
            .filter { segment -> segment.isNotBlank() }
        return when {
            pathSegments.size >= 2 -> pathSegments.takeLast(2).joinToString("/")
            pathSegments.isNotEmpty() -> pathSegments.joinToString("/")
            else -> cloneUrl
        }
    }

    fun fetchResolvedRemoteManifest(repository: NormalizedExtensionRepository): ResolvedRemoteManifest {
        val candidates = buildRemoteManifestCandidates(repository)
        if (candidates.isEmpty()) {
            throw IllegalStateException("扩展仓库地址不支持或无法解析 manifest.json。")
        }

        val branchHint = candidates.joinToString(separator = ", ") { candidate -> candidate.branch }
        var sawNotFound = false
        var sawReachableResponse = false
        var lastHttpStatus: Int? = null

        for (candidate in candidates) {
            val fetchResult = runCatching { fetchTextWithStatus(candidate.url) }.getOrNull() ?: continue
            lastHttpStatus = fetchResult.first
            when {
                fetchResult.first in 200..299 -> {
                    sawReachableResponse = true
                    return ResolvedRemoteManifest(
                        repository = repository.copy(branch = candidate.branch),
                        payload = parseManifestJson(fetchResult.second)
                    )
                }

                fetchResult.first == 404 -> {
                    sawReachableResponse = true
                    sawNotFound = true
                }
            }
        }

        if (sawNotFound) {
            throw IllegalStateException("未在候选分支找到 manifest.json：$branchHint")
        }

        if (sawReachableResponse && lastHttpStatus != null) {
            throw IllegalStateException("manifest.json 预检 HTTP 失败：$lastHttpStatus")
        }

        throw IllegalStateException("无法访问远程 manifest.json。")
    }

    private fun buildHostedRepositoryUrl(
        host: String,
        ownerAndRepo: List<String>,
        branch: String?
    ): NormalizedExtensionRepository? {
        if (ownerAndRepo.size < 2) {
            return null
        }
        val repoSegments = ownerAndRepo.mapIndexed { index, segment ->
            if (index == ownerAndRepo.lastIndex) {
                stripGitSuffix(segment)
            } else {
                segment
            }
        }
        if (repoSegments.any { segment -> segment.isBlank() }) {
            return null
        }
        return NormalizedExtensionRepository(
            cloneUrl = "https://$host/${repoSegments.joinToString("/")}.git",
            branch = branch?.takeIf { value -> value.isNotBlank() }
        )
    }

    private fun parseManifestJson(rawJson: String): JSONObject {
        val payload = runCatching { JSONObject(rawJson) }
            .getOrElse {
                throw IllegalStateException("远程 manifest.json 不是合法 JSON。")
            }
        if (payload.length() == 0) {
            throw IllegalStateException("远程 manifest.json 内容为空。")
        }
        return payload
    }

    private fun buildRemoteManifestCandidates(repository: NormalizedExtensionRepository): List<RemoteManifestCandidate> {
        val manifestSource = resolveRemoteManifestSource(repository) ?: return emptyList()
        val branchCandidates = resolveManifestBranches(manifestSource, repository.branch)
            .ifEmpty { return emptyList() }

        return when (manifestSource.hosting) {
            RemoteManifestHosting.GITHUB -> {
                val owner = manifestSource.segments[0]
                val repo = manifestSource.segments[1]
                branchCandidates.map { branch ->
                    RemoteManifestCandidate(
                        branch = branch,
                        url = "https://raw.githubusercontent.com/$owner/$repo/$branch/manifest.json"
                    )
                }
            }

            RemoteManifestHosting.GITLAB -> {
                val projectPath = manifestSource.segments.joinToString("/")
                branchCandidates.map { branch ->
                    RemoteManifestCandidate(
                        branch = branch,
                        url = "https://gitlab.com/$projectPath/-/raw/$branch/manifest.json"
                    )
                }
            }
        }
    }

    private fun resolveRemoteManifestSource(repository: NormalizedExtensionRepository): RemoteManifestSource? {
        val uri = runCatching { Uri.parse(repository.cloneUrl) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().lowercase()
        val segments = uri.pathSegments.orEmpty()
            .map { segment -> segment.trim() }
            .filter { segment -> segment.isNotEmpty() }
            .toMutableList()
        if (segments.isEmpty()) {
            return null
        }

        segments[segments.lastIndex] = stripGitSuffix(segments.last())
        if (segments.any { segment -> segment.isBlank() }) {
            return null
        }

        return when (host) {
            "github.com", "www.github.com", "raw.githubusercontent.com" -> {
                if (segments.size < 2) {
                    null
                } else {
                    RemoteManifestSource(
                        hosting = RemoteManifestHosting.GITHUB,
                        segments = segments.take(2)
                    )
                }
            }

            "gitclone.com" -> {
                if (segments.size < 3 || !segments.first().equals("github.com", ignoreCase = true)) {
                    null
                } else {
                    RemoteManifestSource(
                        hosting = RemoteManifestHosting.GITHUB,
                        segments = listOf(segments[1], segments[2])
                    )
                }
            }

            "gitlab.com", "www.gitlab.com" -> {
                val markerIndex = segments.indexOf("-")
                val repoSegments = if (markerIndex >= 0) segments.subList(0, markerIndex) else segments
                if (repoSegments.size < 2) {
                    null
                } else {
                    RemoteManifestSource(
                        hosting = RemoteManifestHosting.GITLAB,
                        segments = repoSegments.toList()
                    )
                }
            }

            else -> null
        }
    }

    private fun resolveManifestBranches(source: RemoteManifestSource, explicitBranch: String?): List<String> {
        explicitBranch?.trim()?.takeIf { value -> value.isNotEmpty() }?.let { branch ->
            return listOf(branch)
        }

        val defaultBranch = when (source.hosting) {
            RemoteManifestHosting.GITHUB -> fetchGithubDefaultBranch(source.segments)
            RemoteManifestHosting.GITLAB -> fetchGitlabDefaultBranch(source.segments)
        }

        return listOfNotNull(defaultBranch, "main", "master").distinct()
    }

    private fun fetchGithubDefaultBranch(segments: List<String>): String? {
        if (segments.size < 2) {
            return null
        }
        val owner = segments[0]
        val repo = segments[1]
        val payload = runCatching {
            fetchTextWithStatus("https://api.github.com/repos/$owner/$repo")
        }.getOrNull() ?: return null
        if (payload.first !in 200..299) {
            return null
        }
        return runCatching {
            JSONObject(payload.second).optString("default_branch").trim().ifBlank { null }
        }.getOrNull()
    }

    private fun fetchGitlabDefaultBranch(segments: List<String>): String? {
        val projectPath = segments.joinToString("/")
        val encodedPath = URLEncoder.encode(projectPath, StandardCharsets.UTF_8.toString())
        val payload = runCatching {
            fetchTextWithStatus("https://gitlab.com/api/v4/projects/$encodedPath")
        }.getOrNull() ?: return null
        if (payload.first !in 200..299) {
            return null
        }
        return runCatching {
            JSONObject(payload.second).optString("default_branch").trim().ifBlank { null }
        }.getOrNull()
    }

    private fun fetchTextWithStatus(targetUrl: String): Pair<Int, String> {
        val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "application/json, text/plain, */*")
            setRequestProperty("User-Agent", "SillyDroid-Android-Host")
        }

        return try {
            val statusCode = connection.responseCode
            val payload = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { reader -> reader.readText() }
                .orEmpty()
            statusCode to payload
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchReachabilityStatusCode(targetUrl: String): Int {
        val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", "SillyDroid-Android-Host")
        }

        return try {
            val responseCode = connection.responseCode
            (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.use { stream ->
                    stream.read()
                }
            responseCode
        } finally {
            connection.disconnect()
        }
    }

    private fun stripGitSuffix(value: String): String {
        return if (value.endsWith(".git", ignoreCase = true)) {
            value.dropLast(4)
        } else {
            value
        }
    }

    private data class RemoteManifestCandidate(
        val branch: String,
        val url: String
    )

    private data class ReachabilityCheck(
        val label: String,
        val targetUrl: String,
        val acceptedStatusCodes: Set<Int>
    )

    private enum class RemoteManifestHosting {
        GITHUB,
        GITLAB
    }

    private data class RemoteManifestSource(
        val hosting: RemoteManifestHosting,
        val segments: List<String>
    )
}
