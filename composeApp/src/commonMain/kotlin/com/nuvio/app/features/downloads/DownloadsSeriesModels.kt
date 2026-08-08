package com.nuvio.app.features.downloads

/**
 * A show-level view over the flat [DownloadItem] list. Downloads are stored per episode with no
 * series entity, so shows are derived by grouping on [DownloadItem.parentMetaId].
 */
data class DownloadSeriesSummary(
    val parentMetaId: String,
    val parentMetaType: String,
    val title: String,
    val poster: String?,
    val logo: String?,
    val background: String?,
    val seasons: List<DownloadSeasonGroup>,
) {
    val episodes: List<DownloadItem> = seasons.flatMap { it.episodes }

    val episodeCount: Int get() = episodes.size

    val completedCount: Int get() = episodes.count { it.status == DownloadStatus.Completed }

    val downloadingCount: Int get() = episodes.count { it.status == DownloadStatus.Downloading }

    val queuedCount: Int get() = episodes.count { it.status == DownloadStatus.Queued }

    val pausedCount: Int get() = episodes.count { it.status == DownloadStatus.Paused }

    val failedCount: Int get() = episodes.count { it.status == DownloadStatus.Failed }

    val pendingCount: Int get() = episodes.count(DownloadItem::isPending)

    val hasPendingWork: Boolean get() = pendingCount > 0

    val playableEpisodes: List<DownloadItem> get() = episodes.filter(DownloadItem::isPlayable)

    val downloadedBytes: Long get() = episodes.sumOf { it.downloadedBytes.coerceAtLeast(0L) }

    /**
     * Sum of known sizes. Episodes that have not reported a `Content-Length` yet contribute their
     * downloaded byte count, so the total only ever grows as the queue drains.
     */
    val totalBytes: Long
        get() = episodes.sumOf { item ->
            item.totalBytes?.takeIf { it > 0L } ?: item.downloadedBytes.coerceAtLeast(0L)
        }

    /** Byte-weighted progress across every episode of the show, or `null` when nothing is pending. */
    val progressFraction: Float?
        get() {
            if (!hasPendingWork) return null
            val total = totalBytes.takeIf { it > 0L } ?: return 0f
            return (downloadedBytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

data class DownloadSeasonGroup(
    val seasonNumber: Int,
    val episodes: List<DownloadItem>,
) {
    val isSpecials: Boolean get() = seasonNumber == SPECIALS_SEASON_NUMBER

    val completedCount: Int get() = episodes.count { it.status == DownloadStatus.Completed }

    val pendingCount: Int get() = episodes.count(DownloadItem::isPending)

    val hasPendingWork: Boolean get() = pendingCount > 0

    val hasResumableWork: Boolean
        get() = episodes.any { it.status == DownloadStatus.Paused || it.status == DownloadStatus.Failed }

    val hasRunningWork: Boolean
        get() = episodes.any { it.status == DownloadStatus.Downloading || it.status == DownloadStatus.Queued }

    companion object {
        const val SPECIALS_SEASON_NUMBER = 0
    }
}

/**
 * Groups every episode download into per-show summaries, newest activity first so a show that is
 * actively downloading floats above finished ones.
 */
internal fun List<DownloadItem>.toDownloadSeriesSummaries(): List<DownloadSeriesSummary> =
    filter(DownloadItem::isEpisode)
        .groupBy { it.parentMetaId }
        .mapNotNull { (parentMetaId, episodes) ->
            val ordered = episodes.sortedForSeriesDownloads()
            val reference = ordered.firstOrNull() ?: return@mapNotNull null
            DownloadSeriesSummary(
                parentMetaId = parentMetaId,
                parentMetaType = reference.parentMetaType,
                title = ordered.firstNotNullOfOrNull { it.title.trim().takeIf(String::isNotBlank) }
                    ?: reference.title,
                poster = ordered.firstArtwork(DownloadItem::poster),
                logo = ordered.firstArtwork(DownloadItem::logo),
                background = ordered.firstArtwork(DownloadItem::background),
                seasons = ordered.toDownloadSeasonGroups(),
            )
        }
        .sortedWith(
            compareByDescending<DownloadSeriesSummary> { it.hasPendingWork }
                .thenBy { it.title.lowercase() },
        )

internal fun List<DownloadItem>.toDownloadSeasonGroups(): List<DownloadSeasonGroup> =
    groupBy { it.seasonNumber ?: DownloadSeasonGroup.SPECIALS_SEASON_NUMBER }
        .map { (seasonNumber, episodes) ->
            DownloadSeasonGroup(
                seasonNumber = seasonNumber,
                episodes = episodes.sortedForSeriesDownloads(),
            )
        }
        // Specials (season 0) sort ahead of numbered seasons, matching the details screen.
        .sortedWith(
            compareBy<DownloadSeasonGroup> { if (it.isSpecials) 0 else 1 }
                .thenBy { it.seasonNumber },
        )

/**
 * The show artwork stored on each episode comes from whichever screen enqueued it, so an episode
 * downloaded from a sparse context can be missing images. Take the first episode that has one.
 */
private fun List<DownloadItem>.firstArtwork(select: (DownloadItem) -> String?): String? =
    firstNotNullOfOrNull { select(it)?.trim()?.takeIf(String::isNotBlank) }
