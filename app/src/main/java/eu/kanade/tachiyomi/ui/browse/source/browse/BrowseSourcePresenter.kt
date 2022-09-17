package eu.kanade.tachiyomi.ui.browse.source.browse

import android.content.res.Configuration
import android.os.Bundle
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.core.prefs.CheckboxState
import eu.kanade.core.prefs.mapAsCheckboxState
import eu.kanade.domain.category.interactor.GetCategories
import eu.kanade.domain.category.interactor.SetMangaCategories
import eu.kanade.domain.chapter.interactor.GetChapterByMangaId
import eu.kanade.domain.chapter.interactor.SyncChaptersWithTrackServiceTwoWay
import eu.kanade.domain.manga.interactor.GetDuplicateLibraryManga
import eu.kanade.domain.manga.interactor.GetManga
import eu.kanade.domain.manga.interactor.InsertManga
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toDbManga
import eu.kanade.domain.manga.model.toMangaUpdate
import eu.kanade.domain.source.interactor.GetRemoteManga
import eu.kanade.domain.track.interactor.InsertTrack
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.presentation.browse.BrowseSourceState
import eu.kanade.presentation.browse.BrowseSourceStateImpl
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.toDomainManga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.ui.browse.source.filter.CheckboxItem
import eu.kanade.tachiyomi.ui.browse.source.filter.CheckboxSectionItem
import eu.kanade.tachiyomi.ui.browse.source.filter.GroupItem
import eu.kanade.tachiyomi.ui.browse.source.filter.HeaderItem
import eu.kanade.tachiyomi.ui.browse.source.filter.SelectItem
import eu.kanade.tachiyomi.ui.browse.source.filter.SelectSectionItem
import eu.kanade.tachiyomi.ui.browse.source.filter.SeparatorItem
import eu.kanade.tachiyomi.ui.browse.source.filter.SortGroup
import eu.kanade.tachiyomi.ui.browse.source.filter.SortItem
import eu.kanade.tachiyomi.ui.browse.source.filter.TextItem
import eu.kanade.tachiyomi.ui.browse.source.filter.TextSectionItem
import eu.kanade.tachiyomi.ui.browse.source.filter.TriStateItem
import eu.kanade.tachiyomi.ui.browse.source.filter.TriStateSectionItem
import eu.kanade.tachiyomi.util.chapter.ChapterSettingsHelper
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.withIOContext
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.system.logcat
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date
import eu.kanade.domain.category.model.Category as DomainCategory
import eu.kanade.domain.manga.model.Manga as DomainManga

