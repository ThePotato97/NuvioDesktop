package com.nuvio.app.features.downloads

import com.nuvio.app.features.details.MetaVideo

/**
 * The downloaded episodes of a show, shaped as [MetaVideo] so player code that reasons about
 * episode order can consume them unchanged.
 *
 * The player normally gets its episode list from `MetaDetailsRepository`, which needs the network
 * or a warm cache. Offline that list is empty, so next-episode resolution — and therefore autoplay
 * — stops working even when the following episodes are sitting on disk. This is the offline
 * fallback: it describes exactly what can actually be played right now.
 *
 * Only playable (finished, file still present) episodes are included, so autoplay never advances to
 * an episode that is still downloading.
 */
internal fun downloadedEpisodesAsMetaVideos(parentMetaId: String): List<MetaVideo> {
    DownloadsRepository.ensureLoaded()
    val normalizedParentMetaId = parentMetaId.trim()
    if (normalizedParentMetaId.isEmpty()) return emptyList()

    return DownloadsRepository.uiState.value.items
        .asSequence()
        .filter { it.parentMetaId == normalizedParentMetaId && it.isEpisode && it.isPlayable }
        .sortedWith(downloadSeriesEpisodeComparator)
        .map { item ->
            MetaVideo(
                id = item.videoId,
                title = item.episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: item.title,
                // Left null on purpose: an episode that is already downloaded has necessarily
                // aired, and `hasEpisodeAired(null)` treats a missing date as aired.
                released = null,
                thumbnail = item.episodeThumbnail,
                season = item.seasonNumber,
                episode = item.episodeNumber,
            )
        }
        .toList()
}
