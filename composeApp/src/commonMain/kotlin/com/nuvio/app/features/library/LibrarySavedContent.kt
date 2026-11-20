package com.nuvio.app.features.library

import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.i18n.localizedMediaTypeLabel
import com.nuvio.app.core.ui.NuvioDropdownChip
import com.nuvio.app.core.ui.NuvioDropdownOption
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.PosterGridRow
import com.nuvio.app.features.home.components.PosterGridSkeletonRow
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_filter_all
import nuvio.composeapp.generated.resources.library_filter_genre
import nuvio.composeapp.generated.resources.library_filter_year
import nuvio.composeapp.generated.resources.library_filter_watched
import nuvio.composeapp.generated.resources.library_watched_filter_watched
import nuvio.composeapp.generated.resources.library_watched_filter_unwatched
import nuvio.composeapp.generated.resources.library_filter_all_types
import nuvio.composeapp.generated.resources.library_filter_list
import nuvio.composeapp.generated.resources.library_filter_sort
import nuvio.composeapp.generated.resources.library_filter_type
import nuvio.composeapp.generated.resources.library_sort_added_asc
import nuvio.composeapp.generated.resources.library_sort_added_desc
import nuvio.composeapp.generated.resources.library_sort_title_asc
import nuvio.composeapp.generated.resources.library_sort_title_desc
import nuvio.composeapp.generated.resources.library_sort_provider_order
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibrarySavedControls(
    layoutMode: LibraryLayoutMode,
    sourceMode: LibrarySourceMode,
    sortOption: LibrarySortOption,
    verticalProjection: LibraryVerticalProjection,
    onSectionSelected: (String) -> Unit,
    onTypeSelected: (String?) -> Unit,
    onSortSelected: (LibrarySortOption) -> Unit,
    watchedFilter: LibraryWatchedFilter = LibraryWatchedFilter.ALL,
    onGenreSelected: (String?) -> Unit = {},
    onYearSelected: (String?) -> Unit = {},
    onWatchedFilterSelected: (LibraryWatchedFilter) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sortOptions = availableLibrarySortOptions(sourceMode)
    val allTypesLabel = stringResource(Res.string.library_filter_all_types)

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (layoutMode == LibraryLayoutMode.VERTICAL && sourceMode.isRemoteTrackingSource) {
            val selectedSection = verticalProjection.availableSections
                .firstOrNull { section -> section.type == verticalProjection.selectedSectionKey }
            NuvioDropdownChip(
                title = stringResource(Res.string.library_filter_list),
                label = selectedSection?.displayTitle.orEmpty(),
                selectedKey = verticalProjection.selectedSectionKey,
                options = verticalProjection.availableSections.map { section ->
                    NuvioDropdownOption(key = section.type, label = section.displayTitle)
                },
                enabled = verticalProjection.availableSections.size > 1,
                onSelected = { option -> onSectionSelected(option.key) },
            )
        }

        if (layoutMode == LibraryLayoutMode.VERTICAL) {
            val typeOptions = buildList {
                add(NuvioDropdownOption(key = "", label = allTypesLabel))
                addAll(
                    verticalProjection.availableTypes.map { type ->
                        NuvioDropdownOption(key = type, label = localizedMediaTypeLabel(type))
                    },
                )
            }
            NuvioDropdownChip(
                title = stringResource(Res.string.library_filter_type),
                label = verticalProjection.selectedType
                    ?.let(::localizedMediaTypeLabel)
                    ?: allTypesLabel,
                selectedKey = verticalProjection.selectedType.orEmpty(),
                options = typeOptions,
                enabled = typeOptions.size > 1,
                onSelected = { option -> onTypeSelected(option.key.ifBlank { null }) },
            )
        }

        if (layoutMode == LibraryLayoutMode.VERTICAL) {
            LibraryFacetControls(
                projection = verticalProjection,
                watchedFilter = watchedFilter,
                onGenreSelected = onGenreSelected,
                onYearSelected = onYearSelected,
                onWatchedFilterSelected = onWatchedFilterSelected,
            )
        }

        NuvioDropdownChip(
            title = stringResource(Res.string.library_filter_sort),
            label = librarySortOptionLabel(sortOption),
            selectedKey = sortOption.name,
            options = sortOptions.map { option ->
                NuvioDropdownOption(key = option.name, label = librarySortOptionLabel(option))
            },
            enabled = sortOptions.size > 1,
            onSelected = { option ->
                LibrarySortOption.entries
                    .firstOrNull { it.name == option.key }
                    ?.let(onSortSelected)
            },
        )
    }
}

