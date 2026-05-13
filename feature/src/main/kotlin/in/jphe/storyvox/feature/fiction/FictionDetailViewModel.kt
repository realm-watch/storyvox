package `in`.jphe.storyvox.feature.fiction

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.DownloadMode
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.UiChapter
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.source.epub.writer.EpubExportResult
import `in`.jphe.storyvox.source.epub.writer.ExportFictionToEpubUseCase
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class FictionDetailUiState(
    val fiction: UiFiction? = null,
    val chapters: List<UiChapter> = emptyList(),
    val isInLibrary: Boolean = false,
    val downloadMode: DownloadMode = DownloadMode.Lazy,
    val isLoading: Boolean = true,
    /** Set when the first-subscription `refreshDetail` failed and we have
     *  no cached row to show. Cleared on the next successful refresh.
     *  When [fiction] is non-null this is a tail/refresh error — the
     *  screen should keep showing the cached data and surface the error
     *  as a snackbar / banner rather than blocking the page. */
    val error: String? = null,
    /** Issue #117 — true while [ExportFictionToEpubUseCase] is building the
     *  .epub. UI surfaces a "Building .epub…" chip so the user knows their
     *  tap took effect even on a 5000-chapter export. */
    val isExportingEpub: Boolean = false,
)

sealed interface FictionDetailUiEvent {
    data class OpenReader(val fictionId: String, val chapterId: String) : FictionDetailUiEvent
    /**
     * Issue #117 — fired when an EPUB export finishes. The screen
     * collects this and surfaces the Share-vs-Save bottom sheet to the
     * user. We pipe the result through an event channel rather than a
     * StateFlow because the share action is one-shot per tap and we
     * don't want it re-firing on configuration change.
     */
    data class EpubExported(val result: EpubExportResult) : FictionDetailUiEvent
    /** Issue #117 — surfaces non-fatal export failures (no network for
     *  the cover, disk write error, etc) so the screen can toast. */
    data class EpubExportFailed(val message: String) : FictionDetailUiEvent
}

@HiltViewModel
class FictionDetailViewModel @Inject constructor(
    private val repo: FictionRepositoryUi,
    private val playback: PlaybackControllerUi,
    private val exportEpub: ExportFictionToEpubUseCase,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val fictionId: String = checkNotNull(savedState["fictionId"]) {
        "FictionDetailScreen requires a `fictionId` nav arg"
    }

    private val _events = Channel<FictionDetailUiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** Issue #117 — export-in-flight flag. Exposed via [FictionDetailUiState.isExportingEpub]
     *  through the combine below. Held outside the combine so the suspend
     *  call site can flip it cleanly around the use-case invocation. */
    private val isExporting = kotlinx.coroutines.flow.MutableStateFlow(false)

    val uiState: StateFlow<FictionDetailUiState> = combine(
        repo.fictionById(fictionId),
        repo.chaptersFor(fictionId),
        repo.library,
        repo.fictionLoadError(fictionId),
        isExporting,
    ) { fiction, chapters, library, error, exporting ->
        FictionDetailUiState(
            fiction = fiction,
            chapters = chapters,
            isInLibrary = library.any { it.id == fictionId },
            // Stop showing the spinner once we either have a cached row
            // OR the refresh has failed — otherwise a Cloudflare/network
            // error leaves the user on a permanent spinner with no signal.
            isLoading = fiction == null && error == null,
            error = error,
            isExportingEpub = exporting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FictionDetailUiState())

    fun toggleFollow(follow: Boolean) {
        viewModelScope.launch { repo.follow(fictionId, follow) }
    }

    fun setMode(mode: DownloadMode) {
        viewModelScope.launch { repo.setDownloadMode(fictionId, mode) }
    }

    /**
     * Issue #117 — build a `.epub` of the current fiction in the cache dir
     * and emit [FictionDetailUiEvent.EpubExported] so the screen can fire
     * the share-sheet / SAF flow. Takes [context] because the use case
     * needs `cacheDir` + `FileProvider` — both Context-bound surfaces.
     *
     * Idempotent: if the user double-taps while a previous export is in
     * flight, we short-circuit the second call. The completed export
     * lands in the cache directory, so re-exporting only matters when
     * the user wants a fresh timestamp (or has read more chapters since
     * the last export).
     */
    fun exportToEpub(context: Context) {
        if (isExporting.value) return
        viewModelScope.launch {
            isExporting.value = true
            try {
                val result = exportEpub.export(context, fictionId)
                _events.send(FictionDetailUiEvent.EpubExported(result))
            } catch (t: Throwable) {
                // The use case throws IllegalStateException for missing
                // rows (impossible from this code path — the detail screen
                // only renders for known fictions) and otherwise lets file
                // / IO exceptions bubble. Surface a friendly message; the
                // VM stays alive for the user to try again.
                _events.send(
                    FictionDetailUiEvent.EpubExportFailed(
                        "Couldn't build .epub: ${t.message ?: t.javaClass.simpleName}",
                    ),
                )
            } finally {
                isExporting.value = false
            }
        }
    }

    fun listen(chapterId: String) {
        // Issue #288 — tapping Listen is a stronger intent than tapping
        // 'Add to library'. The user is committing to listen RIGHT NOW.
        // Silently follow the fiction if it isn't already in their
        // library so it survives app restart and the Resume card can
        // surface it on Library. Mirror the gesture-only-add pattern
        // (no confirm dialog) — remove-from-library still requires the
        // explicit AlertDialog from issue #169.
        if (!uiState.value.isInLibrary) {
            viewModelScope.launch { repo.follow(fictionId, true) }
        }
        playback.startListening(fictionId, chapterId)
        viewModelScope.launch { _events.send(FictionDetailUiEvent.OpenReader(fictionId, chapterId)) }
    }
}
