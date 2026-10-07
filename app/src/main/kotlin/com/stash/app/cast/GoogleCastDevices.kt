package com.stash.app.cast

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.stash.core.media.cast.CastConnection
import com.stash.core.media.cast.CastDevice
import com.stash.core.media.cast.CastDevices
import com.stash.core.media.cast.CastRemote
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CastDevices] over the Google Cast SDK (spec 2026-10-06 §2).
 *
 * Discovery and connecting go through [MediaRouter] directly — Stash draws its
 * own picker in Compose, because `MediaRouteButton` needs a FragmentActivity
 * and an AppCompat theme. Selecting a cast route makes the SDK start a
 * session; [sessionListener] turns that into [remote].
 *
 * Main thread only. Starts Cast lazily on first injection; until the SDK is
 * up (or forever, without Play Services) [connection] stays Unavailable and
 * the cast button stays hidden.
 */
@Singleton
class GoogleCastDevices @Inject constructor(
    @ApplicationContext private val context: Context,
) : CastDevices {

    private val _connection = MutableStateFlow<CastConnection>(CastConnection.Unavailable)
    override val connection: StateFlow<CastConnection> = _connection.asStateFlow()

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    override val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _remote = MutableStateFlow<CastRemote?>(null)
    override val remote: StateFlow<CastRemote?> = _remote.asStateFlow()

    private var castContext: CastContext? = null
    private val router: MediaRouter by lazy { MediaRouter.getInstance(context) }
    private val selector: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(StashCastOptionsProvider.RECEIVER_APP_ID))
            .build()
    }
    private var scanning = false

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            _connection.value = CastConnection.Connecting(session.deviceName())
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) = onConnected(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            val current = _remote.value as? GoogleCastRemote
            if (current != null && current.session === session) {
                // Back from a suspend: the speaker kept playing, so keep the same
                // remote and the playback service never leaves cast mode.
                current.onResumed()
                _connection.value = CastConnection.Connected(session.deviceName())
            } else {
                onConnected(session)
            }
        }

        // Drop the remote at "ending", not "ended": by "ended" the receiver has
        // already cleared its status, and the playback service needs the last
        // position to hand playback back to the phone.
        override fun onSessionEnding(session: CastSession) = onDisconnected()
        override fun onSessionEnded(session: CastSession, error: Int) = onDisconnected()
        // A suspend is a brief network loss. The speaker plays on, and the SDK
        // either resumes the session (onSessionResumed) or ends it
        // (onSessionEnded); handing playback back to the phone here would stop
        // the speaker's music on every Wi-Fi hiccup.
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            Log.i(TAG, "session suspended ($reason); waiting for resume")
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            Log.w(TAG, "session start failed: $error")
            onDisconnected()
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) = onDisconnected()
    }

    init {
        val playServices = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (playServices == ConnectionResult.SUCCESS) {
            CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context))
                .addOnSuccessListener { ready(it) }
                .addOnFailureListener { Log.w(TAG, "Cast unavailable", it) }
        } else {
            Log.i(TAG, "no Play Services ($playServices) — Cast stays off")
        }
    }

    private fun ready(cast: CastContext) {
        castContext = cast
        _connection.value = CastConnection.Disconnected
        cast.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
        cast.sessionManager.currentCastSession?.takeIf { it.isConnected }?.let(::onConnected)
        if (scanning) startScan()
    }

    private fun onConnected(session: CastSession) {
        (_remote.value as? GoogleCastRemote)?.release()
        _remote.value = GoogleCastRemote(session)
        _connection.value = CastConnection.Connected(session.deviceName())
    }

    private fun onDisconnected() {
        val old = _remote.value as? GoogleCastRemote
        old?.freeze()
        _remote.value = null // the service reads the last status from the wrapper, not from here
        old?.release()
        if (castContext != null) _connection.value = CastConnection.Disconnected
    }

    override fun startScan() {
        scanning = true
        if (castContext == null) return // ready() resumes it
        router.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        publishRoutes()
    }

    override fun stopScan() {
        scanning = false
        if (castContext == null) return
        router.removeCallback(routerCallback)
    }

    override fun connect(deviceId: String) {
        val route = router.routes.firstOrNull { it.id == deviceId } ?: return
        _connection.value = CastConnection.Connecting(route.name)
        router.selectRoute(route)
    }

    override fun disconnect() {
        castContext?.sessionManager?.endCurrentSession(/* stopCasting = */ true)
    }

    private fun publishRoutes() {
        _devices.value = router.routes
            .filter { !it.isDefaultOrBluetooth && it.isEnabled && it.matchesSelector(selector) }
            .map { CastDevice(id = it.id, name = it.name) }
            .sortedBy { it.name.lowercase() }
    }

    private fun CastSession.deviceName(): String = castDevice?.friendlyName ?: "Speaker"

    private companion object {
        const val TAG = "GoogleCastDevices"
    }
}
