package eu.kanade.tachiyomi.ui.setting

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.core.net.toUri
import androidx.core.text.buildSpannedString
import androidx.preference.PreferenceScreen
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hippo.unifile.UniFile
import eu.kanade.domain.category.interactor.GetCategories
import eu.kanade.presentation.category.visualName
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.util.preference.bindTo
import eu.kanade.tachiyomi.util.preference.defaultValue
import eu.kanade.tachiyomi.util.preference.entriesRes
import eu.kanade.tachiyomi.util.preference.infoPreference
import eu.kanade.tachiyomi.util.preference.intListPreference
import eu.kanade.tachiyomi.util.preference.multiSelectListPreference
import eu.kanade.tachiyomi.util.preference.onClick
import eu.kanade.tachiyomi.util.preference.preference
import eu.kanade.tachiyomi.util.preference.preferenceCategory
import eu.kanade.tachiyomi.util.preference.summaryRes
import eu.kanade.tachiyomi.util.preference.switchPreference
import eu.kanade.tachiyomi.util.preference.titleRes
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.widget.materialdialogs.QuadStateTextView
import eu.kanade.tachiyomi.widget.materialdialogs.setQuadStateMultiChoiceItems
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.File
import eu.kanade.tachiyomi.data.preference.PreferenceKeys as Keys

class SettingsDownloadController : SettingsController() {

    private val getCategories: GetCategories by injectLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = R.string.pref_category_downloads

        val categories = runBlocking { getCategories.await() }

        preference {
            bindTo(preferences.downloadsDirectory())
            titleRes = R.string.pref_download_directory
            onClick {
                val ctrl = DownloadDirectoriesDialog()
                ctrl.targetController = this@SettingsDownloadController
                ctrl.showDialog(router)
            }

            preferences.downloadsDirectory().changes()
                .onEach { path ->
                    val dir = UniFile.fromUri(context, path.toUri())
                    summary = dir.filePath ?: path
                }
                .launchIn(viewScope)
        }
        switchPreference {
            key = Keys.downloadOnlyOverWifi
            titleRes = R.string.connected_to_wifi
            defaultValue = true
        }
        switchPreference {
            bindTo(preferences.saveChaptersAsCBZ())
            titleRes = R.string.save_chapter_as_cbz
        }
        switchPreference {
            bindTo(preferences.splitTallImages())
            titleRes = R.string.split_tall_images
            summaryRes = R.string.split_tall_images_summary
        }

        preferenceCategory {
            titleRes = R.string.pref_category_delete_chapters

            switchPreference {
                key = Keys.removeAfterMarkedAsRead
                titleRes = R.string.pref_remove_after_marked_as_read
                defaultValue = false
            }
            intListPreference {
                key = Keys.removeAfterReadSlots
                titleRes = R.string.pref_remove_after_read
                entriesRes = arrayOf(
                    R.string.disabled,
                    R.string.last_read_chapter,
                    R.string.second_to_last,
                    R.string.third_to_last,
                    R.string.fourth_to_last,
                    R.string.fifth_to_last,
                )
                entryValues = arrayOf("-1", "0", "1", "2", "3", "4")
                defaultValue = "-1"
                summary = "%s"
            }
            switchPreference {
                key = Keys.removeBookmarkedChapters
                titleRes = R.string.pref_remove_bookmarked_chapters
                defaultValue = false
            }
            multiSelectListPreference {
                bindTo(preferences.removeExcludeCategories())
                titleRes = R.string.pref_remove_exclude_categories
                entries = categories.map { it.visualName(context) }.toTypedArray()
                entryValues = categories.map { it.id.toString() }.toTypedArray()

                preferences.removeExcludeCategories().changes()
                    .onEach { mutable ->
                        val selected = mutable
                            .mapNotNull { id -> categories.find { it.id == id.toLong() } }
                            .sortedBy { it.order }

                        summary = if (selected.isEmpty()) {
                            resources?.getString(R.string.none)
                        } else {
                            selected.joinToString { it.visualName(context) }
                        }
                    }.launchIn(viewScope)
            }
        }

        preferenceCategory {
            titleRes = R.string.pref_download_new

            switchPreference {
                bindTo(preferences.downloadNewChapters())
                titleRes = R.string.pref_download_new
            }
            preference {
                bindTo(preferences.downloadNewChapterCategories())
                titleRes = R.string.categories
                onClick {
                    DownloadCategoriesDialog().showDialog(router)
                }

                visibleIf(preferences.downloadNewChapters()) { it }

                fun updateSummary() {
                    val selectedCategories = preferences.downloadNewChapterCategories().get()
                        .mapNotNull { id -> categories.find { it.id == id.toLong() } }
                        .sortedBy { it.order }
                    val includedItemsText = if (selectedCategories.isEmpty()) {
                        context.getString(R.string.all)
                    } else {
                        selectedCategories.joinToString { it.visualName(context) }
                    }

                    val excludedCategories = preferences.downloadNewChapterCategoriesExclude().get()
                        .mapNotNull { id -> categories.find { it.id == id.toLong() } }
                        .sortedBy { it.order }
                    val excludedItemsText = if (excludedCategories.isEmpty()) {
                        context.getString(R.string.none)
                    } else {
                        excludedCategories.joinToString { it.visualName(context) }
                    }

                    summary = buildSpannedString {
                        append(context.getString(R.string.include, includedItemsText))
                        appendLine()
                        append(context.getString(R.string.exclude, excludedItemsText))
                    }
                }

                preferences.downloadNewChapterCategories().changes()
                    .onEach { updateSummary() }
                    .launchIn(viewScope)
                preferences.downloadNewChapterCategoriesExclude().changes()
                    .onEach { updateSummary() }
                    .launchIn(viewScope)
            }
        }

