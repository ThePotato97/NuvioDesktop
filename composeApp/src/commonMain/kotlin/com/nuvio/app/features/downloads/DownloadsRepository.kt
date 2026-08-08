package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString

object DownloadsRepository {
    private const val MaxDownloadAttempts = 3

    /**
     * Transfers running at once. Bulk season/show downloads can enqueue dozens of episodes, and
     * every concurrent transfer is a live HTTP connection competing for the same bandwidth, so the
     * rest wait in [DownloadStatus.Queued] until a slot frees up.
     */
    private const val MaxConcurrentDownloads = 3

    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    private val activeHandles = mutableMapOf<String, DownloadsTaskHandle>()

    /**
     * Ids currently occupying a queue slot. Kept separately from [activeHandles] because a transfer
     * can finish on its IO thread before `DownloadsPlatformDownloader.start` has even returned its
     * handle; counting handles would then leak the slot forever and eventually stall the queue.
     */
    private val runningDownloadIds = mutableSetOf<String>()
    private var hasLoaded = false
    private var nextDownloadOrdinal = 0L
    private var isPumpingQueue = false

    fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    fun onProfileChanged() {
        loadFromDisk()
    }

    fun clearLocalState() {
        activeHandles.values.forEach(DownloadsTaskHandle::cancel)
        activeHandles.clear()
        runningDownloadIds.clear()
        hasLoaded = false
        _uiState.value = DownloadsUiState()
        notifyLiveStatusPlatform()
    }

    fun findPlayableDownloadByVideoId(videoId: String?): DownloadItem? {
        ensureLoaded()
        val normalizedVideoId = videoId?.trim().orEmpty()
        if (normalizedVideoId.isBlank()) return null
        return _uiState.value.items.firstOrNull { item ->
            item.videoId == normalizedVideoId && item.hasPlayableLocalFile()
        }
    }

