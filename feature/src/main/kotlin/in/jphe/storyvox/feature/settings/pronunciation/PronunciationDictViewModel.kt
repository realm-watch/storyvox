package `in`.jphe.storyvox.feature.settings.pronunciation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.repository.pronunciation.MatchType
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDict
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationDictRepository
import `in`.jphe.storyvox.data.repository.pronunciation.PronunciationEntry
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings → Pronunciation editor view-model (issue #135). Phase 1
 * MVP only — global dict, single list, simple add/edit/delete.
 *
 * Per-fiction overrides, import/export, and bulk-edit UX land in
 * phase 2.
 */
@Immutable
data class PronunciationUiState(
    val entries: List<PronunciationEntry> = emptyList(),
)

@HiltViewModel
class PronunciationDictViewModel @Inject constructor(
    private val repo: PronunciationDictRepository,
) : ViewModel() {

    val uiState: StateFlow<PronunciationUiState> = repo.dict
        .map { dict -> PronunciationUiState(entries = dict.entries) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PronunciationUiState())

    /** Add a new entry. Empty pattern is rejected client-side
     *  (the dict layer also drops empty patterns at compile-time,
     *  but we don't want the user to see a row that disappears
     *  on save). */
    fun addEntry(pattern: String, replacement: String, matchType: MatchType, caseSensitive: Boolean) {
        if (pattern.isBlank()) return
        viewModelScope.launch {
            repo.add(
                PronunciationEntry(
                    pattern = pattern,
                    replacement = replacement,
                    matchType = matchType,
                    caseSensitive = caseSensitive,
                )
            )
        }
    }

    fun updateEntry(
        index: Int,
        pattern: String,
        replacement: String,
        matchType: MatchType,
        caseSensitive: Boolean,
    ) {
        if (pattern.isBlank()) return
        viewModelScope.launch {
            repo.update(
                index,
                PronunciationEntry(
                    pattern = pattern,
                    replacement = replacement,
                    matchType = matchType,
                    caseSensitive = caseSensitive,
                ),
            )
        }
    }

    fun deleteEntry(index: Int) = viewModelScope.launch { repo.delete(index) }

    fun clearAll() = viewModelScope.launch { repo.replaceAll(PronunciationDict.EMPTY) }
}
