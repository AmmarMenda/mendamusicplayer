package com.ammar.mendamusicplayer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class MusicService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        val songName = intent?.getStringExtra("SONG_NAME") ?: "Unknown Song"
        val isPlaying = intent?.getBooleanExtra("IS_PLAYING", true) ?: true
        val songUriStr = intent?.getStringExtra("SONG_URI")

        val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pOpenApp = PendingIntent.getActivity(this, 99, openAppIntent, pendingFlags)

        val prevIntent = Intent("ACTION_PREV").setPackage(packageName)
        val pPrev = PendingIntent.getBroadcast(this, 0, prevIntent, pendingFlags)

        val playPauseIntent = Intent("ACTION_TOGGLE_PLAY").setPackage(packageName)
        val pPlayPause = PendingIntent.getBroadcast(this, 1, playPauseIntent, pendingFlags)

        val nextIntent = Intent("ACTION_NEXT").setPackage(packageName)
        val pNext = PendingIntent.getBroadcast(this, 2, nextIntent, pendingFlags)

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause_vec else R.drawable.ic_play_vec
        val playPauseText = if (isPlaying) "Pause" else "Play"

        val notificationBuilder = NotificationCompat.Builder(this, "MusicChannel")
            .setContentTitle("Menda Player")
            .setContentText(songName)
            .setSmallIcon(R.drawable.ic_play_vec)
            .setColor(androidx.core.content.ContextCompat.getColor(this, R.color.primary))
            .setContentIntent(pOpenApp)
            .setOngoing(isPlaying)
            .addAction(R.drawable.ic_prev_vec, "Prev", pPrev)
            .addAction(playPauseIcon, playPauseText, pPlayPause)
            .addAction(R.drawable.ic_next_vec, "Next", pNext)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setShowActionsInCompactView(0, 1, 2)
            )

        startForeground(1, notificationBuilder.build())

        if (songUriStr != null) {
            serviceScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    loadAlbumArt(songUriStr)
                }
                if (bitmap != null) {
                    notificationBuilder.setLargeIcon(bitmap)
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.notify(1, notificationBuilder.build())
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun loadAlbumArt(uriStr: String): Bitmap? {
        val cachedBitmap = AlbumArtCache.getBitmapFromMemCache(uriStr)
        if (cachedBitmap != null) {
            return cachedBitmap
        }

        var newBitmap: Bitmap? = null
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, Uri.parse(uriStr))
            val art = retriever.embeddedPicture
            retriever.release()

            if (art != null) {
                val options = BitmapFactory.Options()
                options.inSampleSize = 4
                newBitmap = BitmapFactory.decodeByteArray(art, 0, art.size, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (newBitmap != null) {
            AlbumArtCache.addBitmapToMemoryCache(uriStr, newBitmap)
        }

        return newBitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "MusicChannel",
                "Music Playback",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