open class BrowseSourcePresenter(
    private val sourceId: Long,
    searchQuery: String? = null,
    private val state: BrowseSourceStateImpl = BrowseSourceState(searchQuery) as BrowseSourceStateImpl,
    private val sourceManager: SourceManager = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getRemoteManga: GetRemoteManga = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    private val getChapterByMangaId: GetChapterByMangaId = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val insertManga: InsertManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val insertTrack: InsertTrack = Injekt.get(),
    private val syncChaptersWithTrackServiceTwoWay: SyncChaptersWithTrackServiceTwoWay = Injekt.get(),
) : BasePresenter<BrowseSourceController>(), BrowseSourceState by state {

    private val loggedServices by lazy { Injekt.get<TrackManager>().services.filter { it.isLogged } }

    var displayMode by preferences.sourceDisplayMode().asState()

    val isDownloadOnly: Boolean by preferences.downloadedOnly().asState()
    val isIncognitoMode: Boolean by preferences.incognitoMode().asState()

    @Composable
    fun getColumnsPreferenceForCurrentOrientation(): State<GridCells> {
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        return produceState<GridCells>(initialValue = GridCells.Adaptive(128.dp), isLandscape) {
            (if (isLandscape) preferences.landscapeColumns() else preferences.portraitColumns())
                .changes()
                .collectLatest { columns ->
                    value = if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns)
                }
        }
    }

    @Composable
    fun getMangaList(): Flow<PagingData<DomainManga>> {
        return remember(currentFilter) {
            Pager(
                PagingConfig(pageSize = 25),
            ) {
                getRemoteManga.subscribe(sourceId, currentFilter.query, currentFilter.filters)
            }.flow
                .map {
                    it.map {
                        withIOContext {
                            networkToLocalManga(it, sourceId).toDomainManga()!!
                        }
                    }
                }
                .cachedIn(presenterScope)
        }
    }

    @Composable
    fun getManga(initialManga: DomainManga): State<DomainManga> {
        return produceState(initialValue = initialManga) {
            getManga.subscribe(initialManga.url, initialManga.source)
                .collectLatest { manga ->
                    if (manga == null) return@collectLatest
                    withIOContext {
                        initializeManga(manga)
                    }
                    value = manga
                }
        }
    }

    fun setFilter(filters: FilterList) {
        state.filters = filters
    }

    fun resetFilter() {
        if (currentFilter !is Filter.UserInput) return
        state.currentFilter = (currentFilter as Filter.UserInput).copy(filters = source!!.getFilterList())
    }

    fun search(query: String? = null) {
        var new = Filter.valueOf(query ?: searchQuery ?: "")
        if (new is Filter.UserInput && currentFilter is Filter.UserInput) {
            new = new.copy(filters = currentFilter.filters)
        }
        state.currentFilter = new
    }

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        state.source = sourceManager.get(sourceId) as? CatalogueSource ?: return
        state.filters = source!!.getFilterList()
    }

    /**
     * Returns a manga from the database for the given manga from network. It creates a new entry
     * if the manga is not yet in the database.
     *
     * @param sManga the manga from the source.
     * @return a manga from the database.
     */
    private suspend fun networkToLocalManga(sManga: SManga, sourceId: Long): Manga {
        var localManga = getManga.await(sManga.url, sourceId)
        if (localManga == null) {
            val newManga = Manga.create(sManga.url, sManga.title, sourceId)
            newManga.copyFrom(sManga)
            newManga.id = -1
            val id = insertManga.await(newManga.toDomainManga()!!)
            val result = getManga.await(id!!)
            localManga = result
        } else if (!localManga.favorite) {
            // if the manga isn't a favorite, set its display title from source
            // if it later becomes a favorite, updated title will go to db
            localManga = localManga.copy(title = sManga.title)
        }
        return localManga?.toDbManga()!!
    }

    /**
     * Initialize a manga.
     *
     * @param manga to initialize.
     */
    private suspend fun initializeManga(manga: DomainManga) {
        if (manga.thumbnailUrl != null || manga.initialized) return
        withContext(NonCancellable) {
            val db = manga.toDbManga()
            try {
                val networkManga = source!!.getMangaDetails(db.copy())
                db.copyFrom(networkManga)
                db.initialized = true
                updateManga.await(
                    db
                        .toDomainManga()
                        ?.toMangaUpdate()!!,
                )
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
            }
        }
    }

    /**
     * Adds or removes a manga from the library.
     *
     * @param manga the manga to update.
     */
    fun changeMangaFavorite(manga: DomainManga) {
        presenterScope.launch {
            var new = manga.copy(
                favorite = !manga.favorite,
                dateAdded = when (manga.favorite) {
                    true -> Date().time
                    false -> 0
                },
            )

            if (!new.favorite) {
                new = new.removeCovers(coverCache)
            } else {
                ChapterSettingsHelper.applySettingDefaults(manga.id)

                autoAddTrack(manga)
            }

            updateManga.await(new.toMangaUpdate())
        }
    }

    fun getSourceOrStub(manga: DomainManga): Source {
        return sourceManager.getOrStub(manga.source)
    }

    fun addFavorite(manga: DomainManga) {
        presenterScope.launch {
            val categories = getCategories()
            val defaultCategoryId = preferences.defaultCategory().get()
            val defaultCategory = categories.find { it.id == defaultCategoryId.toLong() }

            when {
                // Default category set
                defaultCategory != null -> {
                    moveMangaToCategories(manga, defaultCategory)

                    changeMangaFavorite(manga)
                    // activity.toast(activity.getString(R.string.manga_added_library))
                }

                // Automatic 'Default' or no categories
                defaultCategoryId == 0 || categories.isEmpty() -> {
                    moveMangaToCategories(manga)

                    changeMangaFavorite(manga)
                    // activity.toast(activity.getString(R.string.manga_added_library))
                }

                // Choose a category
                else -> {
                    val preselectedIds = getCategories.await(manga.id).map { it.id }
                    state.dialog = Dialog.ChangeMangaCategory(manga, categories.mapAsCheckboxState { it.id in preselectedIds })
                }
            }
        }
    }

    private suspend fun autoAddTrack(manga: DomainManga) {
        loggedServices
            .filterIsInstance<EnhancedTrackService>()
            .filter { it.accept(source!!) }
            .forEach { service ->
                try {
                    service.match(manga.toDbManga())?.let { track ->
                        track.manga_id = manga.id
                        (service as TrackService).bind(track)
                        insertTrack.await(track.toDomainTrack()!!)

                        val chapters = getChapterByMangaId.await(manga.id)
                        syncChaptersWithTrackServiceTwoWay.await(chapters, track.toDomainTrack()!!, service)
                    }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Could not match manga: ${manga.title} with service $service" }
                }
            }
    }

    /**
     * Set the filter states for the current source.
     *
     * @param filters a list of active filters.
     */
    fun setSourceFilter(filters: FilterList) {
        state.currentFilter = when (val filter = currentFilter) {
            Filter.Latest, Filter.Popular -> Filter.UserInput(filters = filters)
            is Filter.UserInput -> filter.copy(filters = filters)
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<DomainCategory> {
        return getCategories.subscribe()
            .firstOrNull()
            ?.filterNot { it.isSystemCategory }
            ?: emptyList()
    }

    suspend fun getDuplicateLibraryManga(manga: DomainManga): DomainManga? {
        return getDuplicateLibraryManga.await(manga.title, manga.source)
    }

    fun moveMangaToCategories(manga: DomainManga, vararg categories: DomainCategory) {
        moveMangaToCategories(manga, categories.filter { it.id != 0L }.map { it.id })
    }

    fun moveMangaToCategories(manga: DomainManga, categoryIds: List<Long>) {
        presenterScope.launchIO {
            setMangaCategories.await(
                mangaId = manga.id,
                categoryIds = categoryIds.toList(),
            )
        }
    }

    sealed class Filter(open val query: String, open val filters: FilterList) {
        object Popular : Filter(query = GetRemoteManga.QUERY_POPULAR, filters = FilterList())
        object Latest : Filter(query = GetRemoteManga.QUERY_LATEST, filters = FilterList())
        data class UserInput(override val query: String = "", override val filters: FilterList = FilterList()) : Filter(query = query, filters = filters)

        companion object {
            fun valueOf(query: String): Filter {
                return when (query) {
                    GetRemoteManga.QUERY_POPULAR -> Popular
                    GetRemoteManga.QUERY_LATEST -> Latest
                    else -> UserInput(query = query)
                }
            }
        }
    }

    sealed class Dialog {
        data class RemoveManga(val manga: DomainManga) : Dialog()
        data class AddDuplicateManga(val manga: DomainManga, val duplicate: DomainManga) : Dialog()
        data class ChangeMangaCategory(
            val manga: DomainManga,
            val initialSelection: List<CheckboxState.State<DomainCategory>>,
        ) : Dialog()
    }
}

fun FilterList.toItems(): List<IFlexible<*>> {
    return mapNotNull { filter ->
        when (filter) {
            is Filter.Header -> HeaderItem(filter)
            is Filter.Separator -> SeparatorItem(filter)
            is Filter.CheckBox -> CheckboxItem(filter)
            is Filter.TriState -> TriStateItem(filter)
            is Filter.Text -> TextItem(filter)
            is Filter.Select<*> -> SelectItem(filter)
            is Filter.Group<*> -> {
                val group = GroupItem(filter)
                val subItems = filter.state.mapNotNull {
                    when (it) {
                        is Filter.CheckBox -> CheckboxSectionItem(it)
                        is Filter.TriState -> TriStateSectionItem(it)
                        is Filter.Text -> TextSectionItem(it)
                        is Filter.Select<*> -> SelectSectionItem(it)
                        else -> null
                    }
                }
                subItems.forEach { it.header = group }
                group.subItems = subItems
                group
            }
            is Filter.Sort -> {
                val group = SortGroup(filter)
                val subItems = filter.values.map {
                    SortItem(it, group)
                }
                group.subItems = subItems
                group
            }
        }
    }
}