    fun findPlayableDownload(
        parentMetaId: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        videoId: String? = null,
    ): DownloadItem? {
        ensureLoaded()
        val items = _uiState.value.items
        val normalizedParentMetaId = parentMetaId.trim()

        findPlayableDownloadByVideoId(videoId)?.let { return it }

        return if (seasonNumber != null && episodeNumber != null) {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == seasonNumber &&
                    item.episodeNumber == episodeNumber &&
                    item.hasPlayableLocalFile()
            }
        } else {
            items.firstOrNull { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    item.seasonNumber == null &&
                    item.episodeNumber == null &&
                    item.hasPlayableLocalFile()
            }
        }
    }

    fun playableLocalFileUri(item: DownloadItem): String? {
        ensureLoaded()
        if (item.status != DownloadStatus.Completed) return null
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return null

        if (resolvedUri != item.localFileUri) {
            mutateItem(item.id) { current ->
                if (current.fileName == item.fileName) {
                    current.copy(
                        localFileUri = resolvedUri,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                } else {
                    current
                }
            }
        }

        return resolvedUri
    }

    fun enqueueFromStream(
        contentType: String,
        videoId: String,
        parentMetaId: String,
        parentMetaType: String,
        title: String,
        logo: String?,
        poster: String?,
        background: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        episodeThumbnail: String?,
        stream: StreamItem,
    ): DownloadEnqueueResult {
        ensureLoaded()

        val sourceUrl = stream.playableDirectUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return DownloadEnqueueResult.MissingUrl

        if (!sourceUrl.isSupportedDownloadUrl()) {
            return DownloadEnqueueResult.UnsupportedFormat
        }

        val now = DownloadsClock.nowEpochMs()
        val logicalKey = buildLogicalKey(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
        )

        var replacedExisting = false
        val currentItems = _uiState.value.items.toMutableList()
        val existing = currentItems.firstOrNull { it.logicalContentKey == logicalKey }
        if (existing != null) {
            replacedExisting = true
            cancelTransfer(existing.id)
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(existing) ?: existing.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(existing.fileName)
            currentItems.removeAll { it.id == existing.id }
        }

        val downloadId = nextDownloadId(now)
        val fileName = buildFileName(
            title = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            fallbackTitle = stream.streamLabel,
            sourceUrl = sourceUrl,
            nowEpochMs = now,
        )

        val item = DownloadItem(
            id = downloadId,
            contentType = contentType,
            parentMetaId = parentMetaId,
            parentMetaType = parentMetaType,
            videoId = videoId,
            title = title,
            logo = logo,
            poster = poster,
            background = background,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeTitle = episodeTitle,
            episodeThumbnail = episodeThumbnail,
            streamTitle = stream.streamLabel,
            streamSubtitle = stream.streamSubtitle,
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            sourceUrl = sourceUrl,
            sourceHeaders = sanitizeRequestHeaders(stream.behaviorHints.proxyHeaders?.request),
            sourceResponseHeaders = sanitizeResponseHeaders(stream.behaviorHints.proxyHeaders?.response),
            localFileUri = null,
            fileName = fileName,
            status = DownloadStatus.Queued,
            downloadedBytes = 0L,
            totalBytes = null,
            errorMessage = null,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )

        currentItems.add(0, item)
        publish(currentItems)
        persist()
        pumpTransferQueue()

        return if (replacedExisting) {
            DownloadEnqueueResult.Replaced
        } else {
            DownloadEnqueueResult.Started
        }
    }

    /**
     * True when the episode already has a download that is finished or still making progress, so a
     * bulk season download can skip re-fetching it.
     */
    fun hasDownloadFor(parentMetaId: String, seasonNumber: Int?, episodeNumber: Int?): Boolean {
        ensureLoaded()
        val logicalKey = buildLogicalKey(parentMetaId, seasonNumber, episodeNumber)
        return _uiState.value.items.any { item ->
            item.logicalContentKey == logicalKey && item.status != DownloadStatus.Failed
        }
    }

    fun pauseDownload(downloadId: String) {
        pauseDownloads(listOf(downloadId))
    }

    /** Pauses every given transfer in one state update, then refills the freed queue slots. */
    fun pauseDownloads(downloadIds: Collection<String>) {
        ensureLoaded()
        val targetIds = downloadIds.toSet()
        if (targetIds.isEmpty()) return

        val pausableIds = _uiState.value.items
            .filter { it.id in targetIds && it.status.isPausable }
            .map { it.id }
            .toSet()
        if (pausableIds.isEmpty()) return

        pausableIds.forEach(::cancelTransfer)

        val now = DownloadsClock.nowEpochMs()
        publish(
            _uiState.value.items.map { item ->
                if (item.id in pausableIds) {
                    item.copy(
                        status = DownloadStatus.Paused,
                        updatedAtEpochMs = now,
                        errorMessage = null,
                    )
                } else {
                    item
                }
            },
        )
        persist()
        pumpTransferQueue()
    }

    fun pauseActiveDownloads() {
        ensureLoaded()
        pauseDownloads(
            _uiState.value.items
                .filter { it.status.isPausable }
                .map { it.id },
        )
    }

    fun resumeDownload(downloadId: String) {
        resumeDownloads(listOf(downloadId))
    }

    /**
     * Re-queues every given transfer. Resumed items go back to [DownloadStatus.Queued] rather than
     * starting at once, so resuming a whole season still honours [MaxConcurrentDownloads].
     */
    fun resumeDownloads(downloadIds: Collection<String>) {
        ensureLoaded()
        val targetIds = downloadIds.toSet()
        if (targetIds.isEmpty()) return

        val resumableIds = _uiState.value.items
            .filter { it.id in targetIds && it.status.isResumable }
            .map { it.id }
            .toSet()
        if (resumableIds.isEmpty()) return

        val now = DownloadsClock.nowEpochMs()
        publish(
            _uiState.value.items.map { item ->
                if (item.id in resumableIds) {
                    item.copy(
                        status = DownloadStatus.Queued,
                        errorMessage = null,
                        localFileUri = null,
                        updatedAtEpochMs = now,
                    )
                } else {
                    item
                }
            },
        )
        persist()
        pumpTransferQueue()
    }

    fun retryDownload(downloadId: String) {
        resumeDownload(downloadId)
    }

    fun cancelDownload(downloadId: String) {
        cancelDownloads(listOf(downloadId))
    }

    /** Cancels the given transfers and deletes both their finished files and any `.part` leftovers. */
    fun cancelDownloads(downloadIds: Collection<String>) {
        ensureLoaded()
        val targetIds = downloadIds.toSet()
        if (targetIds.isEmpty()) return

        val targets = _uiState.value.items.filter { it.id in targetIds }
        if (targets.isEmpty()) return

        targets.forEach { item ->
            cancelTransfer(item.id)
            DownloadsPlatformDownloader.removeFile(playableLocalFileUri(item) ?: item.localFileUri)
            DownloadsPlatformDownloader.removePartialFile(item.fileName)
        }

        publish(_uiState.value.items.filterNot { it.id in targetIds })
        persist()
        pumpTransferQueue()
    }

    // -- Series-level bulk actions -------------------------------------------------------------

    /**
     * Ids of the show's episode downloads, optionally narrowed to a single season. Passing a null
     * [seasonNumber] targets the whole show.
     */
    private fun seriesDownloadIds(
        parentMetaId: String,
        seasonNumber: Int?,
        predicate: (DownloadItem) -> Boolean,
    ): List<String> {
        ensureLoaded()
        val normalizedParentMetaId = parentMetaId.trim()
        return _uiState.value.items
            .filter { item ->
                item.parentMetaId == normalizedParentMetaId &&
                    (seasonNumber == null || item.seasonNumber == seasonNumber) &&
                    predicate(item)
            }
            .map { it.id }
    }

    fun pauseSeriesDownloads(parentMetaId: String, seasonNumber: Int? = null) {
        pauseDownloads(seriesDownloadIds(parentMetaId, seasonNumber) { it.status.isPausable })
    }

    fun resumeSeriesDownloads(parentMetaId: String, seasonNumber: Int? = null) {
        resumeDownloads(seriesDownloadIds(parentMetaId, seasonNumber) { it.status.isResumable })
    }

    fun deleteSeriesDownloads(parentMetaId: String, seasonNumber: Int? = null) {
        cancelDownloads(seriesDownloadIds(parentMetaId, seasonNumber) { true })
    }

    // -- Transfer queue ------------------------------------------------------------------------

    /**
     * Starts queued transfers until [MaxConcurrentDownloads] are running. Safe to call from a
     * download callback: the guard stops a synchronous `onSuccess` from re-entering the loop.
     */
    private fun pumpTransferQueue() {
        if (isPumpingQueue) return
        isPumpingQueue = true
        try {
            while (runningDownloadIds.size < MaxConcurrentDownloads) {
                val next = _uiState.value.items
                    .filter { it.status == DownloadStatus.Queued && it.id !in runningDownloadIds }
                    .minWithOrNull(transferQueueComparator)
                    ?: return

                mutateItem(next.id) { current ->
                    if (current.status != DownloadStatus.Queued) {
                        current
                    } else {
                        current.copy(
                            status = DownloadStatus.Downloading,
                            errorMessage = null,
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                }

                val started = _uiState.value.items.firstOrNull { it.id == next.id }
                if (started == null || started.status != DownloadStatus.Downloading) return
                startDownload(started)
            }
        } finally {
            isPumpingQueue = false
        }
    }

    private fun loadFromDisk() {
        hasLoaded = true
        val payload = DownloadsStorage.loadPayload().orEmpty().trim()
        if (payload.isEmpty()) {
            _uiState.value = DownloadsUiState()
            notifyLiveStatusPlatform()
            return
        }

        var shouldPersistNormalized = false
        val normalized = DownloadsCodec.decodeItems(payload)
            .map { item ->
                // Nothing survives a process restart, so anything mid-flight (running or waiting
                // for a queue slot) comes back paused for the user to resume deliberately.
                val statusNormalized = if (
                    item.status == DownloadStatus.Downloading ||
                    item.status == DownloadStatus.Queued
                ) {
                    item.copy(
                        status = DownloadStatus.Paused,
                        errorMessage = null,
                    )
                } else {
                    item
                }

                val localUriNormalized = normalizeCompletedLocalFileUri(statusNormalized)
                if (localUriNormalized != item) {
                    shouldPersistNormalized = true
                }
                localUriNormalized
            }

        _uiState.value = DownloadsUiState(normalized)
        notifyLiveStatusPlatform()
        if (shouldPersistNormalized) {
            persist()
        }
    }

    /** Frees the queue slot held by a finished transfer. */
    private fun releaseTransferSlot(downloadId: String) {
        runningDownloadIds.remove(downloadId)
        activeHandles.remove(downloadId)
    }

    /** Frees the slot *and* aborts the in-flight transfer. */
    private fun cancelTransfer(downloadId: String) {
        runningDownloadIds.remove(downloadId)
        activeHandles.remove(downloadId)?.cancel()
    }

    private fun startDownload(item: DownloadItem, attempt: Int = 1) {
        runningDownloadIds.add(item.id)
        val request = DownloadPlatformRequest(
            sourceUrl = item.sourceUrl,
            sourceHeaders = item.sourceHeaders,
            destinationFileName = item.fileName,
        )

        val handle = DownloadsPlatformDownloader.start(
            request = request,
            onProgress = { downloadedBytes, totalBytes ->
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
                            totalBytes = totalBytes?.takeIf { it > 0L },
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                            errorMessage = null,
                        )
                    }
                }
            },
            onSuccess = { localFileUri, totalBytes ->
                releaseTransferSlot(item.id)
                mutateItem(item.id) { current ->
                    current.copy(
                        status = DownloadStatus.Completed,
                        localFileUri = localFileUri,
                        downloadedBytes = if (totalBytes != null && totalBytes > 0L) {
                            totalBytes
                        } else {
                            current.downloadedBytes
                        },
                        totalBytes = totalBytes?.takeIf { it > 0L } ?: current.totalBytes,
                        errorMessage = null,
                        updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                    )
                }
                pumpTransferQueue()
            },
            onFailure = onFailure@ { message ->
                releaseTransferSlot(item.id)
                val current = _uiState.value.items.firstOrNull { it.id == item.id }
                if (current?.status == DownloadStatus.Downloading && attempt < MaxDownloadAttempts) {
                    startDownload(current, attempt + 1)
                    return@onFailure
                }
                mutateItem(item.id) { current ->
                    if (current.status != DownloadStatus.Downloading) {
                        current
                    } else {
                        current.copy(
                            status = DownloadStatus.Failed,
                            errorMessage = message.ifBlank { runBlocking { getString(Res.string.download_failed) } },
                            updatedAtEpochMs = DownloadsClock.nowEpochMs(),
                        )
                    }
                }
                // A failure frees a slot just like a success does — let the next episode start.
                pumpTransferQueue()
            },
        )

        // The transfer can complete on its IO thread before start() returns, in which case the slot
        // has already been released and this handle refers to finished work.
        if (item.id in runningDownloadIds) {
            activeHandles[item.id] = handle
        } else {
            handle.cancel()
        }
    }

    private fun mutateItem(downloadId: String, transform: (DownloadItem) -> DownloadItem) {
        var changed = false
        val updated = _uiState.value.items.map { item ->
            if (item.id == downloadId) {
                changed = true
                transform(item)
            } else {
                item
            }
        }

        if (changed) {
            publish(updated)
            persist()
        }
    }

    private fun replaceItem(item: DownloadItem) {
        val updated = _uiState.value.items.map { existing ->
            if (existing.id == item.id) item else existing
        }
        publish(updated)
    }

    private fun publish(items: List<DownloadItem>) {
        _uiState.value = DownloadsUiState(
            items = items,
        )
        notifyLiveStatusPlatform()
    }

    private fun notifyLiveStatusPlatform() {
        runCatching {
            DownloadsLiveStatusPlatform.onItemsChanged(_uiState.value.items)
        }
    }

    private fun persist() {
        DownloadsStorage.savePayload(
            DownloadsCodec.encodeItems(_uiState.value.items),
        )
    }

    private fun nextDownloadId(nowEpochMs: Long): String {
        nextDownloadOrdinal += 1L
        return buildString {
            append(nowEpochMs.toString(36))
            append('_')
            append(nextDownloadOrdinal.toString(36))
        }
    }

    private fun normalizeCompletedLocalFileUri(item: DownloadItem): DownloadItem {
        if (item.status != DownloadStatus.Completed) return item
        val resolvedUri = DownloadsPlatformDownloader.resolveLocalFileUri(
            localFileUri = item.localFileUri,
            destinationFileName = item.fileName,
        ) ?: return item
        return if (resolvedUri != item.localFileUri) {
            item.copy(localFileUri = resolvedUri)
        } else {
            item
        }
    }

    private fun DownloadItem.hasPlayableLocalFile(): Boolean =
        status == DownloadStatus.Completed &&
            DownloadsPlatformDownloader.resolveLocalFileUri(
                localFileUri = localFileUri,
                destinationFileName = fileName,
            ) != null
}

