package dev.popcorn.companion

import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Bridges the [MediaSession] created by the UI (which owns the ExoPlayer) to the
 * system so that phone-local playback keeps running, and the process stays
 * foreground/unfrozen, while the screen is off or the app is backgrounded.
 *
 * The player lifecycle is owned by [CompanionApp]; this service only exposes the
 * session for the media notification and foreground state. It never releases the
 * player.
 */
class PlaybackService : MediaSessionService() {
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        PlaybackHolder.session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped away from recents: if nothing is actively playing, there is
        // no reason to keep the service (and its notification) around.
        val player = PlaybackHolder.session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }
}

/**
 * Process-wide handle to the active [MediaSession]. The UI sets this when it
 * creates the player and clears it on dispose; [PlaybackService] reads it.
 */
object PlaybackHolder {
    @Volatile
    var session: MediaSession? = null
}
