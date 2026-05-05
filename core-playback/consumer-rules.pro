# Keep MediaSession service classes — referenced by the framework
-keep class in.jphe.storyvox.playback.StoryvoxPlaybackService
-keep class in.jphe.storyvox.playback.auto.StoryvoxAutoBrowserService

# Kotlinx serialization — keep generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
