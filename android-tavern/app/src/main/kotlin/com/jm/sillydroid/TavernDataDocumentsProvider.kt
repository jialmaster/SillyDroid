package com.jm.sillydroid

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.jm.sillydroid.data.runtime.HostPaths
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.Locale

class TavernDataDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val rootDirectory = tavernRootDirectory().apply { mkdirs() }
        return MatrixCursor(resolveRootProjection(projection)).apply {
            newRow().apply {
                add(Root.COLUMN_ROOT_ID, rootId)
                add(Root.COLUMN_DOCUMENT_ID, rootDocumentId)
                add(Root.COLUMN_TITLE, contextOrThrow().getString(R.string.tavern_data_documents_root_title))
                add(Root.COLUMN_SUMMARY, contextOrThrow().getString(R.string.tavern_data_documents_root_summary))
                add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                add(Root.COLUMN_ICON, R.drawable.ic_launcher)
                add(Root.COLUMN_AVAILABLE_BYTES, rootDirectory.freeSpace)
                add(Root.COLUMN_MIME_TYPES, "*/*")
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val file = resolveDocumentFile(documentId)
        return MatrixCursor(resolveDocumentProjection(projection)).apply {
            includeDocument(documentFor(documentId, file))
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val parent = resolveDocumentFile(parentDocumentId)
        if (!parent.isDirectory) {
            throw FileNotFoundException("Document is not a directory: $parentDocumentId")
        }

        val rootDirectory = tavernRootDirectory()
        val visibleRootDirectory = if (parentDocumentId.isServerDocumentId()) tavernServerDirectory() else rootDirectory
        return MatrixCursor(resolveDocumentProjection(projection)).apply {
            if (parentDocumentId == rootDocumentId) {
                includeDocument(
                    TavernDocument(
                        file = tavernServerDirectory().apply { mkdirs() },
                        documentId = serverRootDocumentId,
                        displayName = contextOrThrow().getString(R.string.tavern_data_documents_server_root_title)
                    )
                )
            }
            parent.listFiles()
                .orEmpty()
                .filter { child -> isVisibleDocument(parentDocumentId, visibleRootDirectory, child) }
                .sortedWith(compareBy<File> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .forEach { child -> includeDocument(documentFor(documentIdFor(child), child)) }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = resolveDocumentFile(documentId)
        if (file.isDirectory) {
            throw FileNotFoundException("Cannot open a directory as a file: $documentId")
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = resolveDocumentFile(parentDocumentId)
        if (!parent.isDirectory) {
            throw FileNotFoundException("Parent is not a directory: $parentDocumentId")
        }

        val target = uniqueTarget(parent, sanitizeDisplayName(displayName))
        if (mimeType == Document.MIME_TYPE_DIR) {
            if (!target.mkdirs()) {
                throw FileNotFoundException("Failed to create directory: ${target.name}")
            }
        } else {
            target.parentFile?.mkdirs()
            if (!target.createNewFile()) {
                throw FileNotFoundException("Failed to create file: ${target.name}")
            }
        }

        return documentIdFor(target)
    }

    override fun deleteDocument(documentId: String) {
        val file = resolveDocumentFile(documentId)
        if (isProtectedDocument(documentId, file)) {
            throw FileNotFoundException("Root document cannot be deleted.")
        }

        val deleted = if (Files.isSymbolicLink(file.toPath())) {
            file.delete()
        } else {
            file.deleteRecursively()
        }
        if (!deleted && file.exists()) {
            throw FileNotFoundException("Failed to delete document: $documentId")
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = resolveDocumentFile(documentId)
        if (isProtectedDocument(documentId, file)) {
            throw FileNotFoundException("Root document cannot be renamed.")
        }

        val parent = file.parentFile ?: throw FileNotFoundException("Document has no parent: $documentId")
        val target = uniqueTarget(parent, sanitizeDisplayName(displayName))
        if (!file.renameTo(target)) {
            throw FileNotFoundException("Failed to rename document: $documentId")
        }
        return documentIdFor(target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == rootDocumentId && documentId.isServerDocumentId()) {
            return true
        }
        return runCatching {
            val parent = resolveDocumentFile(parentDocumentId).absoluteFile
            val child = resolveDocumentFile(documentId).absoluteFile
            isInsidePath(parent, child) && parent != child
        }.getOrDefault(false)
    }

    private fun MatrixCursor.includeDocument(file: File) {
        includeDocument(documentFor(documentIdFor(file), file))
    }

    private fun MatrixCursor.includeDocument(document: TavernDocument) {
        val file = document.file
        val isRoot = document.documentId == rootDocumentId
        val isServerRoot = document.documentId == serverRootDocumentId
        val isDirectory = file.isDirectory
        val flags = when {
            isRoot -> Document.FLAG_DIR_SUPPORTS_CREATE
            isServerRoot -> 0
            isDirectory -> Document.FLAG_DIR_SUPPORTS_CREATE or
                Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_RENAME
            else -> Document.FLAG_SUPPORTS_WRITE or
                Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_RENAME
        }

        newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, document.documentId)
            add(
                Document.COLUMN_DISPLAY_NAME,
                if (isRoot) contextOrThrow().getString(R.string.tavern_data_documents_root_title) else document.displayName
            )
            add(Document.COLUMN_SIZE, if (isDirectory) null else file.length())
            add(Document.COLUMN_MIME_TYPE, if (isDirectory) Document.MIME_TYPE_DIR else mimeTypeFor(file))
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun tavernRootDirectory(): File {
        // MT/SAF 入口面向高级用户，直接暴露运行时 APP_DATA_ROOT，根层应是 config/data/plugins/extensions。
        return hostPaths().serverDataDir
    }

    private fun hostPaths(): HostPaths {
        return HostPaths.from(contextOrThrow())
    }

    private fun contextOrThrow() = requireNotNull(context) {
        "TavernDataDocumentsProvider is not attached to a context."
    }

    private fun resolveDocumentFile(documentId: String): File {
        val rootDirectory = tavernRootDirectory().apply { mkdirs() }.absoluteFile
        val file = resolveDocumentFileUnchecked(documentId, rootDirectory)

        if (!isInsidePath(rootDirectory, file.absoluteFile)) {
            if (!documentId.isServerDocumentId()) {
                throw FileNotFoundException("Document escapes Tavern root: $documentId")
            }
        }

        val canonicalFile = file.canonicalFile
        if (!isInsideAccessibleRoot(canonicalFile)) {
            throw FileNotFoundException("Document escapes Tavern root: $documentId")
        }
        if (!isVisibleDocument(documentId, if (documentId.isServerDocumentId()) tavernServerDirectory() else rootDirectory, file)) {
            throw FileNotFoundException("Document is hidden from this provider: $documentId")
        }
        if (!file.exists()) {
            throw FileNotFoundException("Document does not exist: $documentId")
        }
        return file
    }

    private fun resolveDocumentFileUnchecked(documentId: String, rootDirectory: File): File {
        return when {
            documentId == rootDocumentId -> rootDirectory
            documentId.startsWith("$rootDocumentId/") -> {
                val relativePath = documentId.removePrefix("$rootDocumentId/")
                if (relativePath.isBlank()) rootDirectory else File(rootDirectory, relativePath)
            }
            documentId == serverRootDocumentId -> tavernServerDirectory().apply { mkdirs() }.absoluteFile
            documentId.startsWith("$serverRootDocumentId/") -> {
                val relativePath = documentId.removePrefix("$serverRootDocumentId/")
                if (relativePath.isBlank()) {
                    tavernServerDirectory().apply { mkdirs() }.absoluteFile
                } else {
                    File(tavernServerDirectory().apply { mkdirs() }, relativePath)
                }
            }
            else -> throw FileNotFoundException("Unknown document id: $documentId")
        }
    }

    private fun documentIdFor(file: File): String {
        val rootDirectory = tavernRootDirectory().absoluteFile
        val absoluteFile = file.absoluteFile
        return if (absoluteFile == rootDirectory) {
            rootDocumentId
        } else if (absoluteFile == tavernServerDirectory().absoluteFile) {
            serverRootDocumentId
        } else if (isInsidePath(tavernServerDirectory().absoluteFile, absoluteFile)) {
            "$serverRootDocumentId/${absoluteFile.relativeTo(tavernServerDirectory().absoluteFile).invariantSeparatorsPath}"
        } else {
            "$rootDocumentId/${absoluteFile.relativeTo(rootDirectory).invariantSeparatorsPath}"
        }
    }

    private fun documentFor(documentId: String, file: File): TavernDocument {
        return TavernDocument(
            file = file,
            documentId = documentId,
            displayName = if (documentId == serverRootDocumentId) {
                contextOrThrow().getString(R.string.tavern_data_documents_server_root_title)
            } else {
                file.name
            }
        )
    }

    private fun isProtectedDocument(documentId: String, file: File): Boolean {
        return file.absoluteFile == tavernRootDirectory().absoluteFile || documentId == serverRootDocumentId
    }

    private fun isVisibleDocument(documentId: String, rootDirectory: File, file: File): Boolean {
        if (!isInsidePath(rootDirectory.absoluteFile, file.absoluteFile)) {
            return false
        }

        val canonicalFile = file.canonicalFile
        if (!isInsideAccessibleRoot(canonicalFile)) {
            return false
        }
        if (file.absoluteFile == rootDirectory.absoluteFile) {
            return true
        }

        val relativePath = file.absoluteFile.relativeTo(rootDirectory.absoluteFile).invariantSeparatorsPath
        val topLevelName = relativePath.substringBefore('/')
        return documentId.isServerDocumentId() || topLevelName != ".sillydroid-maintenance"
    }

    private fun isInsideAccessibleRoot(file: File): Boolean {
        val paths = hostPaths()
        val canonicalFile = file.canonicalFile
        return isInside(paths.serverDataDir.canonicalFile, canonicalFile) ||
            isInside(paths.serverDir.canonicalFile, canonicalFile)
    }

    private fun tavernServerDirectory(): File {
        return hostPaths().serverDir
    }

    private fun isInsidePath(parent: File, child: File): Boolean {
        val parentPath = parent.toPath().toAbsolutePath().normalize()
        val childPath = child.toPath().toAbsolutePath().normalize()
        return childPath == parentPath || childPath.startsWith(parentPath)
    }

    private fun isInside(parent: File, child: File): Boolean {
        val parentPath = parent.canonicalPath
        val childPath = child.canonicalPath
        return childPath == parentPath || childPath.startsWith(parentPath + File.separator)
    }

    private fun sanitizeDisplayName(displayName: String): String {
        val sanitized = displayName.trim()
            .replace('/', '_')
            .replace('\\', '_')
        if (sanitized.isBlank() || sanitized == "." || sanitized == "..") {
            throw FileNotFoundException("Invalid document name: $displayName")
        }
        return sanitized
    }

    private fun uniqueTarget(parent: File, displayName: String): File {
        val directTarget = File(parent, displayName)
        if (!directTarget.exists()) {
            return directTarget
        }

        val extensionSeparator = displayName.lastIndexOf('.')
        val hasExtension = extensionSeparator > 0 && extensionSeparator < displayName.lastIndex
        val baseName = if (hasExtension) displayName.substring(0, extensionSeparator) else displayName
        val extension = if (hasExtension) displayName.substring(extensionSeparator) else ""

        for (index in 1..9999) {
            val candidate = File(parent, "$baseName ($index)$extension")
            if (!candidate.exists()) {
                return candidate
            }
        }

        throw FileNotFoundException("Failed to allocate unique document name: $displayName")
    }

    private fun mimeTypeFor(file: File): String {
        val extension = file.extension.lowercase(Locale.US)
        return when (extension) {
            "css" -> "text/css"
            "js", "mjs", "cjs" -> "application/javascript"
            "json", "jsonl" -> "application/json"
            "yaml", "yml" -> "application/x-yaml"
            "md" -> "text/markdown"
            "txt", "log" -> "text/plain"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: "application/octet-stream"
        }
    }

    private fun resolveRootProjection(projection: Array<out String>?): Array<String> {
        return projection?.map { it }?.toTypedArray() ?: defaultRootProjection
    }

    private fun resolveDocumentProjection(projection: Array<out String>?): Array<String> {
        return projection?.map { it }?.toTypedArray() ?: defaultDocumentProjection
    }

    private companion object {
        private const val rootId = "sillydroid-tavern-data"
        private const val rootDocumentId = "root"
        private const val serverRootDocumentId = "tavern-server"

        private val defaultRootProjection = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_MIME_TYPES
        )

        private val defaultDocumentProjection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS
        )

        private fun String.isServerDocumentId(): Boolean {
            return this == serverRootDocumentId || startsWith("$serverRootDocumentId/")
        }
    }

    private data class TavernDocument(
        val file: File,
        val documentId: String,
        val displayName: String
    )
}
