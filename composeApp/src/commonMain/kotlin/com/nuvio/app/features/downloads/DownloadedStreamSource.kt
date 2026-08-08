package com.nuvio.app.features.downloads

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.i18n.localizedByteUnit
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamsUiState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.provider_downloaded
import org.jetbrains.compose.resources.stringResource

/**
 * Group id for the synthetic "Downloaded" provider. Chosen to not collide with real providers,
 * which are namespaced `addon:` / `plugin:` / `plugin-repo:` / `debrid:`.
 */
internal const val DownloadedStreamAddonId = "downloaded"

/**
 * A local file already on disk, presented as a source provider alongside the real addons.
 *
 * Playback normally prefers a download automatically and never reaches the sources list — but that
 * preference is skipped for manual selection, which is exactly how a user arrives here. Without
 * this the sources list gives no sign the episode is already downloaded, so it is easy to stream
 * something you already have.
 *
 * Returns null when nothing playable is downloaded for this episode.
 */
@Composable
internal fun rememberDownloadedStreamGroup(
    parentMetaId: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    videoId: String?,
): AddonStreamGroup? {
    if (!AppFeaturePolicy.downloadsEnabled || parentMetaId.isNullOrBlank()) return null

    val downloadsUiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val downloadedLabel = stringResource(Res.string.provider_downloaded)

    // Keyed on items so the entry appears as soon as a download finishes, without a reload.
    return remember(
        downloadsUiState.items,
        parentMetaId,
        seasonNumber,
        episodeNumber,
        videoId,
        downloadedLabel,
    ) {
        val item = DownloadsRepository.findPlayableDownload(
            parentMetaId = parentMetaId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            videoId = videoId,
        ) ?: return@remember null

        val localFileUri = DownloadsRepository.playableLocalFileUri(item) ?: return@remember null

        AddonStreamGroup(
            addonName = downloadedLabel,
            addonId = DownloadedStreamAddonId,
            streams = listOf(
                StreamItem(
                    // Keep the original release name: it tells the user which cut they have on
                    // disk, which is the thing they are comparing against the live sources.
                    name = item.streamTitle.ifBlank { downloadedLabel },
                    description = downloadedStreamDescription(item),
                    url = localFileUri,
                    sourceName = downloadedLabel,
                    addonName = downloadedLabel,
                    addonId = DownloadedStreamAddonId,
                ),
            ),
            isLoading = false,
        )
    }
}

/** Pins the downloaded group to the top of the list, ahead of every network provider. */
internal fun StreamsUiState.withDownloadedGroup(group: AddonStreamGroup?): StreamsUiState {
    if (group == null) return this
    return copy(
        groups = listOf(group) + groups.filterNot { it.addonId == DownloadedStreamAddonId },
        activeAddonIds = activeAddonIds + DownloadedStreamAddonId,
        // A local file is a real result, so the list is no longer empty even if every addon failed.
        emptyStateReason = null,
    )
}

private fun downloadedStreamDescription(item: DownloadItem): String? =
    listOfNotNull(
        formatDownloadBytes(item.totalBytes ?: item.downloadedBytes).takeIf { item.downloadedBytes > 0L },
        item.providerName.trim().takeIf { it.isNotBlank() },
    ).joinToString(" • ").takeIf { it.isNotBlank() }

internal fun formatDownloadBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${localizedByteUnit("B")}"
    val kib = 1024.0
    val mib = kib * 1024.0
    val gib = mib * 1024.0
    val value = bytes.toDouble()
    return when {
        value >= gib -> "${((value / gib) * 10.0).toInt() / 10.0} ${localizedByteUnit("GB")}"
        value >= mib -> "${((value / mib) * 10.0).toInt() / 10.0} ${localizedByteUnit("MB")}"
        value >= kib -> "${((value / kib) * 10.0).toInt() / 10.0} ${localizedByteUnit("KB")}"
        else -> "$bytes ${localizedByteUnit("B")}"
    }
}
