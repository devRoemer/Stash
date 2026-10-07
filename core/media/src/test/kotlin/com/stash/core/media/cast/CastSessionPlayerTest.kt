package com.stash.core.media.cast

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CastSessionPlayerTest {

    /** The stopped local player: a queue, an index and a position, nothing more. */
    private class QueuePlayer(ids: List<String>) : SimpleBasePlayer(Looper.getMainLooper()) {
        val items = ids.map(::item).toMutableList()
        var index = 0
        var positionMs = 0L
        var wantPlay = false
        var repeat = Player.REPEAT_MODE_OFF
        val calls = mutableListOf<String>()

        override fun getState(): State = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().addAllCommands().build())
            .setPlaylist(items.mapIndexed { i, it -> MediaItemData.Builder("uid$i").setMediaItem(it).build() })
            .setCurrentMediaItemIndex(if (items.isEmpty()) C.INDEX_UNSET else index)
            .setContentPositionMs(positionMs)
            .setPlayWhenReady(wantPlay, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(Player.STATE_IDLE)
            .setRepeatMode(repeat)
            .build()

        override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
            index = mediaItemIndex
            this.positionMs = if (positionMs == C.TIME_UNSET) 0 else positionMs
            return done()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            calls += "local playWhenReady=$playWhenReady"
            wantPlay = playWhenReady
            return done()
        }

        override fun handlePrepare(): ListenableFuture<*> { calls += "local prepare"; return done() }
        override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> { repeat = repeatMode; return done() }

        override fun handleReplaceMediaItems(fromIndex: Int, toIndex: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
            repeat(toIndex - fromIndex) { items.removeAt(fromIndex) }
            items.addAll(fromIndex, mediaItems)
            return done()
        }

        override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
            repeat(toIndex - fromIndex) { items.removeAt(fromIndex) }
            if (index >= toIndex) index -= toIndex - fromIndex
            return done()
        }

        private fun done() = Futures.immediateVoidFuture()
    }

    /** A speaker that records commands; tests push its status with [report]. */
    private class FakeRemote : CastRemote {
        override var status = CastStatus.EMPTY
        val loads = mutableListOf<Triple<String, Long, Boolean>>()
        val calls = mutableListOf<String>()
        private val listeners = mutableListOf<() -> Unit>()

        val lastContentId: String get() = loads.last().first

        override fun load(media: CastMedia, startPositionMs: Long, autoplay: Boolean) {
            loads += Triple(media.contentId, startPositionMs, autoplay)
        }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
        override fun stop() { calls += "stop" }
        override fun setVolume(level: Float) { calls += "volume:$level" }
        override fun setMuted(muted: Boolean) { calls += "muted:$muted" }
        override fun addListener(listener: () -> Unit) { listeners += listener }
        override fun removeListener(listener: () -> Unit) { listeners -= listener }

        fun report(
            state: CastStatus.PlayerState,
            idle: CastStatus.IdleReason = CastStatus.IdleReason.NONE,
            positionMs: Long = 0L,
            durationMs: Long = 200_000L,
            contentId: String? = lastContentId,
        ) {
            status = CastStatus(state, idle, contentId, positionMs, durationMs, 0.5f, false)
            listeners.toList().forEach { it() }
        }
    }

    private var now = 0L

    private fun cast(local: QueuePlayer, remote: FakeRemote, positionMs: Long = 0L, play: Boolean = true) =
        CastSessionPlayer(local, mediaFor = { item, contentId -> media(item, contentId) }, clock = { now })
            .apply { attach(local, remote, positionMs, play) }

    @Test fun `attaching loads the current song where the phone was`() {
        val local = QueuePlayer(listOf("a", "b")).apply { index = 1 }
        val remote = FakeRemote()
        val player = cast(local, remote, positionMs = 42_000L, play = true)

        assertThat(remote.loads.single().second).isEqualTo(42_000L)
        assertThat(remote.loads.single().third).isTrue()
        assertThat(remote.lastContentId).startsWith("b#")
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
        assertThat(player.deviceInfo.playbackType).isEqualTo(androidx.media3.common.DeviceInfo.PLAYBACK_TYPE_REMOTE)
    }

    @Test fun `position, duration and state come from the speaker`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING, positionMs = 61_000L, durationMs = 180_000L)

        assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
        assertThat(player.isPlaying).isTrue()
        assertThat(player.currentPosition).isEqualTo(61_000L)
        assertThat(player.duration).isEqualTo(180_000L)
    }

    @Test fun `play and pause go to the speaker, never the phone`() {
        val local = QueuePlayer(listOf("a"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        player.pause()
        now += 5_000
        remote.report(CastStatus.PlayerState.PAUSED)
        player.play()

        assertThat(remote.calls).containsExactly("pause", "play").inOrder()
        assertThat(local.calls).isEmpty()
    }

    @Test fun `a pause that arrives while the song loads is applied when the speaker answers`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote, play = true)
        player.pause() // the load (autoplay) is still in flight
        assertThat(remote.calls).isEmpty()

        remote.report(CastStatus.PlayerState.PLAYING)

        assertThat(remote.calls).containsExactly("pause")
        assertThat(player.playWhenReady).isFalse()
    }

    @Test fun `the idle a speaker reports before loading is still buffering, and play doesn't load twice`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote, play = false)
        remote.report(CastStatus.PlayerState.IDLE)

        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
        player.play()
        assertThat(remote.loads).hasSize(1)

        remote.report(CastStatus.PlayerState.PAUSED)
        assertThat(remote.calls).containsExactly("play")
    }

    @Test fun `a seek within the song moves the speaker, not the queue`() {
        val local = QueuePlayer(listOf("a", "b"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        player.seekTo(90_000L)

        assertThat(remote.calls).containsExactly("seek:90000")
        assertThat(remote.loads).hasSize(1)
        assertThat(local.index).isEqualTo(0)
    }

    @Test fun `skipping moves the phone's queue and loads the next song on the speaker`() {
        val local = QueuePlayer(listOf("a", "b", "c"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING, positionMs = 10_000L)

        player.seekToNextMediaItem()

        assertThat(local.index).isEqualTo(1)
        assertThat(remote.loads).hasSize(2)
        assertThat(remote.lastContentId).startsWith("b#")
        assertThat(remote.loads.last().second).isEqualTo(0L)
        assertThat(remote.loads.last().third).isTrue()
    }

    @Test fun `when the speaker finishes a song the queue moves on`() {
        val local = QueuePlayer(listOf("a", "b"))
        val remote = FakeRemote()
        cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.FINISHED)
        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.FINISHED, contentId = remote.loads.first().first)

        assertThat(local.index).isEqualTo(1)
        assertThat(remote.loads).hasSize(2) // the repeated FINISHED status didn't skip twice
        assertThat(remote.lastContentId).startsWith("b#")
    }

    @Test fun `finishing the last song ends playback`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.FINISHED)

        assertThat(player.playbackState).isEqualTo(Player.STATE_ENDED)
        assertThat(remote.loads).hasSize(1)
    }

    @Test fun `repeat one plays the same song again`() {
        val local = QueuePlayer(listOf("a", "b")).apply { repeat = Player.REPEAT_MODE_ONE }
        val remote = FakeRemote()
        cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.FINISHED)

        assertThat(local.index).isEqualTo(0)
        assertThat(remote.loads).hasSize(2)
        assertThat(remote.lastContentId).startsWith("a#")
    }

    @Test fun `repeat all over a single song loads it again`() {
        val local = QueuePlayer(listOf("a")).apply { repeat = Player.REPEAT_MODE_ALL }
        val remote = FakeRemote()
        cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.FINISHED)

        assertThat(remote.loads).hasSize(2)
    }

    @Test fun `swapping the current song's placeholder for its resolved URL doesn't restart it`() {
        val local = QueuePlayer(listOf("a", "b"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        remote.report(CastStatus.PlayerState.PLAYING)

        player.replaceMediaItem(0, MediaItem.Builder().setMediaId("a").setUri("https://cdn.example/a.flac").build())
        player.removeMediaItem(1)

        assertThat(remote.loads).hasSize(1)
    }

    @Test fun `a speaker error becomes the player error`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a", "b")), remote)

        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.ERROR)

        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.playerError?.errorCode).isEqualTo(PlaybackException.ERROR_CODE_REMOTE_ERROR)
    }

    @Test fun `a song that can't be served is an error, not a silent load`() {
        val local = QueuePlayer(listOf("a"))
        val remote = FakeRemote()
        val player = CastSessionPlayer(local, mediaFor = { _, _ -> null }, clock = { now })
            .apply { attach(local, remote, 0L, true) }

        assertThat(remote.loads).isEmpty()
        assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
        assertThat(player.playerError?.errorCode).isEqualTo(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
    }

    @Test fun `prepare after an error loads the song again`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.ERROR)

        player.prepare()

        assertThat(remote.loads).hasSize(2)
        assertThat(player.playerError).isNull()
    }

    @Test fun `seeking after the queue ended starts the song again`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING)
        remote.report(CastStatus.PlayerState.IDLE, CastStatus.IdleReason.FINISHED)

        player.seekTo(0, 0L)

        assertThat(remote.loads).hasSize(2)
        assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
    }

    @Test fun `play after stop loads the song where it stopped`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING, positionMs = 30_000L)

        player.stop()
        player.play()

        assertThat(remote.calls).contains("stop")
        assertThat(remote.loads).hasSize(2)
        assertThat(remote.loads.last().second).isEqualTo(30_000L)
    }

    @Test fun `a pause from the speaker itself shows on the phone`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING)
        now += 10_000 // well past our own last command

        remote.report(CastStatus.PlayerState.PAUSED)

        assertThat(player.playWhenReady).isFalse()
    }

        @Test fun `volume keys drive the speaker`() {
        val remote = FakeRemote()
        val player = cast(QueuePlayer(listOf("a")), remote)
        remote.report(CastStatus.PlayerState.PLAYING) // volume 0.5 → 10 of 20

        @Suppress("DEPRECATION") player.increaseDeviceVolume()

        assertThat(player.deviceVolume).isEqualTo(10)
        assertThat(remote.calls).containsExactly("volume:0.55")
    }

    @Test fun `speed isn't offered while casting`() {
        val player = cast(QueuePlayer(listOf("a")), FakeRemote())
        assertThat(player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)).isFalse()
    }

    @Test fun `detaching hands back the speaker's song and position`() {
        val local = QueuePlayer(listOf("a", "b"))
        val remote = FakeRemote()
        val player = cast(local, remote)
        player.seekToNextMediaItem()
        remote.report(CastStatus.PlayerState.PLAYING, positionMs = 73_000L)

        val handoff = player.detach()

        assertThat(handoff).isEqualTo(CastSessionPlayer.Handoff(mediaItemIndex = 1, positionMs = 73_000L))
        assertThat(player.isAttached).isFalse()
    }

    private companion object {
        fun item(id: String) = MediaItem.Builder().setMediaId(id).setUri("file:///music/$id.flac").build()
        fun media(item: MediaItem, contentId: String) = CastMedia(
            contentId = contentId,
            url = "http://192.168.1.2:1234/t/m/${item.mediaId}",
            contentType = "audio/flac",
            title = item.mediaId,
            artist = null,
            album = null,
            artworkUrl = null,
            durationMs = 0L,
        )
    }
}
