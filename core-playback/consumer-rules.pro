# Keep MediaSession service classes — referenced by the framework
-keep class in.jphe.storyvox.playback.StoryvoxPlaybackService
-keep class in.jphe.storyvox.playback.auto.StoryvoxAutoBrowserService

# PR-F (#86) — Hilt's WorkerFactory looks up CoroutineWorker subclasses
# by FQN at runtime (the WorkManager runtime instantiates them
# reflectively via the generated HiltWorkerFactory binding). R8 doesn't
# always see HiltWorker-annotated classes as roots from its DCE pass —
# `core-data`'s consumer-rules.pro pins `in.jphe.storyvox.data.work.**`
# the same way for ChapterDownloadWorker; mirror that here for
# `playback.cache.**` so ChapterRenderJob (and any future worker landing
# in this package) survives minified builds.
-keep class in.jphe.storyvox.playback.cache.ChapterRenderJob { *; }

# Kotlinx serialization — keep generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