@Composable
internal fun LibraryFacetControls(
    projection: LibraryVerticalProjection,
    watchedFilter: LibraryWatchedFilter,
    onGenreSelected: (String?) -> Unit,
    onYearSelected: (String?) -> Unit,
    onWatchedFilterSelected: (LibraryWatchedFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val all = stringResource(Res.string.library_filter_all)
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NuvioDropdownChip(
            title = stringResource(Res.string.library_filter_genre),
            label = projection.selectedGenre ?: all,
            selectedKey = projection.selectedGenre.orEmpty(),
            options = listOf(NuvioDropdownOption("", all)) + projection.availableGenres.map {
                NuvioDropdownOption(it.key, "${it.key} (${it.count})")
            },
            enabled = projection.availableGenres.isNotEmpty(),
            onSelected = { onGenreSelected(it.key.ifBlank { null }) },
        )
        NuvioDropdownChip(
            title = stringResource(Res.string.library_filter_year),
            label = projection.selectedYear ?: all,
            selectedKey = projection.selectedYear.orEmpty(),
            options = listOf(NuvioDropdownOption("", all)) + projection.availableYears.map {
                NuvioDropdownOption(it.key, "${it.key} (${it.count})")
            },
            enabled = projection.availableYears.isNotEmpty(),
            onSelected = { onYearSelected(it.key.ifBlank { null }) },
        )
        val watchedLabel = stringResource(Res.string.library_watched_filter_watched)
        val unwatchedLabel = stringResource(Res.string.library_watched_filter_unwatched)
        NuvioDropdownChip(
            title = stringResource(Res.string.library_filter_watched),
            label = when (watchedFilter) {
                LibraryWatchedFilter.ALL -> all
                LibraryWatchedFilter.WATCHED -> watchedLabel
                LibraryWatchedFilter.UNWATCHED -> unwatchedLabel
            },
            selectedKey = watchedFilter.name,
            options = listOf(
                NuvioDropdownOption(LibraryWatchedFilter.ALL.name, all),
                NuvioDropdownOption(LibraryWatchedFilter.WATCHED.name, watchedLabel),
                NuvioDropdownOption(LibraryWatchedFilter.UNWATCHED.name, unwatchedLabel),
            ),
            onSelected = { option ->
                LibraryWatchedFilter.entries.firstOrNull { it.name == option.key }?.let(onWatchedFilterSelected)
            },
        )
    }
}

internal fun LazyListScope.libraryVerticalContent(
    projection: LibraryVerticalProjection,
    columns: Int,
    horizontalPadding: Dp = 16.dp,
    watchedKeys: Set<String>,
    fullyWatchedSeriesKeys: Set<String>,
    onPosterClick: ((LibraryItem) -> Unit)?,
    onPosterLongClick: ((LibraryItem, LibrarySection) -> Unit)?,
) {
    items(
        items = projection.entries.chunked(columns),
        key = { rowEntries ->
            val firstEntry = rowEntries.first()
            "library-vertical:${firstEntry.item.type}:${firstEntry.item.id}"
        },
    ) { rowEntries ->
        PosterGridRow(
            items = rowEntries.map { entry -> entry.item.toMetaPreview() },
            columns = columns,
            modifier = libraryContentTransitionModifier()
                .padding(horizontal = horizontalPadding),
            watchedKeys = watchedKeys,
            fullyWatchedSeriesKeys = fullyWatchedSeriesKeys,
            onPosterClick = onPosterClick?.let { callback ->
                { preview -> rowEntries.findEntry(preview)?.item?.let(callback) }
            },
            onPosterLongClick = onPosterLongClick?.let { callback ->
                { preview ->
                    rowEntries.findEntry(preview)?.let { entry -> callback(entry.item, entry.section) }
                }
            },
        )
    }
}

internal fun LazyListScope.libraryVerticalSkeletonItems(columns: Int, horizontalPadding: Dp = 16.dp) {
    items(
        count = 2,
        key = { index -> "library-vertical-skeleton:$index" },
    ) {
        PosterGridSkeletonRow(
            columns = columns,
            modifier = libraryContentTransitionModifier()
                .padding(horizontal = horizontalPadding),
        )
    }
}

internal fun LazyItemScope.libraryContentTransitionModifier(): Modifier =
    Modifier.animateItem(
        fadeInSpec = tween(durationMillis = 160),
        placementSpec = tween(durationMillis = 180),
        fadeOutSpec = tween(durationMillis = 90),
    )

@Composable
internal fun librarySortOptionLabel(option: LibrarySortOption): String =
    when (option) {
        LibrarySortOption.DEFAULT -> stringResource(Res.string.library_sort_provider_order)
        LibrarySortOption.ADDED_DESC -> stringResource(Res.string.library_sort_added_desc)
        LibrarySortOption.ADDED_ASC -> stringResource(Res.string.library_sort_added_asc)
        LibrarySortOption.TITLE_ASC -> stringResource(Res.string.library_sort_title_asc)
        LibrarySortOption.TITLE_DESC -> stringResource(Res.string.library_sort_title_desc)
    }

private fun List<LibraryVerticalEntry>.findEntry(preview: MetaPreview): LibraryVerticalEntry? =
    firstOrNull { entry -> entry.item.id == preview.id && entry.item.type == preview.type }
