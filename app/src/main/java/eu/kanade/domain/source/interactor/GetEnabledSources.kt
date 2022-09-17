package eu.kanade.domain.source.interactor

import eu.kanade.domain.source.model.Pin
import eu.kanade.domain.source.model.Pins
import eu.kanade.domain.source.model.Source
import eu.kanade.domain.source.repository.SourceRepository
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.LocalSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class GetEnabledSources(
    private val repository: SourceRepository,
    private val preferences: PreferencesHelper,
) {

    fun subscribe(): Flow<List<Source>> {
        return combine(
            preferences.pinnedSources().changes(),
            preferences.enabledLanguages().changes(),
            preferences.disabledSources().changes(),
            preferences.lastUsedSource().changes(),
            repository.getSources(),
        ) { pinnedSourceIds, enabledLanguages, disabledSources, lastUsedSource, sources ->
            val duplicatePins = preferences.duplicatePinnedSources().get()
            sources
                .filter { it.lang in enabledLanguages || it.id == LocalSource.ID }
                .filterNot { it.id.toString() in disabledSources }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .flatMap {
                    val flag = if ("${it.id}" in pinnedSourceIds) Pins.pinned else Pins.unpinned
                    val source = it.copy(pin = flag)
                    val toFlatten = mutableListOf(source)
                    if (source.id == lastUsedSource) {
                        toFlatten.add(source.copy(isUsedLast = true, pin = source.pin - Pin.Actual))
                    }
                    if (duplicatePins && Pin.Pinned in source.pin) {
                        toFlatten[0] = toFlatten[0].copy(pin = source.pin + Pin.Forced)
                        toFlatten.add(source.copy(pin = source.pin - Pin.Actual))
                    }
                    toFlatten
                }
        }
            .distinctUntilChanged()
    }
}