        preferenceCategory {
            titleRes = R.string.download_ahead

            intListPreference {
                bindTo(preferences.autoDownloadWhileReading())
                titleRes = R.string.auto_download_while_reading
                entries = arrayOf(
                    context.getString(R.string.disabled),
                    context.resources.getQuantityString(R.plurals.next_unread_chapters, 2, 2),
                    context.resources.getQuantityString(R.plurals.next_unread_chapters, 3, 3),
                    context.resources.getQuantityString(R.plurals.next_unread_chapters, 5, 5),
                    context.resources.getQuantityString(R.plurals.next_unread_chapters, 10, 10),
                )
                entryValues = arrayOf("0", "2", "3", "5", "10")
                summary = "%s"
            }
            infoPreference(R.string.download_ahead_info)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            DOWNLOAD_DIR -> if (data != null && resultCode == Activity.RESULT_OK) {
                val context = applicationContext ?: return
                val uri = data.data
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                if (uri != null) {
                    @Suppress("NewApi")
                    context.contentResolver.takePersistableUriPermission(uri, flags)
                }

                val file = UniFile.fromUri(context, uri)
                preferences.downloadsDirectory().set(file.uri.toString())
            }
        }
    }

    fun predefinedDirectorySelected(selectedDir: String) {
        val path = File(selectedDir).toUri()
        preferences.downloadsDirectory().set(path.toString())
    }

    fun customDirectorySelected() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try {
            startActivityForResult(intent, DOWNLOAD_DIR)
        } catch (e: ActivityNotFoundException) {
            activity?.toast(R.string.file_picker_error)
        }
    }

    class DownloadDirectoriesDialog : DialogController() {

        private val preferences: PreferencesHelper = Injekt.get()

        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            val activity = activity!!
            val currentDir = preferences.downloadsDirectory().get()
            val externalDirs = listOf(getDefaultDownloadDir(), File(activity.getString(R.string.custom_dir))).map(File::toString)
            var selectedIndex = externalDirs.indexOfFirst { it in currentDir }

            return MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.pref_download_directory)
                .setSingleChoiceItems(externalDirs.toTypedArray(), selectedIndex) { _, which ->
                    selectedIndex = which
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val target = targetController as? SettingsDownloadController
                    if (selectedIndex == externalDirs.lastIndex) {
                        target?.customDirectorySelected()
                    } else {
                        target?.predefinedDirectorySelected(externalDirs[selectedIndex])
                    }
                }
                .create()
        }

        private fun getDefaultDownloadDir(): File {
            val defaultDir = Environment.getExternalStorageDirectory().absolutePath +
                File.separator + resources?.getString(R.string.app_name) +
                File.separator + "downloads"

            return File(defaultDir)
        }
    }

    class DownloadCategoriesDialog : DialogController() {

        private val preferences: PreferencesHelper = Injekt.get()
        private val getCategories: GetCategories = Injekt.get()

        override fun onCreateDialog(savedViewState: Bundle?): Dialog {
            val categories = runBlocking { getCategories.await() }

            val items = categories.map { it.visualName(activity!!) }
            var selected = categories
                .map {
                    when (it.id.toString()) {
                        in preferences.downloadNewChapterCategories().get() -> QuadStateTextView.State.CHECKED.ordinal
                        in preferences.downloadNewChapterCategoriesExclude().get() -> QuadStateTextView.State.INVERSED.ordinal
                        else -> QuadStateTextView.State.UNCHECKED.ordinal
                    }
                }
                .toIntArray()

            return MaterialAlertDialogBuilder(activity!!)
                .setTitle(R.string.categories)
                .setQuadStateMultiChoiceItems(
                    message = R.string.pref_download_new_categories_details,
                    items = items,
                    initialSelected = selected,
                ) { selections ->
                    selected = selections
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val included = selected
                        .mapIndexed { index, value -> if (value == QuadStateTextView.State.CHECKED.ordinal) index else null }
                        .filterNotNull()
                        .map { categories[it].id.toString() }
                        .toSet()
                    val excluded = selected
                        .mapIndexed { index, value -> if (value == QuadStateTextView.State.INVERSED.ordinal) index else null }
                        .filterNotNull()
                        .map { categories[it].id.toString() }
                        .toSet()

                    preferences.downloadNewChapterCategories().set(included)
                    preferences.downloadNewChapterCategoriesExclude().set(excluded)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }
}

private const val DOWNLOAD_DIR = 104
