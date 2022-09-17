package eu.kanade.tachiyomi.data.backup

import android.Manifest
import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import data.Manga_sync
import data.Mangas
import eu.kanade.data.DatabaseHandler
import eu.kanade.domain.category.interactor.GetCategories
import eu.kanade.domain.category.model.Category
import eu.kanade.domain.history.model.HistoryUpdate
import eu.kanade.domain.manga.interactor.GetFavorites
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CATEGORY
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CATEGORY_MASK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CHAPTER
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_CHAPTER_MASK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_HISTORY
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_HISTORY_MASK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_TRACK
import eu.kanade.tachiyomi.data.backup.BackupConst.BACKUP_TRACK_MASK
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupSerializer
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.backupCategoryMapper
import eu.kanade.tachiyomi.data.backup.models.backupChapterMapper
import eu.kanade.tachiyomi.data.backup.models.backupTrackMapper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.copyFrom
import eu.kanade.tachiyomi.util.system.hasPermission
import eu.kanade.tachiyomi.util.system.logcat
import eu.kanade.tachiyomi.util.system.toLong
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import okio.buffer
import okio.gzip
import okio.sink
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.FileOutputStream
import java.util.Date
import kotlin.math.max
import eu.kanade.domain.manga.model.Manga as DomainManga

class BackupManager(
    private val context: Context,
) {

    private val handler: DatabaseHandler = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val preferences: PreferencesHelper = Injekt.get()
    private val getCategories: GetCategories = Injekt.get()
    private val getFavorites: GetFavorites = Injekt.get()

    internal val parser = ProtoBuf

    /**
     * Create backup file from database
     *
     * @param uri path of Uri
     * @param isAutoBackup backup called from scheduled backup job
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun createBackup(uri: Uri, flags: Int, isAutoBackup: Boolean): String {
        if (!context.hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            throw IllegalStateException(context.getString(R.string.missing_storage_permission))
        }

        val databaseManga = getFavorites.await()
        val backup = Backup(
            backupMangas(databaseManga, flags),
            backupCategories(flags),
            emptyList(),
            backupExtensionInfo(databaseManga),
        )

        var file: UniFile? = null
        try {
            file = (
                if (isAutoBackup) {
                    // Get dir of file and create
                    var dir = UniFile.fromUri(context, uri)
                    dir = dir.createDirectory("automatic")

                    // Delete older backups
                    val numberOfBackups = preferences.numberOfBackups().get()
                    val backupRegex = Regex("""tachiyomi_\d+-\d+-\d+_\d+-\d+.proto.gz""")
                    dir.listFiles { _, filename -> backupRegex.matches(filename) }
                        .orEmpty()
                        .sortedByDescending { it.name }
                        .drop(numberOfBackups - 1)
                        .forEach { it.delete() }

                    // Create new file to place backup
                    dir.createFile(Backup.getBackupFilename())
                } else {
                    UniFile.fromUri(context, uri)
                }
                )
                ?: throw Exception("Couldn't create backup file")

            if (!file.isFile) {
                throw IllegalStateException("Failed to get handle on file")
            }

            val byteArray = parser.encodeToByteArray(BackupSerializer, backup)
            if (byteArray.isEmpty()) {
                throw IllegalStateException(context.getString(R.string.empty_backup_error))
            }

            file.openOutputStream().also {
                // Force overwrite old file
                (it as? FileOutputStream)?.channel?.truncate(0)
            }.sink().gzip().buffer().use { it.write(byteArray) }
            val fileUri = file.uri

            // Make sure it's a valid backup file
            BackupFileValidator().validate(context, fileUri)

            return fileUri.toString()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            file?.delete()
            throw e
        }
    }

    private fun backupExtensionInfo(mangas: List<DomainManga>): List<BackupSource> {
        return mangas
            .asSequence()
            .map { it.source }
            .distinct()
            .map { sourceManager.getOrStub(it) }
            .map { BackupSource.copyFrom(it) }
            .toList()
    }

    /**
     * Backup the categories of library
     *
     * @return list of [BackupCategory] to be backed up
     */
    private suspend fun backupCategories(options: Int): List<BackupCategory> {
        // Check if user wants category information in backup
        return if (options and BACKUP_CATEGORY_MASK == BACKUP_CATEGORY) {
            getCategories.await()
                .filterNot(Category::isSystemCategory)
                .map(backupCategoryMapper)
        } else {
            emptyList()
        }
    }

    private suspend fun backupMangas(mangas: List<DomainManga>, flags: Int): List<BackupManga> {
        return mangas.map {
            backupManga(it, flags)
        }
    }

    /**
     * Convert a manga to Json
     *
     * @param manga manga that gets converted
     * @param options options for the backup
     * @return [BackupManga] containing manga in a serializable form
     */
    private suspend fun backupManga(manga: DomainManga, options: Int): BackupManga {
        // Entry for this manga
        val mangaObject = BackupManga.copyFrom(manga)

        // Check if user wants chapter information in backup
        if (options and BACKUP_CHAPTER_MASK == BACKUP_CHAPTER) {
            // Backup all the chapters
            val chapters = handler.awaitList { chaptersQueries.getChaptersByMangaId(manga.id, backupChapterMapper) }
            if (chapters.isNotEmpty()) {
                mangaObject.chapters = chapters
            }
        }

        // Check if user wants category information in backup
        if (options and BACKUP_CATEGORY_MASK == BACKUP_CATEGORY) {
            // Backup categories for this manga
            val categoriesForManga = getCategories.await(manga.id)
            if (categoriesForManga.isNotEmpty()) {
                mangaObject.categories = categoriesForManga.map { it.order }
            }
        }

        // Check if user wants track information in backup
        if (options and BACKUP_TRACK_MASK == BACKUP_TRACK) {
            val tracks = handler.awaitList { manga_syncQueries.getTracksByMangaId(manga.id, backupTrackMapper) }
            if (tracks.isNotEmpty()) {
                mangaObject.tracking = tracks
            }
        }

        // Check if user wants history information in backup
        if (options and BACKUP_HISTORY_MASK == BACKUP_HISTORY) {
            val historyByMangaId = handler.awaitList(true) { historyQueries.getHistoryByMangaId(manga.id) }
            if (historyByMangaId.isNotEmpty()) {
                val history = historyByMangaId.map { history ->
                    val chapter = handler.awaitOne { chaptersQueries.getChapterById(history.chapter_id) }
                    BackupHistory(chapter.url, history.last_read?.time ?: 0L, history.time_read)
                }
                if (history.isNotEmpty()) {
                    mangaObject.history = history
                }
            }
        }

        return mangaObject
    }

    internal suspend fun restoreExistingManga(manga: Manga, dbManga: Mangas) {
        manga.id = dbManga._id
        manga.copyFrom(dbManga)
        updateManga(manga)
    }

    /**
     * Fetches manga information
     *
     * @param manga manga that needs updating
     * @return Updated manga info.
     */
    internal suspend fun restoreNewManga(manga: Manga): Manga {
        return manga.also {
            it.initialized = it.description != null
            it.id = insertManga(it)
        }
    }

    /**
     * Restore the categories from Json
     *
     * @param backupCategories list containing categories
     */
    internal suspend fun restoreCategories(backupCategories: List<BackupCategory>) {
        // Get categories from file and from db
        val dbCategories = getCategories.await()

        val categories = backupCategories.map {
            var category = it.getCategory()
            var found = false
            for (dbCategory in dbCategories) {
                // If the category is already in the db, assign the id to the file's category
                // and do nothing
                if (category.name == dbCategory.name) {
                    category = category.copy(id = dbCategory.id)
                    found = true
                    break
                }
            }
            if (!found) {
                // Let the db assign the id
                val id = handler.awaitOne {
                    categoriesQueries.insert(category.name, category.order, category.flags)
                    categoriesQueries.selectLastInsertedRowId()
                }
                category = category.copy(id = id)
            }

            category
        }

        preferences.categorizedDisplaySettings().set(
            (dbCategories + categories)
                .distinctBy { it.flags }
                .size > 1,
        )
    }

    /**
     * Restores the categories a manga is in.
     *
     * @param manga the manga whose categories have to be restored.
     * @param categories the categories to restore.
     */
    internal suspend fun restoreCategories(manga: Manga, categories: List<Int>, backupCategories: List<BackupCategory>) {
        val dbCategories = getCategories.await()
        val mangaCategoriesToUpdate = mutableListOf<Pair<Long, Long>>()

        categories.forEach { backupCategoryOrder ->
            backupCategories.firstOrNull {
                it.order == backupCategoryOrder.toLong()
            }?.let { backupCategory ->
                dbCategories.firstOrNull { dbCategory ->
                    dbCategory.name == backupCategory.name
                }?.let { dbCategory ->
                    mangaCategoriesToUpdate.add(Pair(manga.id!!, dbCategory.id))
                }
            }
        }

        // Update database
        if (mangaCategoriesToUpdate.isNotEmpty()) {
            handler.await(true) {
                mangas_categoriesQueries.deleteMangaCategoryByMangaId(manga.id!!)
                mangaCategoriesToUpdate.forEach { (mangaId, categoryId) ->
                    mangas_categoriesQueries.insert(mangaId, categoryId)
                }
            }
        }
    }

    /**
     * Restore history from Json
     *
     * @param history list containing history to be restored
     */
    internal suspend fun restoreHistory(history: List<BackupHistory>) {
        // List containing history to be updated
        val toUpdate = mutableListOf<HistoryUpdate>()
        for ((url, lastRead, readDuration) in history) {
            var dbHistory = handler.awaitOneOrNull { historyQueries.getHistoryByChapterUrl(url) }
            // Check if history already in database and update
            if (dbHistory != null) {
                dbHistory = dbHistory.copy(
                    last_read = Date(max(lastRead, dbHistory.last_read?.time ?: 0L)),
                    time_read = max(readDuration, dbHistory.time_read) - dbHistory.time_read,
                )
                toUpdate.add(
                    HistoryUpdate(
                        chapterId = dbHistory.chapter_id,
                        readAt = dbHistory.last_read!!,
                        sessionReadDuration = dbHistory.time_read,
                    ),
                )
            } else {
                // If not in database create
                handler
                    .awaitOneOrNull { chaptersQueries.getChapterByUrl(url) }
                    ?.let {
                        toUpdate.add(
                            HistoryUpdate(
                                chapterId = it._id,
                                readAt = Date(lastRead),
                                sessionReadDuration = readDuration,
                            ),
                        )
                    }
            }
        }
        handler.await(true) {
            toUpdate.forEach { payload ->
                historyQueries.upsert(
                    payload.chapterId,
                    payload.readAt,
                    payload.sessionReadDuration,
                )
            }
        }
    }

    /**
     * Restores the sync of a manga.
     *
     * @param manga the manga whose sync have to be restored.
     * @param tracks the track list to restore.
     */
    internal suspend fun restoreTracking(manga: Manga, tracks: List<Track>) {
        // Fix foreign keys with the current manga id
        tracks.map { it.manga_id = manga.id!! }

        // Get tracks from database
        val dbTracks = handler.awaitList { manga_syncQueries.getTracksByMangaId(manga.id!!) }
        val toUpdate = mutableListOf<Manga_sync>()
        val toInsert = mutableListOf<Track>()

        tracks.forEach { track ->
            var isInDatabase = false
            for (dbTrack in dbTracks) {
                if (track.sync_id == dbTrack.sync_id.toInt()) {
                    // The sync is already in the db, only update its fields
                    var temp = dbTrack
                    if (track.media_id != dbTrack.remote_id) {
                        temp = temp.copy(remote_id = track.media_id)
                    }
                    if (track.library_id != dbTrack.library_id) {
                        temp = temp.copy(library_id = track.library_id)
                    }
                    temp = temp.copy(last_chapter_read = max(dbTrack.last_chapter_read, track.last_chapter_read.toDouble()))
                    isInDatabase = true
                    toUpdate.add(temp)
                    break
                }
            }
            if (!isInDatabase) {
                // Insert new sync. Let the db assign the id
                track.id = null
                toInsert.add(track)
            }
        }
        // Update database
        if (toUpdate.isNotEmpty()) {
            handler.await(true) {
                toUpdate.forEach { track ->
                    manga_syncQueries.update(
                        track.manga_id,
                        track.sync_id,
                        track.remote_id,
                        track.library_id,
                        track.title,
                        track.last_chapter_read,
                        track.total_chapters,
                        track.status,
                        track.score.toDouble(),
                        track.remote_url,
                        track.start_date,
                        track.finish_date,
                        track._id,
                    )
                }
            }
        }
        if (toInsert.isNotEmpty()) {
            handler.await(true) {
                toInsert.forEach { track ->
                    manga_syncQueries.insert(
                        track.manga_id,
                        track.sync_id.toLong(),
                        track.media_id,
                        track.library_id,
                        track.title,
                        track.last_chapter_read.toDouble(),
                        track.total_chapters.toLong(),
                        track.status.toLong(),
                        track.score,
                        track.tracking_url,
                        track.started_reading_date,
                        track.finished_reading_date,
                    )
                }
            }
        }
    }

    internal suspend fun restoreChapters(manga: Manga, chapters: List<Chapter>) {
        val dbChapters = handler.awaitList { chaptersQueries.getChaptersByMangaId(manga.id!!) }

        chapters.forEach { chapter ->
            val dbChapter = dbChapters.find { it.url == chapter.url }
            if (dbChapter != null) {
                chapter.id = dbChapter._id
                chapter.copyFrom(dbChapter)
                if (dbChapter.read && !chapter.read) {
                    chapter.read = dbChapter.read
                    chapter.last_page_read = dbChapter.last_page_read.toInt()
                } else if (chapter.last_page_read == 0 && dbChapter.last_page_read != 0L) {
                    chapter.last_page_read = dbChapter.last_page_read.toInt()
                }
                if (!chapter.bookmark && dbChapter.bookmark) {
                    chapter.bookmark = dbChapter.bookmark
                }
            }

            chapter.manga_id = manga.id
        }

        val newChapters = chapters.groupBy { it.id != null }
        newChapters[true]?.let { updateKnownChapters(it) }
        newChapters[false]?.let { insertChapters(it) }
    }

    /**
     * Returns manga
     *
     * @return [Manga], null if not found
     */
    internal suspend fun getMangaFromDatabase(url: String, source: Long): Mangas? {
        return handler.awaitOneOrNull { mangasQueries.getMangaByUrlAndSource(url, source) }
    }

    /**
     * Inserts manga and returns id
     *
     * @return id of [Manga], null if not found
     */
    private suspend fun insertManga(manga: Manga): Long {
        return handler.awaitOne(true) {
            mangasQueries.insert(
                source = manga.source,
                url = manga.url,
                artist = manga.artist,
                author = manga.author,
                description = manga.description,
                genre = manga.getGenres(),
                title = manga.title,
                status = manga.status.toLong(),
                thumbnailUrl = manga.thumbnail_url,
                favorite = manga.favorite,
                lastUpdate = manga.last_update,
                nextUpdate = 0L,
                initialized = manga.initialized,
                viewerFlags = manga.viewer_flags.toLong(),
                chapterFlags = manga.chapter_flags.toLong(),
                coverLastModified = manga.cover_last_modified,
                dateAdded = manga.date_added,
            )
            mangasQueries.selectLastInsertedRowId()
        }
    }

    private suspend fun updateManga(manga: Manga): Long {
        handler.await(true) {
            mangasQueries.update(
                source = manga.source,
                url = manga.url,
                artist = manga.artist,
                author = manga.author,
                description = manga.description,
                genre = manga.genre,
                title = manga.title,
                status = manga.status.toLong(),
                thumbnailUrl = manga.thumbnail_url,
                favorite = manga.favorite.toLong(),
                lastUpdate = manga.last_update,
                initialized = manga.initialized.toLong(),
                viewer = manga.viewer_flags.toLong(),
                chapterFlags = manga.chapter_flags.toLong(),
                coverLastModified = manga.cover_last_modified,
                dateAdded = manga.date_added,
                mangaId = manga.id!!,
            )
        }
        return manga.id!!
    }

    /**
     * Inserts list of chapters
     */
    private suspend fun insertChapters(chapters: List<Chapter>) {
        handler.await(true) {
            chapters.forEach { chapter ->
                chaptersQueries.insert(
                    chapter.manga_id!!,
                    chapter.url,
                    chapter.name,
                    chapter.scanlator,
                    chapter.read,
                    chapter.bookmark,
                    chapter.last_page_read.toLong(),
                    chapter.chapter_number,
                    chapter.source_order.toLong(),
                    chapter.date_fetch,
                    chapter.date_upload,
                )
            }
        }
    }

    /**
     * Updates a list of chapters
     */
    private suspend fun updateChapters(chapters: List<Chapter>) {
        handler.await(true) {
            chapters.forEach { chapter ->
                chaptersQueries.update(
                    chapter.manga_id!!,
                    chapter.url,
                    chapter.name,
                    chapter.scanlator,
                    chapter.read.toLong(),
                    chapter.bookmark.toLong(),
                    chapter.last_page_read.toLong(),
                    chapter.chapter_number.toDouble(),
                    chapter.source_order.toLong(),
                    chapter.date_fetch,
                    chapter.date_upload,
                    chapter.id!!,
                )
            }
        }
    }

    /**
     * Updates a list of chapters with known database ids
     */
    private suspend fun updateKnownChapters(chapters: List<Chapter>) {
        handler.await(true) {
            chapters.forEach { chapter ->
                chaptersQueries.update(
                    mangaId = null,
                    url = null,
                    name = null,
                    scanlator = null,
                    read = chapter.read.toLong(),
                    bookmark = chapter.bookmark.toLong(),
                    lastPageRead = chapter.last_page_read.toLong(),
                    chapterNumber = null,
                    sourceOrder = null,
                    dateFetch = null,
                    dateUpload = null,
                    chapterId = chapter.id!!,
                )
            }
        }
    }
}
