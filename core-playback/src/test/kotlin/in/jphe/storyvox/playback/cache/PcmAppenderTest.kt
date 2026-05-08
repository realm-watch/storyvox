package `in`.jphe.storyvox.playback.cache

import `in`.jphe.storyvox.playback.tts.Sentence
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PcmAppenderTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pcm-appender-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun newAppender(basename: String = "abc"): PcmAppender = PcmAppender(
        pcmFile = File(dir, "$basename.pcm"),
        metaFile = File(dir, "$basename.meta.json"),
        indexFile = File(dir, "$basename.idx.json"),
        sampleRate = 22050,
        chapterId = "skypride/ch1",
        voiceId = "cori",
        chunkerVersion = 1,
        speedHundredths = 100,
        pitchHundredths = 100,
    )

    @Test
    fun `append two sentences then finalize produces correct files`() {
        val app = newAppender()
        val s0 = Sentence(0, 0, 10, "First.")
        val s1 = Sentence(1, 11, 20, "Second.")
        val pcm0 = ByteArray(100) { 0x11 }
        val pcm1 = ByteArray(50)  { 0x22 }

        app.appendSentence(s0, pcm0, trailingSilenceMs = 350)
        app.appendSentence(s1, pcm1, trailingSilenceMs = 350)
        app.finalize()

        // pcm = concat of both
        val pcmRead = File(dir, "abc.pcm").readBytes()
        assertEquals(150, pcmRead.size)
        assertArrayEquals(pcm0, pcmRead.copyOfRange(0, 100))
        assertArrayEquals(pcm1, pcmRead.copyOfRange(100, 150))

        // idx.json present + correct
        val idxText = File(dir, "abc.idx.json").readText()
        val idx = pcmCacheJson.decodeFromString(PcmIndex.serializer(), idxText)
        assertEquals(22050, idx.sampleRate)
        assertEquals(2, idx.sentenceCount)
        assertEquals(150L, idx.totalBytes)
        assertEquals(0L, idx.sentences[0].byteOffset)
        assertEquals(100, idx.sentences[0].byteLen)
        assertEquals(100L, idx.sentences[1].byteOffset)
        assertEquals(50, idx.sentences[1].byteLen)
        assertEquals(0, idx.sentences[0].start)
        assertEquals(11, idx.sentences[1].start)
        assertEquals(350, idx.sentences[0].trailingSilenceMs)

        // meta.json present + carries chapterId
        val metaText = File(dir, "abc.meta.json").readText()
        val meta = pcmCacheJson.decodeFromString(PcmMeta.serializer(), metaText)
        assertEquals("skypride/ch1", meta.chapterId)
        assertEquals("cori", meta.voiceId)
        assertEquals(22050, meta.sampleRate)
        assertEquals(1, meta.chunkerVersion)
    }

    @Test
    fun `abandon deletes all three files`() {
        val app = newAppender()
        app.appendSentence(Sentence(0, 0, 10, "Hi."), ByteArray(20), trailingSilenceMs = 350)
        // pcm + meta exist after construction+append; idx will not yet
        assertTrue(File(dir, "abc.pcm").exists())
        assertTrue(File(dir, "abc.meta.json").exists())
        assertFalse(File(dir, "abc.idx.json").exists())

        app.abandon()

        assertFalse(File(dir, "abc.pcm").exists())
        assertFalse(File(dir, "abc.meta.json").exists())
        assertFalse(File(dir, "abc.idx.json").exists())
    }

    @Test
    fun `empty pcm is skipped silently`() {
        val app = newAppender()
        app.appendSentence(Sentence(0, 0, 10, "Hi."), ByteArray(0), trailingSilenceMs = 350)
        app.appendSentence(Sentence(1, 11, 20, "There."), ByteArray(40) { 0x33 }, trailingSilenceMs = 350)
        app.finalize()

        val idx = pcmCacheJson.decodeFromString(
            PcmIndex.serializer(),
            File(dir, "abc.idx.json").readText(),
        )
        // Only the 40-byte sentence made it
        assertEquals(1, idx.sentenceCount)
        assertEquals(40L, idx.totalBytes)
        assertEquals(1, idx.sentences[0].i)
    }

    @Test
    fun `finalize after abandon throws`() {
        val app = newAppender()
        app.abandon()
        var threw = false
        try { app.finalize() } catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun `append after finalize throws`() {
        val app = newAppender()
        app.appendSentence(Sentence(0, 0, 10, "Hi."), ByteArray(10), trailingSilenceMs = 350)
        app.finalize()
        var threw = false
        try {
            app.appendSentence(Sentence(1, 11, 20, "Bye."), ByteArray(10), trailingSilenceMs = 350)
        } catch (_: IllegalStateException) { threw = true }
        assertTrue(threw)
    }
}
