package com.nuvio.app.features.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioToastController
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenDownload: (DownloadItem) -> Unit,
    initialShowId: String? = null,
    onNavigateToShow: ((showId: String, title: String) -> Unit)? = null,
    onBackFromShow: (() -> Unit)? = null,
) {
    val uiState by remember {
        DownloadsRepository.ensureLoaded()
        DownloadsRepository.uiState
    }.collectAsStateWithLifecycle()
    val seriesDownloadState by SeriesDownloadCoordinator.uiState.collectAsStateWithLifecycle()

    var selectedShowId by rememberSaveable(initialShowId) { mutableStateOf(initialShowId) }
    var pendingShowDeletion by remember { mutableStateOf<DownloadSeriesSummary?>(null) }
    val openDownloadsDirectoryFailedText = stringResource(Res.string.downloads_open_directory_failed)

    // Every episode of the show, not just finished ones, so a season in flight is browsable while
    // it downloads.
    val series = remember(uiState.items) { uiState.items.toDownloadSeriesSummaries() }
    val selectedShow = remember(selectedShowId, series) {
        selectedShowId?.let { showId -> series.firstOrNull { it.parentMetaId == showId } }
    }

    NuvioScreen {
        stickyHeader {
            NuvioScreenHeader(
                title = if (selectedShowId == null) {
                    stringResource(Res.string.compose_settings_root_downloads_title)
                } else {
                    selectedShow?.title ?: stringResource(Res.string.downloads_show_downloads)
                },
                onBack = {
                    if (selectedShowId != null) {
                        onBackFromShow?.invoke() ?: run { selectedShowId = null }
                    } else {
                        onBack()
                    }
                },
                actions = {
                    val show = selectedShow
                    if (show != null) {
                        IconButton(onClick = { pendingShowDeletion = show }) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = stringResource(Res.string.downloads_delete_show),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            if (!DownloadsPlatformDownloader.openDownloadsDirectory()) {
                                NuvioToastController.show(openDownloadsDirectoryFailedText)
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Folder,
                            contentDescription = stringResource(Res.string.downloads_open_directory),
                        )
                    }
                },
            )
        }

        seriesDownloadBanners(
            progressList = if (selectedShowId == null) {
                seriesDownloadState.activeJobs
            } else {
                listOfNotNull(seriesDownloadState.progressFor(selectedShowId.orEmpty())?.takeIf { !it.isFinished })
            },
        )

        val show = selectedShow
        if (selectedShowId == null) {
            downloadsRootContent(
                uiState = uiState,
                series = series,
                onOpenDownload = onOpenDownload,
                onOpenShow = { showId, title ->
                    onNavigateToShow?.invoke(showId, title) ?: run { selectedShowId = showId }
                },
            )
        } else if (show != null) {
            downloadsShowContent(
                show = show,
                onOpenDownload = onOpenDownload,
            )
        } else {
            item { EmptyMessage(stringResource(Res.string.downloads_empty_episodes)) }
        }
    }

    pendingShowDeletion?.let { show ->
        AlertDialog(
            onDismissRequest = { pendingShowDeletion = null },
            title = { Text(stringResource(Res.string.downloads_delete_show)) },
            text = {
                Text(stringResource(Res.string.downloads_delete_show_message, show.episodeCount, show.title))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        SeriesDownloadCoordinator.cancel(show.parentMetaId)
                        DownloadsRepository.deleteSeriesDownloads(show.parentMetaId)
                        pendingShowDeletion = null
                        // The show no longer exists, so drop back out of its (now empty) page.
                        if (selectedShowId == show.parentMetaId) {
                            onBackFromShow?.invoke() ?: run { selectedShowId = null }
                        }
                    },
                ) {
                    Text(stringResource(Res.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingShowDeletion = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

/** Progress of the source-resolution pass that precedes a bulk season/show download. */
private fun LazyListScope.seriesDownloadBanners(progressList: List<SeriesDownloadProgress>) {
    if (progressList.isEmpty()) return
    items(
        items = progressList,
        key = { "series-progress-${it.parentMetaId}" },
    ) { progress ->
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(
                        Res.string.downloads_series_finding_sources,
                        progress.title,
                        progress.processedEpisodes,
                        progress.totalEpisodes,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = progress.progressFraction,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = progress.currentEpisodeLabel.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    TextButton(onClick = { SeriesDownloadCoordinator.cancel(progress.parentMetaId) }) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                }
            }
        }
    }
}

private fun LazyListScope.downloadsRootContent(
    uiState: DownloadsUiState,
    series: List<DownloadSeriesSummary>,
    onOpenDownload: (DownloadItem) -> Unit,
    onOpenShow: (showId: String, title: String) -> Unit,
) {
    // Episodes are represented by their show card, which carries the show's aggregate progress, so
    // listing them here as well would duplicate every in-flight episode.
    val activeMovies = uiState.activeItems.filterNot(DownloadItem::isEpisode)
    val completedMovies = uiState.completedItems.filterNot(DownloadItem::isEpisode)

    if (activeMovies.isNotEmpty()) {
        item { SectionTitle(stringResource(Res.string.downloads_section_active)) }
        items(items = activeMovies, key = { it.id }) { item ->
            DownloadRow(item = item, onOpen = { onOpenDownload(item) })
        }
    }

    if (completedMovies.isNotEmpty()) {
        item { SectionTitle(stringResource(Res.string.downloads_section_movies)) }
        items(items = completedMovies, key = { it.id }) { item ->
            DownloadRow(item = item, onOpen = { onOpenDownload(item) })
        }
    }

    if (series.isNotEmpty()) {
        item { SectionTitle(stringResource(Res.string.downloads_section_shows)) }
        items(items = series, key = { it.parentMetaId }) { show ->
            SeriesCard(show = show, onClick = { onOpenShow(show.parentMetaId, show.title) })
        }
    }

    if (uiState.items.isEmpty()) {
        item { EmptyMessage(stringResource(Res.string.downloads_empty_title)) }
    }
}

private fun LazyListScope.downloadsShowContent(
    show: DownloadSeriesSummary,
    onOpenDownload: (DownloadItem) -> Unit,
) {
    if (show.seasons.isEmpty()) {
        item { EmptyMessage(stringResource(Res.string.downloads_empty_episodes)) }
        return
    }

    show.seasons.forEach { season ->
        item(key = "season-header-${season.seasonNumber}") {
            SeasonHeader(parentMetaId = show.parentMetaId, season = season)
        }
        items(items = season.episodes, key = { it.id }) { item ->
            DownloadRow(item = item, onOpen = { onOpenDownload(item) })
        }
    }
}

@Composable
private fun SeriesCard(
    show: DownloadSeriesSummary,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SeriesArtwork(posterUrl = show.poster ?: show.background)

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = show.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = seriesSubtitle(show),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                show.progressFraction?.let { fraction ->
                    LinearProgressIndicator(
                        progress = fraction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    )
                    Text(
                        text = "${formatBytes(show.downloadedBytes)} / ${formatBytes(show.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SeriesArtwork(posterUrl: String?) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .width(46.dp)
            .height(69.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (!posterUrl.isNullOrBlank()) {
            NuvioAsyncImage(
                model = posterUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(69.dp),
            )
        }
    }
}

@Composable
private fun seriesSubtitle(show: DownloadSeriesSummary): String {
    // Counts every episode of the show, including ones still downloading — not just finished files.
    val episodeCount = stringResource(Res.string.downloads_show_episode_count, show.episodeCount)
    val activity = when {
        show.downloadingCount > 0 ->
            stringResource(Res.string.downloads_state_downloading_count, show.downloadingCount)
        show.queuedCount > 0 -> stringResource(Res.string.downloads_state_queued_count, show.queuedCount)
        show.pausedCount > 0 -> stringResource(Res.string.downloads_state_paused_count, show.pausedCount)
        show.failedCount > 0 -> stringResource(Res.string.downloads_state_failed_count, show.failedCount)
        else -> null
    }
    return listOfNotNull(episodeCount, activity).joinToString(" • ")
}

@Composable
private fun SeasonHeader(
    parentMetaId: String,
    season: DownloadSeasonGroup,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (season.isSpecials) {
                    stringResource(Res.string.episodes_specials)
                } else {
                    stringResource(Res.string.episodes_season, season.seasonNumber)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    Res.string.downloads_season_completed_count,
                    season.completedCount,
                    season.episodes.size,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Only one bulk action is ever meaningful: pause what is moving, or restart what is not.
        if (season.hasRunningWork) {
            IconButton(onClick = { DownloadsRepository.pauseSeriesDownloads(parentMetaId, season.seasonNumber) }) {
                Icon(
                    imageVector = Icons.Rounded.Pause,
                    contentDescription = stringResource(Res.string.downloads_pause_season),
                )
            }
        } else if (season.hasResumableWork) {
            IconButton(onClick = { DownloadsRepository.resumeSeriesDownloads(parentMetaId, season.seasonNumber) }) {
                Icon(
                    imageVector = Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(Res.string.downloads_resume_season),
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onOpen: () -> Unit,
) {
    val displayTitle = item.displayTitle()
    val displaySubtitle = downloadDisplaySubtitle(item = item, displayTitle = displayTitle)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(enabled = item.isPlayable, onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = displaySubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusText(item),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (item.status) {
                        DownloadStatus.Downloading, DownloadStatus.Queued -> {
                            IconButton(onClick = { DownloadsRepository.pauseDownload(item.id) }) {
                                Icon(
                                    imageVector = Icons.Rounded.Pause,
                                    contentDescription = stringResource(Res.string.compose_action_pause),
                                )
                            }
                        }
                        DownloadStatus.Paused -> {
                            IconButton(onClick = { DownloadsRepository.resumeDownload(item.id) }) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_resume),
                                )
                            }
                        }
                        DownloadStatus.Failed -> {
                            IconButton(onClick = { DownloadsRepository.retryDownload(item.id) }) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(Res.string.action_retry),
                                )
                            }
                        }
                        DownloadStatus.Completed -> {
                            IconButton(onClick = onOpen) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(Res.string.action_play),
                                )
                            }
                        }
                    }
                    IconButton(onClick = { DownloadsRepository.cancelDownload(item.id) }) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(Res.string.action_delete),
                        )
                    }
                }
            }

            when (item.status) {
                DownloadStatus.Downloading -> {
                    if (item.totalBytes != null && item.totalBytes > 0L) {
                        LinearProgressIndicator(
                            progress = item.progressFraction,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                // A queued item is deliberately flat: it is waiting, not stalled at 0%.
                DownloadStatus.Queued -> {
                    LinearProgressIndicator(
                        progress = 0f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                DownloadStatus.Paused, DownloadStatus.Failed, DownloadStatus.Completed -> Unit
            }
        }
    }
}

private fun DownloadItem.displayTitle(): String =
    if (isEpisode) {
        episodeTitle?.trim()?.takeIf { it.isNotBlank() } ?: title
    } else {
        title
    }

@Composable
private fun downloadDisplaySubtitle(
    item: DownloadItem,
    displayTitle: String,
): String {
    val seasonNumber = item.seasonNumber
    val episodeNumber = item.episodeNumber
    if (seasonNumber == null || episodeNumber == null) {
        return item.displaySubtitle
    }

    val episodeCode = stringResource(
        Res.string.compose_player_episode_code_full,
        seasonNumber,
        episodeNumber,
    )
    return listOf(
        episodeCode,
        item.episodeTitle?.trim().orEmpty().takeIf { it.isNotBlank() && it != displayTitle },
        item.title.trim().takeIf { it.isNotBlank() && it != displayTitle },
    ).filterNotNull().joinToString(" • ")
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun EmptyMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun statusText(item: DownloadItem): String {
    val size = if (item.totalBytes != null && item.totalBytes > 0L) {
        "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
    } else {
        formatBytes(item.downloadedBytes)
    }

    return when (item.status) {
        DownloadStatus.Queued -> stringResource(Res.string.downloads_status_queued)
        DownloadStatus.Downloading -> stringResource(Res.string.downloads_status_downloading, size)
        DownloadStatus.Paused -> stringResource(Res.string.downloads_status_paused, size)
        DownloadStatus.Completed -> stringResource(
            Res.string.downloads_status_completed,
            formatBytes(item.totalBytes ?: item.downloadedBytes),
        )
        DownloadStatus.Failed -> item.errorMessage ?: stringResource(Res.string.downloads_status_failed)
    }
}

private fun formatBytes(bytes: Long): String = formatDownloadBytes(bytes)