/** A running or queued transfer can be paused; a finished one cannot. */
private val DownloadStatus.isPausable: Boolean
    get() = this == DownloadStatus.Downloading || this == DownloadStatus.Queued

/** Paused and failed transfers can both be put back on the queue. */
private val DownloadStatus.isResumable: Boolean
    get() = this == DownloadStatus.Paused || this == DownloadStatus.Failed

/**
 * Queue order. Enqueue time comes first so a show queued later never jumps ahead of one already
 * waiting; season/episode break ties within a bulk batch, whose items all share a timestamp,
 * so a season downloads in viewing order rather than arbitrarily.
 */
private val transferQueueComparator: Comparator<DownloadItem> =
    compareBy<DownloadItem> { it.createdAtEpochMs }
        .thenBy { it.seasonNumber ?: Int.MAX_VALUE }
        .thenBy { it.episodeNumber ?: Int.MAX_VALUE }
        .thenBy { it.id }

@Serializable
private data class StoredDownloadsPayload(
    val items: List<DownloadItem> = emptyList(),
)

private object DownloadsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeItems(payload: String): List<DownloadItem> =
        runCatching {
            json.decodeFromString<StoredDownloadsPayload>(payload).items
        }.getOrDefault(emptyList())

    fun encodeItems(items: Collection<DownloadItem>): String =
        json.encodeToString(
            StoredDownloadsPayload(
                items = items.toList(),
            ),
        )
}

