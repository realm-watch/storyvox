package `in`.jphe.storyvox.playback.tts.source

import `in`.jphe.storyvox.playback.SentenceRange
import `in`.jphe.storyvox.playback.cache.PcmIndex
import `in`.jphe.storyvox.playback.cache.PcmIndexEntry
import `in`.jphe.storyvox.playback.cache.pcmCacheJson
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * PR-E (#86) — reads a finalized PCM cache entry from disk and serves
 * [PcmChunk]s to [`in`.jphe.storyvox.playback.tts.EnginePlayer]'s
 * consumer thread.
 *
 * Constructed by `EnginePlayer.startPlaybackPipeline` when
 * [`in`.jphe.storyvox.playback.cache.PcmCache.isComplete] returns true
 * for the (chapter, voice, speed, pitch, chunkerVersion, dictHash)
 * key. The consumer treats this source identically to
 * [EngineStreamingSource] — pull a chunk, write to AudioTrack, fire
 * UI sentence-transition events. The difference: this source NEVER
 * blocks meaningfully (no engine inference), so [bufferHeadroomMs]
 * stays effectively unbounded and PR-B's pause-buffer-resume UI never
 * fires for cached chapters.
 *
 * **Pacing.** The consumer's `track.write()` blocks once the AudioTrack
 * hardware buffer is full (~2 s of audio at minBufferSize on Tab A7
 * Lite). That's what regulates this source's effective rate — without
 * that back-pressure, the consumer would freerun through the entire
 * cached chapter in milliseconds and the UI would flash sentence
 * boundaries faster than the audio plays.
 *
 * **mmap vs read.** The default path mmap's the entire `.pcm` file
 * via `FileChannel.map(READ_ONLY, 0, len)`. mmap is preferable
 * because the OS page cache absorbs the read cost — sequential mmap
 * access on UFS 2.0 internal flash is >100 MB/s effective. Fallback:
 * some 32-bit emulator kernels reject mmap requests > 1 GB with
 * EINVAL. In that case we fall back to [RandomAccessFile.read] into
 * a [ByteArray] per sentence, which is correctness-equivalent but
 * allocates per chunk. The fallback is detected at construction
 * time and never re-tried mid-playback.
 */
class CacheFileSource private constructor(
    private val pcmFile: File,
    private val index: PcmIndex,
    startSentenceIndex: Int,
    /** True if mmap succeeded; false → fallback to read path. */
    private val mapped: MappedByteBuffer?,
    private val randomAccess: RandomAccessFile,
) : PcmSource {

    override val sampleRate: Int = index.sampleRate

    /** Cursor into [PcmIndex.sentences]. Mutated by [nextChunk] and
     *  [seekToCharOffset]; single-threaded access (the consumer thread). */
    @Volatile private var cursor: Int = startSentenceIndex.coerceIn(0, index.sentences.size)

    private val _bufferHeadroomMs = MutableStateFlow(Long.MAX_VALUE)

    /**
     * Always reports an effectively unbounded headroom. Cache files
     * never underrun — a cached chapter has every sentence already on
     * disk. The streaming-source headroom flow drives PR-B's
     * pause-buffer-resume UI; for the cache source we want that UI to
     * never fire (which is correct), so we report a huge constant.
     */
    override val bufferHeadroomMs: StateFlow<Long> = _bufferHeadroomMs.asStateFlow()

    /** Cache source has no producer queue. Reported as 0/0 to the
     *  Debug overlay (issue #290). */
    override fun producerQueueDepth(): Int = 0
    override fun producerQueueCapacity(): Int = 0

    /** No headroom tracking for cache files — they don't underrun. */
    override fun decrementHeadroomForChunk(chunk: PcmChunk) = Unit

    override suspend fun nextChunk(): PcmChunk? = withContext(Dispatchers.IO) {
        val entries = index.sentences
        val i = cursor
        if (i >= entries.size) return@withContext null
        val e = entries[i]
        cursor = i + 1
        val pcm = readPcmFor(e)
        val silenceBytes = silenceBytesFor(e.trailingSilenceMs, sampleRate)
        PcmChunk(
            sentenceIndex = e.i,
            range = SentenceRange(e.i, e.start, e.end),
            pcm = pcm,
            trailingSilenceBytes = silenceBytes,
        )
    }

    override suspend fun seekToCharOffset(charOffset: Int) {
        // Find the latest sentence whose start <= charOffset. Matches
        // EngineStreamingSource.seekToCharOffset's mapping so both
        // sources behave identically post-seek. Offset before the first
        // sentence → cursor=0; offset past the last sentence → cursor
        // at the last sentence (which then exhausts after one nextChunk).
        val target = index.sentences.indexOfLast { it.start <= charOffset }
            .takeIf { it >= 0 } ?: 0
        cursor = target
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        // Release the underlying RandomAccessFile so the file descriptor
        // is returned. mmap'd MappedByteBuffer cleanup happens via
        // GC + Cleaner; we can't force-unmap on JVM without sun.misc
        // unsafe access. Closing the channel/file that produced the
        // buffer is enough — the buffer remains valid until GC, and
        // on Linux the kernel page-cache reclaim runs anyway.
        runCatching { randomAccess.close() }
        Unit
    }

    /**
     * Cache source has no in-flight cache write to finalize — the
     * file was finalized before this source was constructed (that's
     * the [`in`.jphe.storyvox.playback.cache.PcmCache.isComplete]
     * precondition). Inherited default would also no-op; override
     * is explicit for documentation.
     */
    override fun finalizeCache() = Unit

    /** Read the bytes for a single sentence's PCM range. mmap path:
     *  slice the MappedByteBuffer view. Read path: position +
     *  read into a fresh ByteArray. */
    private fun readPcmFor(entry: PcmIndexEntry): ByteArray {
        val mapped = this.mapped
        if (mapped != null) {
            // Slice view — no copy of the underlying bytes. We DO need
            // to copy into a ByteArray because EnginePlayer's consumer
            // thread calls `track.write(byteArray, off, len)`, which
            // doesn't accept a ByteBuffer in the byte[] AudioTrack
            // API path. Future optimization: extend PcmChunk to carry
            // a ByteBuffer alongside the byte[]; saves one copy per
            // sentence (~1 ms on Tab A7 Lite, not material).
            val out = ByteArray(entry.byteLen)
            mapped.position(entry.byteOffset.toInt())
            mapped.get(out, 0, entry.byteLen)
            return out
        }
        // Read-path fallback.
        val raf = randomAccess
        val out = ByteArray(entry.byteLen)
        synchronized(raf) {
            raf.seek(entry.byteOffset)
            var read = 0
            while (read < entry.byteLen) {
                val n = raf.read(out, read, entry.byteLen - read)
                if (n < 0) break
                read += n
            }
        }
        return out
    }

    companion object {
        /**
         * Open a `CacheFileSource` for an already-finalized cache entry.
         * `EnginePlayer.startPlaybackPipeline` calls this when
         * `PcmCache.isComplete(key)` returns true.
         *
         * @throws IOException if reading the index fails or the pcm
         *  file is truncated. EnginePlayer catches this and falls
         *  back to the streaming source — a corrupt cache should
         *  re-render rather than crash playback.
         */
        @Throws(IOException::class)
        suspend fun open(
            pcmFile: File,
            indexFile: File,
            startSentenceIndex: Int = 0,
        ): CacheFileSource = withContext(Dispatchers.IO) {
            val index = pcmCacheJson.decodeFromString(
                PcmIndex.serializer(),
                indexFile.readText(),
            )
            // Validate: the .pcm file must be at least totalBytes long.
            // PR-D's appender writes exactly totalBytes; a smaller file
            // means truncation (disk-full mid-finalize, or wiped by
            // the OS cache-dir reaper between finalize and open).
            if (pcmFile.length() < index.totalBytes) {
                throw IOException(
                    "PCM cache file truncated: ${pcmFile.length()} < ${index.totalBytes}",
                )
            }
            val raf = RandomAccessFile(pcmFile, "r")
            val mapped: MappedByteBuffer? = runCatching {
                raf.channel.map(FileChannel.MapMode.READ_ONLY, 0L, raf.length())
            }.getOrNull()

            CacheFileSource(
                pcmFile = pcmFile,
                index = index,
                startSentenceIndex = startSentenceIndex,
                mapped = mapped,
                randomAccess = raf,
            )
        }
    }
}
