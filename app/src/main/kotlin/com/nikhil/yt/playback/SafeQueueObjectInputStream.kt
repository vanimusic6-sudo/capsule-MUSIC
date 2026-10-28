package com.nikhil.yt.playback

import java.io.InputStream
import java.io.InvalidClassException
import java.io.ObjectInputStream
import java.io.ObjectStreamClass

/** Hardening for legacy app-private Java serialized queues; no arbitrary classes. */
internal class SafeQueueObjectInputStream(stream: InputStream) : ObjectInputStream(stream) {
    override fun resolveClass(desc: ObjectStreamClass): Class<*> {
        if (!isApprovedQueueSerializationType(desc.name)) {
            throw InvalidClassException("Unapproved persisted queue class", desc.name)
        }
        return super.resolveClass(desc)
    }

    override fun resolveProxyClass(interfaces: Array<out String>): Class<*> =
        throw InvalidClassException("Dynamic proxies prohibited in persisted queues")
}

internal fun isApprovedQueueSerializationType(name: String): Boolean {
    if (name.startsWith("[")) {
        var dimension = 0
        while (dimension < name.length && name[dimension] == '[') dimension++
        if (dimension >= name.length || dimension > 5) return false
        val component = name.substring(dimension)
        return when {
            component.length == 1 -> component[0] in "ZBCSIFJD"
            component.startsWith("L") && component.endsWith(";") ->
                isApprovedQueueSerializationType(component.substring(1, component.lastIndex))
            else -> false
        }
    }
    return name in APPROVED_CLASSES
}

private val APPROVED_CLASSES: Set<String> = setOf(
    "com.nikhil.yt.models.PersistQueue",
    "com.nikhil.yt.models.PersistPlayerState",
    "com.nikhil.yt.models.QueueType",
    "com.nikhil.yt.models.QueueType$LIST",
    "com.nikhil.yt.models.QueueType$YOUTUBE",
    "com.nikhil.yt.models.QueueType$YOUTUBE_ALBUM_RADIO",
    "com.nikhil.yt.models.QueueType$LOCAL_ALBUM_RADIO",
    "com.nikhil.yt.models.QueueData",
    "com.nikhil.yt.models.QueueData$YouTubeData",
    "com.nikhil.yt.models.QueueData$YouTubeAlbumRadioData",
    "com.nikhil.yt.models.QueueData$LocalAlbumRadioData",
    "com.nikhil.yt.models.MediaMetadata",
    "com.nikhil.yt.models.MediaMetadata$Artist",
    "com.nikhil.yt.models.MediaMetadata$Album",
    "java.lang.String",
    "java.lang.Integer",
    "java.lang.Long",
    "java.lang.Boolean",
    "java.lang.Float",
    "java.lang.Double",
    "java.lang.Number",
    "java.lang.Object",
    "java.util.ArrayList",
    "java.util.LinkedList",
    "java.util.Collections$EmptyList",
    "java.util.Collections$SingletonList",
    "java.util.Collections$UnmodifiableList",
    "java.util.Collections$UnmodifiableRandomAccessList",
    "java.util.Arrays$ArrayList",
    "java.util.ImmutableCollections$List12",
    "java.util.ImmutableCollections$ListN",
    "kotlin.collections.EmptyList",
    "java.time.Ser",
    "java.time.LocalDate",
    "java.time.LocalTime",
    "java.time.LocalDateTime",
)
