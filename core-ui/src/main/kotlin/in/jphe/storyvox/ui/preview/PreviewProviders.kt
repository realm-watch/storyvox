package `in`.jphe.storyvox.ui.preview

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import `in`.jphe.storyvox.ui.component.ChapterCardState

/**
 * Sample data feeding `@Preview` Composables. Avoids reaching into core-data
 * (which would couple the design system to the Room schema).
 */

data class SampleFiction(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val rating: Float,
    val chapters: Int,
    val isOngoing: Boolean,
    val synopsis: String,
)

object SampleData {
    val fictions: List<SampleFiction> = listOf(
        SampleFiction(
            id = "1",
            title = "The Wandering Inn",
            author = "pirateaba",
            coverUrl = null,
            rating = 4.7f,
            chapters = 412,
            isOngoing = true,
            synopsis = "An inn for travelers from all worlds, kept by a young woman from Earth in a world of magic and monsters.",
        ),
        SampleFiction(
            id = "2",
            title = "Mother of Learning",
            author = "nobody103",
            coverUrl = null,
            rating = 4.9f,
            chapters = 108,
            isOngoing = false,
            synopsis = "Zorian is stuck in a time loop. Every month resets. He has to figure out why — and how to escape — before everything ends.",
        ),
        SampleFiction(
            id = "3",
            title = "He Who Fights With Monsters",
            author = "Shirtaloon",
            coverUrl = null,
            rating = 4.6f,
            chapters = 220,
            isOngoing = true,
            synopsis = "Jason wakes up in a world of magic and monsters with no memory of how he got there.",
        ),
    )

    val chapters: List<ChapterCardState> = listOf(
        ChapterCardState(1, "Apocalypse: Lost", "2 days ago", "12 min", isDownloaded = true, isFinished = true, isCurrent = false),
        ChapterCardState(2, "First Light", "2 days ago", "18 min", isDownloaded = true, isFinished = true, isCurrent = false),
        ChapterCardState(3, "Strangers in the Inn", "1 day ago", "22 min", isDownloaded = true, isFinished = false, isCurrent = true),
        ChapterCardState(4, "The Goblin Problem", "5 hours ago", "16 min", isDownloaded = false, isFinished = false, isCurrent = false),
    )

    const val SAMPLE_CHAPTER_TEXT: String = (
        "The inn was quiet that morning. Not the heavy quiet of a place abandoned, " +
        "but the patient quiet of a place waiting. Erin wiped down the bar with a rag " +
        "that had seen better days and watched the road through the open door. " +
        "Travelers came when they came. The dust on the floor told her there had been " +
        "none for two days now. That was fine. The bread was fresh, the kettle was on, " +
        "and somewhere upstairs a goblin was trying very hard not to be heard."
    )
}

class FictionPreviewProvider : PreviewParameterProvider<SampleFiction> {
    override val values = SampleData.fictions.asSequence()
}

class ChapterPreviewProvider : PreviewParameterProvider<ChapterCardState> {
    override val values = SampleData.chapters.asSequence()
}
