package `in`.jphe.storyvox.playback.cache

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide mutex that serializes calls into the singleton VoxSherpa
 * engines (Piper / Kokoro / Kitten). Held during:
 *  - `VoiceEngine.loadModel` / `KokoroEngine.loadModel` / `KittenEngine.loadModel`
 *    (model load + warm-up)
 *  - `VoiceEngine.generateAudioPCM` / `KokoroEngine.generateAudioPCM` /
 *    `KittenEngine.generateAudioPCM` (per-sentence inference)
 *  - `engine.destroy` (release)
 *
 * Without this serialization, `loadModel` can free the native pointer
 * while a `generateAudioPCM` call is mid-flight on another thread —
 * SIGSEGV (issue #11).
 *
 * Two callers in the system:
 *  - [`in`.jphe.storyvox.playback.tts.EnginePlayer] — foreground
 *    playback, takes the mutex on every sentence the streaming source
 *    generates AND on every `loadAndPlay` model swap.
 *  - [`in`.jphe.storyvox.playback.cache.ChapterRenderJob] (PR-F) —
 *    background WorkManager-driven pre-render. Takes the mutex
 *    per-sentence in the rendering loop.
 *
 * Both share this `@Singleton`. Wraps a [Mutex] (kotlinx.coroutines) —
 * re-entrancy is NOT supported. EnginePlayer's `loadAndPlay` doesn't
 * currently re-enter (model load and generate are serialized, never
 * nested), so this matches the current usage.
 *
 * Pre-PR-F this mutex was a private `Mutex()` inside `EnginePlayer`.
 * Hoisting was needed so the background worker shares the SAME instance
 * with the foreground player — a private `Mutex()` per ChapterRenderJob
 * would still allow concurrent JNI calls between worker and player,
 * which is the issue #11 race.
 */
@Singleton
class EngineMutex @Inject constructor() {
    val mutex: Mutex = Mutex()
}
