package com.ammar.mendamusicplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicService : Service() {

    companion object {
        const val CHANNEL_ID = "MusicChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY_TRACK = "com.ammar.mendamusicplayer.action.PLAY_TRACK"
        const val ACTION_TOGGLE_PLAY = "com.ammar.mendamusicplayer.action.TOGGLE_PLAY"
        const val ACTION_NEXT = "com.ammar.mendamusicplayer.action.NEXT"
        const val ACTION_PREV = "com.ammar.mendamusicplayer.action.PREV"
        const val ACTION_REFRESH = "com.ammar.mendamusicplayer.action.REFRESH"
        const val EXTRA_INDEX = "EXTRA_INDEX"
    }

    inner class LocalBinder : Binder() {
        val service: MusicService get() = this@MusicService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isNoisyReceiverRegistered = false
    private val stateListeners = mutableListOf<() -> Unit>()

    fun addOnPlaybackStateChangedListener(listener: () -> Unit) {
        stateListeners.add(listener)
    }

    fun removeOnPlaybackStateChangedListener(listener: () -> Unit) {
        stateListeners.remove(listener)
    }

    private fun notifyStateChanged() {
        for (listener in stateListeners.toList()) {
            listener()
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> pause(releaseFocus = true)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause(releaseFocus = false)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mediaPlayer?.setVolume(0.2f, 0.2f)
            AudioManager.AUDIOFOCUS_GAIN -> mediaPlayer?.setVolume(1.0f, 1.0f)
        }
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            play()
        }

        override fun onPause() {
            pause()
        }

        override fun onSkipToNext() {
            playNext()
        }

        override fun onSkipToPrevious() {
            playPrevious()
        }

        override fun onSeekTo(pos: Long) {
            seekTo(pos.toInt())
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        mediaSession = MediaSessionCompat(this, "MendaPlayerSession")
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
        mediaSession.setCallback(mediaSessionCallback)
        mediaSession.isActive = true
        updatePlaybackState(PlaybackStateCompat.STATE_NONE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        when (intent?.action) {
            ACTION_PLAY_TRACK -> playTrack(intent.getIntExtra(EXTRA_INDEX, -1))
            ACTION_TOGGLE_PLAY -> if (isPlaying) pause() else play()
            ACTION_NEXT -> playNext()
            ACTION_PREV -> playPrevious()
            else -> refreshForeground()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private var isPrepared = false

    val isPlaying: Boolean
        get() = mediaPlayer?.isPlaying == true

    val currentPosition: Int
        get() = try {
            if (isPrepared) mediaPlayer?.currentPosition ?: 0 else 0
        } catch (e: Exception) {
            0
        }

    val duration: Int
        get() = try {
            if (isPrepared) mediaPlayer?.duration ?: 0 else 0
        } catch (e: Exception) {
            0
        }

    fun seekTo(positionMs: Int) {
        mediaPlayer?.let { player ->
            try {
                if (isPrepared) {
                    player.seekTo(positionMs)
                }
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }
    }

    fun play() {
        val player = mediaPlayer
        if (player == null || !isPrepared) {
            val songs = PlayerState.songList
            if (songs.isNotEmpty()) {
                val targetIndex = if (PlayerState.currentSongIndex in songs.indices) PlayerState.currentSongIndex else 0
                playTrack(targetIndex)
            }
            return
        }
        if (requestAudioFocus()) {
            try {
                player.start()
                registerNoisyReceiver()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                refreshForeground()
                notifyStateChanged()
            } catch (e: IllegalStateException) {
                e.printStackTrace()
                val songs = PlayerState.songList
                if (songs.isNotEmpty()) {
                    val targetIndex = if (PlayerState.currentSongIndex in songs.indices) PlayerState.currentSongIndex else 0
                    playTrack(targetIndex)
                }
            }
        }
    }

    fun pause(releaseFocus: Boolean = true) {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        }
        unregisterNoisyReceiver()
        if (releaseFocus) {
            abandonAudioFocus()
        }
        refreshForeground()
        notifyStateChanged()
    }

    fun stop() {
        isPrepared = false
        mediaPlayer?.reset()
        unregisterNoisyReceiver()
        abandonAudioFocus()
        PlayerState.currentSongIndex = -1
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notifyStateChanged()
    }

    fun playTrack(index: Int) {
        val songs = PlayerState.songList
        if (songs.isEmpty() || index < 0 || index >= songs.size) return

        PlayerState.currentSongIndex = index

        if (!requestAudioFocus()) return

        isPrepared = false
        try {
            val player = getOrCreateMediaPlayer()
            player.reset()
            val song = songs[index]
            player.setDataSource(applicationContext, song.uri)
            player.prepareAsync()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        refreshForeground()
        notifyStateChanged()
    }

    fun playNext() {
        val songs = PlayerState.songList
        if (songs.isEmpty()) return

        var nextIndex = PlayerState.currentSongIndex + 1

        if (PlayerState.isShuffleEnabled) {
            val positionInShuffle = PlayerState.shuffledIndices.indexOf(PlayerState.currentSongIndex)
            if (positionInShuffle >= 0 && positionInShuffle < PlayerState.shuffledIndices.size - 1) {
                nextIndex = PlayerState.shuffledIndices[positionInShuffle + 1]
            } else {
                PlayerState.shuffledIndices = songs.indices.shuffled().toMutableList()
                nextIndex = PlayerState.shuffledIndices[0]
            }
        } else if (nextIndex >= songs.size) {
            nextIndex = 0
        }

        playTrack(nextIndex)
    }

    fun playPrevious() {
        val songs = PlayerState.songList
        if (songs.isEmpty()) return

        var prevIndex = PlayerState.currentSongIndex - 1

        if (PlayerState.isShuffleEnabled) {
            val positionInShuffle = PlayerState.shuffledIndices.indexOf(PlayerState.currentSongIndex)
            if (positionInShuffle > 0 && positionInShuffle < PlayerState.shuffledIndices.size) {
                prevIndex = PlayerState.shuffledIndices[positionInShuffle - 1]
            } else {
                prevIndex = PlayerState.shuffledIndices.lastOrNull() ?: 0
            }
        } else if (prevIndex < 0) {
            prevIndex = songs.size - 1
        }

        playTrack(prevIndex)
    }

    private fun getOrCreateMediaPlayer(): MediaPlayer {
        mediaPlayer?.let { return it }
        val player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnPreparedListener { mp ->
                isPrepared = true
                mp.start()
                registerNoisyReceiver()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                refreshForeground()
                notifyStateChanged()
            }
            setOnCompletionListener {
                playNext()
            }
            setOnErrorListener { mp, what, extra ->
                isPrepared = false
                mp.reset()
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                notifyStateChanged()
                true
            }
        }
        mediaPlayer = player
        return player
    }

    private fun requestAudioFocus(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest == null) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
        }

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun registerNoisyReceiver() {
        if (!isNoisyReceiverRegistered) {
            registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            isNoisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (isNoisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            isNoisyReceiverRegistered = false
        }
    }

    private fun updatePlaybackState(state: Int) {
        val position = when (state) {
            PlaybackStateCompat.STATE_PLAYING,
            PlaybackStateCompat.STATE_PAUSED -> currentPosition.toLong()
            else -> PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        }
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(state, position, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun refreshForeground() {
        val idx = PlayerState.currentSongIndex
        val hasSong = idx >= 0 && idx < PlayerState.songList.size
        val songName = if (hasSong) PlayerState.songList[idx].name else "Unknown Song"
        val songUriStr = if (hasSong) PlayerState.songList[idx].uri.toString() else null
        val playing = isPlaying

        startForeground(NOTIFICATION_ID, buildNotification(songName, playing))

        if (songUriStr != null) {
            serviceScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    loadAlbumArt(songUriStr)
                }
                if (bitmap != null) {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(NOTIFICATION_ID, buildNotification(songName, playing, bitmap))
                }
            }
        }
    }

    private fun buildNotification(songName: String, isPlaying: Boolean, largeIcon: Bitmap? = null): android.app.Notification {
        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pOpenApp = PendingIntent.getActivity(this, 99, openAppIntent, pendingFlags)

        val prevIntent = Intent(this, MusicService::class.java).setAction(ACTION_PREV)
        val pPrev = PendingIntent.getService(this, 0, prevIntent, pendingFlags)

        val playPauseIntent = Intent(this, MusicService::class.java).setAction(ACTION_TOGGLE_PLAY)
        val pPlayPause = PendingIntent.getService(this, 1, playPauseIntent, pendingFlags)

        val nextIntent = Intent(this, MusicService::class.java).setAction(ACTION_NEXT)
        val pNext = PendingIntent.getService(this, 2, nextIntent, pendingFlags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Menda Player")
            .setContentText(songName)
            .setSmallIcon(R.drawable.ic_play_vec)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setContentIntent(pOpenApp)
            .setOngoing(isPlaying)
            .addAction(R.drawable.ic_prev_vec, "Prev", pPrev)
            .addAction(
                if (isPlaying) R.drawable.ic_pause_vec else R.drawable.ic_play_vec,
                if (isPlaying) "Pause" else "Play",
                pPlayPause
            )
            .addAction(R.drawable.ic_next_vec, "Next", pNext)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )

        largeIcon?.let { builder.setLargeIcon(it) }
        return builder.build()
    }

    private fun loadAlbumArt(uriStr: String): Bitmap? {
        AlbumArtCache.getBitmapFromMemCache(uriStr)?.let { return it }

        var newBitmap: Bitmap? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, Uri.parse(uriStr))
            val art = retriever.embeddedPicture
            if (art != null) {
                val options = BitmapFactory.Options()
                options.inSampleSize = 4
                options.inPreferredConfig = Bitmap.Config.RGB_565
                newBitmap = BitmapFactory.decodeByteArray(art, 0, art.size, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            retriever.release()
        }

        newBitmap?.let { AlbumArtCache.addBitmapToMemoryCache(uriStr, it) }
        return newBitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        unregisterNoisyReceiver()
        abandonAudioFocus()
        mediaPlayer?.release()
        mediaPlayer = null
        mediaSession.isActive = false
        mediaSession.release()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
