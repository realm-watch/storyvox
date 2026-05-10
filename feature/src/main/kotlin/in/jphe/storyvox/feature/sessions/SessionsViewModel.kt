package `in`.jphe.storyvox.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.UiFiction
import `in`.jphe.storyvox.llm.FeatureKind
import `in`.jphe.storyvox.llm.LlmSessionRepository
import `in`.jphe.storyvox.llm.SessionView
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Issue #218 — surface past LLM sessions stored in Room. Free-form
 * chat sessions (one per fictionId, created by [ChatViewModel]) and
 * chapter-recap sessions (created by ChapterRecap, one per chapter)
 * both live in the same table; this VM reads them and decorates
 * each row with the resolved fiction title for the card subtitle.
 *
 * The "Open" action is supplied by the screen — for free-form chat
 * sessions it navigates to the existing ChatScreen for the same
 * fictionId. For recap sessions there's no equivalent surface yet
 * (the recap modal lives inside the reader); the card disables Open.
 */
@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val sessionRepo: LlmSessionRepository,
    private val fictionRepo: FictionRepositoryUi,
) : ViewModel() {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<SessionsUiState> = sessionRepo.observeSessions()
        .flatMapLatest { sessions ->
            // Resolve each session's anchor fiction title in parallel.
            // Sessions without an anchor (rare — recap sessions always
            // have one) just render the session name as fallback.
            val titleFlows = sessions.map { s ->
                val fid = s.anchorFictionId
                if (fid != null) fictionRepo.fictionById(fid).map { it?.title }
                else flowOf(null)
            }
            if (titleFlows.isEmpty()) {
                flowOf(SessionsUiState(rows = emptyList()))
            } else {
                combine(titleFlows) { titles ->
                    val rows = sessions.mapIndexed { idx, s ->
                        SessionRow(
                            session = s,
                            fictionTitle = titles[idx],
                        )
                    }
                    SessionsUiState(rows = rows)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionsUiState())

    fun deleteSession(sessionId: String) {
        viewModelScope.launch { sessionRepo.deleteSession(sessionId) }
    }
}

data class SessionsUiState(
    val rows: List<SessionRow> = emptyList(),
)

data class SessionRow(
    val session: SessionView,
    /** Resolved fiction title from the anchor id, or null if the
     *  fiction can't be resolved (deleted, or session predates the
     *  anchor field). The screen falls back to [SessionView.name]. */
    val fictionTitle: String?,
) {
    val isFreeFormChat: Boolean get() = session.featureKind == null
    val isChapterRecap: Boolean get() = session.featureKind == FeatureKind.ChapterRecap
}
