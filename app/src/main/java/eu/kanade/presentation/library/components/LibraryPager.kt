package eu.kanade.presentation.library.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import com.google.accompanist.pager.HorizontalPager
import com.google.accompanist.pager.PagerState
import eu.kanade.core.prefs.PreferenceMutableState
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.library.setting.LibraryDisplayMode

@Composable
fun LibraryPager(
    state: PagerState,
    pageCount: Int,
    selectedManga: List<LibraryManga>,
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    getDisplayModeForPage: @Composable (Int) -> LibraryDisplayMode,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    getLibraryForPage: @Composable (Int) -> List<LibraryItem>,
    onClickManga: (LibraryManga) -> Unit,
    onLongClickManga: (LibraryManga) -> Unit,
) {
    HorizontalPager(
        count = pageCount,
        modifier = Modifier.fillMaxSize(),
        state = state,
        verticalAlignment = Alignment.Top,
    ) { page ->
        if (page !in ((state.currentPage - 1)..(state.currentPage + 1))) {
            // To make sure only one offscreen page is being composed
            return@HorizontalPager
        }
        val library = getLibraryForPage(page)
        val displayMode = getDisplayModeForPage(page)
        val columns by if (displayMode != LibraryDisplayMode.List) {
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            remember(isLandscape) { getColumnsForOrientation(isLandscape) }
        } else {
            remember { mutableStateOf(0) }
        }

        when (displayMode) {
            LibraryDisplayMode.List -> {
                LibraryList(
                    items = library,
                    selection = selectedManga,
                    onClick = onClickManga,
                    onLongClick = onLongClickManga,
                    searchQuery = searchQuery,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                )
            }
            LibraryDisplayMode.CompactGrid -> {
                LibraryCompactGrid(
                    items = library,
                    columns = columns,
                    selection = selectedManga,
                    onClick = onClickManga,
                    onLongClick = onLongClickManga,
                    searchQuery = searchQuery,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                )
            }
            LibraryDisplayMode.ComfortableGrid -> {
                LibraryComfortableGrid(
                    items = library,
                    columns = columns,
                    selection = selectedManga,
                    onClick = onClickManga,
                    onLongClick = onLongClickManga,
                    searchQuery = searchQuery,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                )
            }
            LibraryDisplayMode.CoverOnlyGrid -> {
                LibraryCoverOnlyGrid(
                    items = library,
                    columns = columns,
                    selection = selectedManga,
                    onClick = onClickManga,
                    onLongClick = onLongClickManga,
                    searchQuery = searchQuery,
                    onGlobalSearchClicked = onGlobalSearchClicked,
                )
            }
        }
    }
}
