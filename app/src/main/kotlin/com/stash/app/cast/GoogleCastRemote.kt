package com.stash.app.cast

import android.net.Uri
import android.util.Log
import com.google.android.gms.cast.Cast
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.stash.core.media.cast.CastMedia
import com.stash.core.media.cast.CastRemote
import com.stash.core.media.cast.CastStatus

/**
 * [CastRemote] over one [CastSession]'s [RemoteMediaClient]: a single song on
 * the Default Media Receiver. Main thread only, like the SDK.
 */
internal class GoogleCastRemote(val session: CastSession) : CastRemote {

    private val listeners = mutableListOf<() -> Unit>()

    /**
     * The last contentId the receiver reported. A finished song's status can
     * arrive without its MediaInfo; it still belongs to the song that played.
     */
    private var lastSeenContentId: String? = null

    /** A load the SDK rejected outright; reported as an ERROR for that song until the next load. */
    private var failedContentId: String? = null

    /** The newest load we sent. Results for older ones are stale: a quick skip replaces them. */
    private var requestedContentId: String? = null

    private val client: RemoteMediaClient? get() = session.remoteMediaClient

    /** The client [mediaCallback] is registered on; the SDK may hand out a new one after a suspend. */
    private var registeredClient: RemoteMediaClient? = null

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() = notifyListeners()
        override fun onMetadataUpdated() = notifyListeners()
    }

    private val castListener = object : Cast.Listener() {
        override fun onVolumeChanged() = notifyListeners()
    }

    init {
        registerClient()
        session.addCastListener(castListener)
    }

    /** After a suspended session resumes: follow the session's current client and report fresh status. */
    fun onResumed() {
        registerClient()
        notifyListeners()
    }

    private fun registerClient() {
        val current = client
        if (current === registeredClient) return
        registeredClient?.unregisterCallback(mediaCallback)
        current?.registerCallback(mediaCallback)
        registeredClient = current
    }

    /**
     * Status as it stood when the session started ending. By the time the
     * playback service hands playback back to the phone the receiver may have
     * cleared its status; this keeps the position it reached.
     */
    private var frozen: CastStatus? = null

    fun freeze() {
        if (frozen == null) frozen = status
    }

    /** Called when the session goes away; the object is dead after this. */
    fun release() {
        registeredClient?.unregisterCallback(mediaCallback)
        registeredClient = null
        session.removeCastListener(castListener)
        listeners.clear()
    }

    override val status: CastStatus
        get() {
            frozen?.let { return it }
            val volume = runCatching { session.volume.toFloat() }.getOrDefault(1f)
            val muted = runCatching { session.isMute }.getOrDefault(false)
            failedContentId?.let {
                return CastStatus(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.ERROR, it, 0L, 0L, volume, muted)
            }
            val remote = client ?: return CastStatus.EMPTY.copy(volume = volume, muted = muted)
            val media = remote.mediaStatus
            media?.mediaInfo?.contentId?.let { lastSeenContentId = it }
            val state = when (media?.playerState) {
                MediaStatus.PLAYER_STATE_PLAYING -> CastStatus.PlayerState.PLAYING
                MediaStatus.PLAYER_STATE_PAUSED -> CastStatus.PlayerState.PAUSED
                MediaStatus.PLAYER_STATE_BUFFERING -> CastStatus.PlayerState.BUFFERING
                MediaStatus.PLAYER_STATE_LOADING -> CastStatus.PlayerState.LOADING
                else -> CastStatus.PlayerState.IDLE
            }
            val idleReason = if (state != CastStatus.PlayerState.IDLE) {
                CastStatus.IdleReason.NONE
            } else {
                when (media?.idleReason) {
                    MediaStatus.IDLE_REASON_FINISHED -> CastStatus.IdleReason.FINISHED
                    MediaStatus.IDLE_REASON_CANCELED -> CastStatus.IdleReason.CANCELLED
                    MediaStatus.IDLE_REASON_INTERRUPTED -> CastStatus.IdleReason.INTERRUPTED
                    MediaStatus.IDLE_REASON_ERROR -> CastStatus.IdleReason.ERROR
                    else -> CastStatus.IdleReason.NONE
                }
            }
            return CastStatus(
                playerState = state,
                idleReason = idleReason,
                contentId = media?.mediaInfo?.contentId ?: lastSeenContentId,
                positionMs = remote.approximateStreamPosition.coerceAtLeast(0),
                durationMs = remote.streamDuration.coerceAtLeast(0),
                volume = volume,
                muted = muted,
            )
        }

    override fun load(media: CastMedia, startPositionMs: Long, autoplay: Boolean) {
        val remote = client ?: return
        failedContentId = null
        requestedContentId = media.contentId
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            media.title?.let { putString(MediaMetadata.KEY_TITLE, it) }
            media.artist?.let { putString(MediaMetadata.KEY_ARTIST, it) }
            media.album?.let { putString(MediaMetadata.KEY_ALBUM_TITLE, it) }
            media.artworkUrl?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val info = MediaInfo.Builder(media.contentId)
            .setContentUrl(media.url)
            .setContentType(media.contentType)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata)
            .apply { if (media.durationMs > 0) setStreamDuration(media.durationMs) }
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(autoplay)
            .setCurrentTime(startPositionMs)
            .build()
        remote.load(request).setResultCallback { result ->
            val code = result.status.statusCode
            // A newer load replaces this one when the user skips quickly; that
            // result is REPLACED/CANCELED and says nothing about the song now
            // loading. Recording it would pin status to the old song for good.
            val superseded = media.contentId != requestedContentId ||
                code == CastStatusCodes.REPLACED || code == CastStatusCodes.CANCELED
            if (!result.status.isSuccess && !superseded) {
                Log.w(TAG, "load rejected: $code ${result.status.statusMessage}")
                failedContentId = media.contentId
                notifyListeners()
            }
        }
    }

    override fun play() {
        client?.play()
    }

    override fun pause() {
        client?.pause()
    }

    override fun seekTo(positionMs: Long) {
        client?.seek(MediaSeekOptions.Builder().setPosition(positionMs).build())
    }

    override fun stop() {
        client?.stop()
    }

    override fun setVolume(level: Float) {
        runCatching { session.volume = level.coerceIn(0f, 1f).toDouble() }
            .onFailure { Log.w(TAG, "setVolume failed", it) }
    }

    override fun setMuted(muted: Boolean) {
        runCatching { session.isMute = muted }
            .onFailure { Log.w(TAG, "setMute failed", it) }
    }

    override fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    override fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners() {
        listeners.toList().forEach { it() }
    }

    private companion object {
        const val TAG = "GoogleCastRemote"
    }
}
