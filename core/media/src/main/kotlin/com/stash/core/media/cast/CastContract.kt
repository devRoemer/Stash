package com.stash.core.media.cast

import kotlinx.coroutines.flow.StateFlow

/**
 * Google Cast, as the rest of the app sees it (spec 2026-10-06 §2).
 *
 * Implemented in the app module (`com.stash.app.cast`, over the Google Cast
 * SDK), so core:media and the feature modules stay free of Cast SDK types.
 *
 * Main thread only, like the Cast SDK underneath.
 */
interface CastDevices {
    /** Where casting stands. [CastConnection.Unavailable] hides the cast button. */
    val connection: StateFlow<CastConnection>

    /** Speakers found on the network. Only kept fresh between [startScan] and [stopScan]. */
    val devices: StateFlow<List<CastDevice>>

    /** The connected receiver, or null. The playback service plays through it while non-null. */
    val remote: StateFlow<CastRemote?>

    fun startScan()
    fun stopScan()
    fun connect(deviceId: String)
    fun disconnect()
}

data class CastDevice(val id: String, val name: String)

sealed interface CastConnection {
    /** No Cast on this phone (Play Services missing or failed to start). */
    data object Unavailable : CastConnection
    data object Disconnected : CastConnection
    data class Connecting(val deviceName: String) : CastConnection
    data class Connected(val deviceName: String) : CastConnection
}

/**
 * One connected receiver running the Default Media Receiver. It holds a single
 * song at a time: the phone owns the queue (spec §1).
 */
interface CastRemote {
    val status: CastStatus

    fun load(media: CastMedia, startPositionMs: Long, autoplay: Boolean)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun stop()

    /** 0..1, the receiver's own volume. */
    fun setVolume(level: Float)
    fun setMuted(muted: Boolean)

    /** Called on the main thread whenever [status] may have changed. */
    fun addListener(listener: () -> Unit)
    fun removeListener(listener: () -> Unit)
}

/** What the receiver is told to play. [url] points at [CastMediaServer]. */
data class CastMedia(
    /** Identifies the song in [CastStatus.contentId] — the MediaItem's mediaId plus a load counter. */
    val contentId: String,
    val url: String,
    val contentType: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val artworkUrl: String?,
    val durationMs: Long,
)

data class CastStatus(
    val playerState: PlayerState,
    val idleReason: IdleReason,
    /** The [CastMedia.contentId] the receiver holds, or null when it holds nothing. */
    val contentId: String?,
    /** Interpolated between status messages, so it moves smoothly. */
    val positionMs: Long,
    /** 0 when the receiver doesn't know yet. */
    val durationMs: Long,
    val volume: Float,
    val muted: Boolean,
) {
    enum class PlayerState { IDLE, LOADING, BUFFERING, PLAYING, PAUSED }
    enum class IdleReason { NONE, FINISHED, CANCELLED, INTERRUPTED, ERROR }

    companion object {
        val EMPTY = CastStatus(PlayerState.IDLE, IdleReason.NONE, null, 0L, 0L, 1f, false)
    }
}