private fun sanitizeRequestHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (
                normalizedKey.isBlank() ||
                normalizedValue.isBlank() ||
                normalizedKey.equals("Accept-Encoding", ignoreCase = true) ||
                normalizedKey.equals("Range", ignoreCase = true)
            ) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun sanitizeResponseHeaders(headers: Map<String, String>?): Map<String, String> =
    headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            val normalizedValue = value.trim()
            if (normalizedKey.isBlank() || normalizedValue.isBlank()) {
                null
            } else {
                normalizedKey to normalizedValue
            }
        }
        .toMap()

private fun buildLogicalKey(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
): String = if (seasonNumber != null && episodeNumber != null) {
    "${parentMetaId.trim()}|$seasonNumber|$episodeNumber"
} else {
    "${parentMetaId.trim()}|movie"
}

private fun buildFileName(
    title: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    episodeTitle: String?,
    fallbackTitle: String,
    sourceUrl: String,
    nowEpochMs: Long,
): String {
    val baseTitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append(title)
            append(" S")
            append(seasonNumber.toString().padStart(2, '0'))
            append('E')
            append(episodeNumber.toString().padStart(2, '0'))
            if (!episodeTitle.isNullOrBlank()) {
                append(' ')
                append(episodeTitle)
            }
        }
    } else {
        title.ifBlank { fallbackTitle }
    }

    val extension = sourceUrl.fileExtensionFromUrl()
    return buildString {
        append(baseTitle.sanitizeFileName().ifBlank { "download" }.take(92))
        append('_')
        append(nowEpochMs.toString(36))
        append('.')
        append(extension)
    }
}

private fun String.sanitizeFileName(): String =
    trim().replace(Regex("[^A-Za-z0-9._ -]"), "_")

private fun String.fileExtensionFromUrl(): String {
    val withoutQuery = substringBefore('?').substringBefore('#')
    val suffix = withoutQuery.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
        .trim()

    return if (suffix.length in 2..5 && suffix.all { it.isLetterOrDigit() }) {
        suffix
    } else {
        "mp4"
    }
}

private fun String.isSupportedDownloadUrl(): Boolean {
    val normalized = trim().lowercase()
    if (normalized.startsWith("magnet:")) return false
    if (normalized.endsWith(".m3u8") || normalized.contains(".m3u8?")) return false
    if (normalized.endsWith(".mpd") || normalized.contains(".mpd?")) return false
    if (normalized.endsWith(".torrent") || normalized.contains(".torrent?")) return false
    return normalized.startsWith("http://") || normalized.startsWith("https://")
}
