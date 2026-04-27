package com.ammar.mendamusicplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.app.Activity
import android.content.Intent
import androidx.documentfile.provider.DocumentFile
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.Button
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.view.View
import android.widget.LinearLayout
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import kotlin.random.Random
import android.widget.ImageButton
import android.graphics.Color
import android.content.ContentUris
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

class MainActivity : AppCompatActivity() {

    companion object {
        var currentSongIndex = -1
        var mediaPlayer: MediaPlayer? = null
        var isShuffleEnabled = false
        var shuffledIndices = mutableListOf<Int>()
        val songList = mutableListOf<Song>()
    }

    private var displayedSongList = mutableListOf<Song>()

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var updateSeekBar: Runnable
    private var isPlaylistVisible = false
    private var currentAlbumArt: Bitmap? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isNoisyReceiverRegistered = false
    private lateinit var mediaSession: MediaSessionCompat
    private val requestStoragePermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            // The user clicked "Allow"! Load the music.
            refreshLibrary()
        } else {
            // The user clicked "Deny". Show a friendly message.
            android.widget.Toast.makeText(
                this,
                "Storage permission is required to load your music library.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

override fun onCreateOptionsMenu(menu: Menu?): Boolean {
    // This takes your XML file and draws it onto the top bar
    menuInflater.inflate(R.menu.main_menu, menu)
    return true
}

override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
        R.id.action_search -> {
            // Using the fully qualified name "android.widget.Toast" fixes the ambiguity
            android.widget.Toast.makeText(this, "Search button tapped!", android.widget.Toast.LENGTH_SHORT).show()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}

    private fun checkStorageAndLoadMusic() {
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13 and above uses this specific audio permission
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            // Android 12 and below uses the general storage permission
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // We already have permission! Just load the library immediately.
            refreshLibrary()
        } else {
            // We don't have permission yet, so launch the Android permission popup.
            requestStoragePermissionLauncher.launch(permission)
        }
    }

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pauseMusic() // System says it's noisy, pause the music!
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pauseMusic() // Another app took over, pause the music!
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                mediaPlayer?.setVolume(0.2f, 0.2f) // Lower volume temporarily (e.g., notification chime)
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1.0f, 1.0f) // Restore volume
            }
        }
    }

    private fun updatePlaybackState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .setState(
                state,
                mediaPlayer?.currentPosition?.toLong() ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                1.0f
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }


    data class Song(val name: String, val uri: android.net.Uri) {
        // ArrayAdapter uses toString() to decide what text to show in the list!
        override fun toString(): String = name
    }

    private suspend fun loadSongsFromMediaStore(): List<Song> = withContext(Dispatchers.IO) {
        val newSongList = mutableListOf<Song>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE
        )

        // --- FOLDER RESTRICTION LOGIC ---
        val selection: String
        val selectionArgs: Array<String>

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Modern Android (API 29+): Use RELATIVE_PATH
            // This looks for anything inside the standard "Music" directory
            selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            selectionArgs = arrayOf("Music/%")
        } else {
            // Legacy Android (API 28 and below): Use DATA (Absolute Path)
            // This looks for "/Music/" anywhere in the absolute file path
            selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DATA} LIKE ?"
            selectionArgs = arrayOf("%/Music/%")
        }

        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        applicationContext.contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val title = cursor.getString(titleColumn) ?: "Unknown Title"

                // Construct the exact Uri for playback and album art
                val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                newSongList.add(Song(title, contentUri))
            }
        }

        return@withContext newSongList
    }

    private inner class PlaylistAdapter(val songs: List<Song>, val realIndices: List<Int>) :
        ArrayAdapter<Song>(this@MainActivity, R.layout.playlist_item, songs) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.playlist_item, parent, false)

            val song = songs[position]
            val songNameText = view.findViewById<TextView>(R.id.songNameText)
            val btnRemove = view.findViewById<Button>(R.id.btnRemove)

            if (position == 0) {
                songNameText.text = "▶  ${song.name}"
                btnRemove.visibility = View.INVISIBLE
            } else {
                songNameText.text = song.name
                btnRemove.visibility = View.VISIBLE
            }

            btnRemove.setOnClickListener {
                val realIndex = realIndices[position]

                // If removing the currently playing song, stop it
                if (realIndex == currentSongIndex) {
                    mediaPlayer?.stop()
                    mediaPlayer?.release()
                    mediaPlayer = null
                    findViewById<TextView>(R.id.nowPlayingText).text = "Not Playing"
                }

                songList.removeAt(realIndex) // Removes from master list

                // Memory safe fix: If we delete a song, refresh the shuffle map to avoid crashes
                if (isShuffleEnabled) {
                    shuffledIndices = songList.indices.shuffled().toMutableList()
                    if (currentSongIndex != -1 && currentSongIndex < songList.size) {
                        shuffledIndices.remove(currentSongIndex)
                        shuffledIndices.add(0, currentSongIndex)
                    }
                }

                if (realIndex < currentSongIndex) {
                    currentSongIndex--
                } else if (realIndex == currentSongIndex) {
                    currentSongIndex = -1
                }

                updatePlaylistUI()
                updateUI() // Updates main library
            }
            return view
        }
    }

    private fun togglePlaylistView() {
        val viewAllMusic = findViewById<LinearLayout>(R.id.viewAllMusic)
        val viewPlaylist = findViewById<LinearLayout>(R.id.viewPlaylist)
        val btnToggle = findViewById<ImageButton>(R.id.btnTogglePlaylist)

        isPlaylistVisible = !isPlaylistVisible

        if (isPlaylistVisible) {
            // Show Playlist, Hide Main Library
            viewAllMusic.visibility = View.GONE
            viewPlaylist.visibility = View.VISIBLE
            btnToggle.animate().rotation(90f).setDuration(200).start() // Arrow points down
            updatePlaylistUI()
        } else {
            // Show Main Library, Hide Playlist
            viewAllMusic.visibility = View.VISIBLE
            viewPlaylist.visibility = View.GONE
            btnToggle.animate().rotation(-90f).setDuration(200).start() // Arrow points up
        }
    }

    private fun updatePlaylistUI() {
        if (currentSongIndex < 0 || songList.isEmpty()) return

        val displaySongs = mutableListOf<Song>()
        val realIndices = mutableListOf<Int>()

        if (isShuffleEnabled) {
            // Show the remaining randomized queue
            val currentPos = shuffledIndices.indexOf(currentSongIndex)
            if (currentPos != -1) {
                val upcoming = shuffledIndices.subList(currentPos, shuffledIndices.size)
                for (i in upcoming) {
                    displaySongs.add(songList[i])
                    realIndices.add(i)
                }
            }
        } else {
            // Show standard alphabetical remainder
            for (i in currentSongIndex until songList.size) {
                displaySongs.add(songList[i])
                realIndices.add(i)
            }
        }

        val playlistListView = findViewById<ListView>(R.id.playlistList)
        val adapter = PlaylistAdapter(displaySongs, realIndices)
        playlistListView.adapter = adapter

        playlistListView.setOnItemClickListener { _, _, position, _ ->
            val realIndex = realIndices[position]
            playAudio(realIndex)
        }
    }

    private val musicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ACTION_TOGGLE_PLAY" -> findViewById<ImageButton>(R.id.btnPlayPause).performClick()
                "ACTION_NEXT" -> playNext()
                "ACTION_PREV" -> playPrevious() // <-- Add this line
            }
        }
    }

    private fun updateNotification(songName: String, isPlaying: Boolean, songUri: String? = null) {
        val serviceIntent = Intent(this, MusicService::class.java).apply {
            putExtra("SONG_NAME", songName)
            putExtra("IS_PLAYING", isPlaying)
            songUri?.let { putExtra("SONG_URI", it) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()

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

    private fun playMusic() {
        // ONLY play if the Android OS grants us Audio Focus
        if (requestAudioFocus()) {
            mediaPlayer?.let { player ->
                player.start()

                // Register receiver so we know if headphones unplug
                if (!isNoisyReceiverRegistered) {
                    registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                    isNoisyReceiverRegistered = true
                }

                // Your existing UI updates
                val currentSongName = if (currentSongIndex >= 0) songList[currentSongIndex].name else "Unknown"
                updatePlaybackState(android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING)
                findViewById<ImageButton>(R.id.btnPlayPause).setImageResource(R.drawable.ic_pause_vec)

                val currentUri = if (currentSongIndex >= 0) songList[currentSongIndex].uri.toString() else null
                updateNotification(currentSongName, true, currentUri)
                handler.post(updateSeekBar)
            }
        }
    }

    private fun pauseMusic() {
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()

                // Unregister receiver to save battery
                if (isNoisyReceiverRegistered) {
                    unregisterReceiver(becomingNoisyReceiver)
                    isNoisyReceiverRegistered = false
                }

                // Your existing UI updates
                val currentSongName = if (currentSongIndex >= 0) songList[currentSongIndex].name else "Unknown"
                updatePlaybackState(android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED)
                findViewById<ImageButton>(R.id.btnPlayPause).setImageResource(R.drawable.ic_play_vec)
                updateNotification(currentSongName, false)
                handler.removeCallbacks(updateSeekBar)

                // Give up audio focus since we paused
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(audioFocusChangeListener)
                }
            }
        }
    }

    private fun playAudio(index: Int) {
        if (songList.isEmpty() || index < 0 || index >= songList.size) return

        currentSongIndex = index
        val song = songList[index]

        updateAlbumArt(song)

        if (isPlaylistVisible) {
            updatePlaylistUI()
        }

        updateNotification(song.name, true, song.uri.toString())
        mediaPlayer?.release()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(applicationContext, song.uri)
                prepareAsync()
                setOnPreparedListener {
                    findViewById<SeekBar>(R.id.seekBar).max = it.duration
                    start()
                    mediaSession.isActive = true
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    val playPauseBtn = findViewById<ImageButton>(R.id.btnPlayPause)
                    playPauseBtn.setImageResource(R.drawable.ic_pause_vec)
                    findViewById<TextView>(R.id.nowPlayingText).text = song.name
                    handler.postDelayed(updateSeekBar, 1000)
                }
                setOnErrorListener { mp, _, _ ->
                    mp.reset()
                    false
                }
                setOnCompletionListener { playNext() }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isNoisyReceiverRegistered) {
            unregisterReceiver(becomingNoisyReceiver)
            isNoisyReceiverRegistered = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        handler.removeCallbacks(updateSeekBar)
        unregisterReceiver(musicReceiver)

        // Only stop everything if the activity is finishing and the player isn't active
        if (isFinishing && mediaPlayer?.isPlaying != true) {
            mediaPlayer?.release()
            mediaPlayer = null
            stopService(Intent(this, MusicService::class.java))
            mediaSession.isActive = false
            mediaSession.release()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val filter = IntentFilter().apply {
            addAction("ACTION_TOGGLE_PLAY")
            addAction("ACTION_NEXT")
            addAction("ACTION_PREV")
        }
        // Android 13+ requires explicit export flags for receivers
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            registerReceiver(musicReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(musicReceiver, filter)
        }

        mediaSession = MediaSessionCompat(this, "MendaPlayerSession")
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() {
                findViewById<ImageButton>(R.id.btnPlayPause).performClick()
            }

            override fun onPause() {
                findViewById<ImageButton>(R.id.btnPlayPause).performClick()
            }

            override fun onSkipToNext() {
                playNext()
            }

            override fun onSkipToPrevious() {
                playPrevious()
            }
        })

        setContentView(R.layout.activity_main)
        val defaultTopBar = findViewById<View>(R.id.defaultTopBar)
        val searchTopBar = findViewById<View>(R.id.searchTopBar)
        val btnSearchClose = findViewById<ImageButton>(R.id.btnSearchClose)
        val etSearchInput = findViewById<EditText>(R.id.etSearchInput)
        val btnSearch = findViewById<ImageButton>(R.id.btnSearch)

        val bottomPlayerBar = findViewById<View>(R.id.bottomPlayerBar)
        val bottomDivider = findViewById<View>(R.id.bottomDivider)

        // Open Search
        btnSearch.setOnClickListener {
            defaultTopBar.visibility = View.GONE
            searchTopBar.visibility = View.VISIBLE
            bottomPlayerBar.visibility = View.GONE
            bottomDivider.visibility = View.GONE
            
            // Focus the text input and show the keyboard
            etSearchInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etSearchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        // Close Search
        btnSearchClose.setOnClickListener {
            searchTopBar.visibility = View.GONE
            defaultTopBar.visibility = View.VISIBLE
            bottomPlayerBar.visibility = View.VISIBLE
            bottomDivider.visibility = View.VISIBLE
            
            // Clear the text and hide the keyboard
            etSearchInput.text.clear()
            updateUI(songList)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etSearchInput.windowToken, 0)
        }

        etSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                if (query.isEmpty()) {
                    updateUI(songList)
                } else {
                    val filtered = songList.filter { isFuzzyMatch(query, it.name) }
                    updateUI(filtered)
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        setupControls()

        // Sync UI with existing playback state if already playing
        mediaPlayer?.let { player ->
            if (player.isPlaying) {
                val currentSong = if (currentSongIndex >= 0 && currentSongIndex < songList.size) songList[currentSongIndex] else null
                currentSong?.let {
                    findViewById<TextView>(R.id.nowPlayingText).text = it.name
                    updateAlbumArt(it)
                }
                findViewById<ImageButton>(R.id.btnPlayPause).setImageResource(R.drawable.ic_pause_vec)
                findViewById<SeekBar>(R.id.seekBar).max = player.duration
                handler.post(updateSeekBar)
            }
        }

        if (isShuffleEnabled) {
            findViewById<ImageButton>(R.id.btnShuffle).setColorFilter(ContextCompat.getColor(this, R.color.primary))
        }

        checkPermissions()
    }


    private fun setupControls() {
        val btnShuffle = findViewById<ImageButton>(R.id.btnShuffle)
        val btnPrev = findViewById<ImageButton>(R.id.btnPrev)
        val btnPlayPause = findViewById<ImageButton>(R.id.btnPlayPause)
        val btnNext = findViewById<ImageButton>(R.id.btnNext)
        val seekBar = findViewById<SeekBar>(R.id.seekBar)
        val btnTogglePlaylist = findViewById<ImageButton>(R.id.btnTogglePlaylist)
        val btnFolder = findViewById<ImageButton>(R.id.btnFolder)

        btnFolder.setOnClickListener {
            openFolderPicker()
        }

        btnShuffle.setOnClickListener {
            isShuffleEnabled = !isShuffleEnabled
            if (isShuffleEnabled) {
                // Create a randomized map of the ENTIRE list
                shuffledIndices = songList.indices.shuffled().toMutableList()
                // Ensure the current song stays at the top of the queue so it doesn't skip
                shuffledIndices.remove(currentSongIndex)
                shuffledIndices.add(0, currentSongIndex)

                btnShuffle.setColorFilter(ContextCompat.getColor(this, R.color.primary)) // Highlight icon
            } else {
                shuffledIndices.clear()
                btnShuffle.clearColorFilter() // Remove highlight
            }
            updatePlaylistUI()
        }

        btnTogglePlaylist.setOnClickListener {
            togglePlaylistView()
        }

        btnPlayPause.setOnClickListener {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    pauseMusic()
                } else {
                    playMusic()
                }
            }
        }


        // 2. Next / Prev Logic
        btnNext.setOnClickListener { playNext() }
        btnPrev.setOnClickListener { playPrevious() }

        // 3. Let user drag the seekbar to skip ahead
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.let {
                        if (it.isPlaying || it.isLooping || it.currentPosition > 0) { // basic state check
                            try {
                                it.seekTo(progress)
                            } catch (e: IllegalStateException) {
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        // 4. The loop that moves the seekbar forward while playing
        updateSeekBar = object : Runnable {
            override fun run() {
                mediaPlayer?.let {
                    if (it.isPlaying) {
                        seekBar.progress = it.currentPosition
                        handler.postDelayed(this, 1000)
                    }
                }
            }
        }
    }

    private fun openFolderPicker() {
        checkStorageAndLoadMusic()
    }    private fun refreshLibrary() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = loadSongsFromMediaStore()
            withContext(Dispatchers.Main) {
                songList.clear()
                songList.addAll(list)
                saveCache(list)
                updateUI()
                findViewById<TextView>(R.id.statusText).text = "Found ${songList.size} files"
            }
        }
    }

    private fun saveCache(list: List<Song>) {
        val prefs = getSharedPreferences("MendaPrefs", Context.MODE_PRIVATE)
        val sb = StringBuilder()
        for (song in list) {
            sb.append(song.name).append(":::").append(song.uri.toString()).append("|||")
        }
        prefs.edit().putString("cached_songs", sb.toString()).apply()
    }

    private fun loadCache(): List<Song> {
        val prefs = getSharedPreferences("MendaPrefs", Context.MODE_PRIVATE)
        val cached = prefs.getString("cached_songs", "") ?: ""
        if (cached.isEmpty()) return emptyList()

        val list = mutableListOf<Song>()
        val items = cached.split("|||")
        for (item in items) {
            if (item.contains(":::")) {
                val parts = item.split(":::")
                if (parts.size >= 2) {
                    list.add(Song(parts[0], android.net.Uri.parse(parts[1])))
                }
            }
        }
        return list
    }

    private fun playNext() {
        if (songList.isEmpty()) return

        var nextIndex = currentSongIndex + 1

        if (isShuffleEnabled) {
            val positionInShuffle = shuffledIndices.indexOf(currentSongIndex)
            if (positionInShuffle < shuffledIndices.size - 1) {
                nextIndex = shuffledIndices[positionInShuffle + 1]
            } else {
                // Re-shuffle when the queue finishes!
                shuffledIndices = songList.indices.shuffled().toMutableList()
                nextIndex = shuffledIndices[0]
            }
        } else if (nextIndex >= songList.size) {
            nextIndex = 0
        }

        playAudio(nextIndex)
    }

    private fun playPrevious() {
        if (songList.isEmpty()) return

        var prevIndex = currentSongIndex - 1

        if (isShuffleEnabled) {
            val positionInShuffle = shuffledIndices.indexOf(currentSongIndex)
            if (positionInShuffle > 0) {
                prevIndex = shuffledIndices[positionInShuffle - 1]
            } else {
                // Wrap to end of shuffle queue
                prevIndex = shuffledIndices.last()
            }
        } else if (prevIndex < 0) {
            prevIndex = songList.size - 1
        }

        playAudio(prevIndex)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun updateAlbumArt(song: Song) {
        val imageKey = song.uri.toString()
        val imgView = findViewById<ImageView>(R.id.imgAlbumArt)
        val cachedBitmap = AlbumArtCache.getBitmapFromMemCache(imageKey)
        if (cachedBitmap != null) {
            imgView.setImageBitmap(cachedBitmap)
            currentAlbumArt = cachedBitmap
            if (mediaPlayer?.isPlaying == true) {
                val currentSongName = if (currentSongIndex >= 0) songList[currentSongIndex].name else "Unknown"
                updateNotification(currentSongName, true, imageKey)
            }
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var newBitmap: Bitmap? = null
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(applicationContext, song.uri)
                val art = retriever.embeddedPicture
                if (art != null) {
                    val options = BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    BitmapFactory.decodeByteArray(art, 0, art.size, options)
                    options.inSampleSize = calculateInSampleSize(options, 400, 400)
                    options.inJustDecodeBounds = false
                    newBitmap = BitmapFactory.decodeByteArray(art, 0, art.size, options)
                }
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            if (newBitmap != null) {
                AlbumArtCache.addBitmapToMemoryCache(imageKey, newBitmap)
            }
            withContext(Dispatchers.Main) {
                if (newBitmap != null) {
                    imgView.setImageBitmap(newBitmap)
                } else {
                    imgView.setImageResource(R.drawable.ic_album_placeholder_vec)
                }
                currentAlbumArt = newBitmap
                if (mediaPlayer?.isPlaying == true) {
                    val currentSongName = if (currentSongIndex >= 0) songList[currentSongIndex].name else "Unknown"
                    val currentSongUri = if (currentSongIndex >= 0) songList[currentSongIndex].uri.toString() else null
                    updateNotification(currentSongName, true, currentSongUri)
                }
            }
        }
    }

    private fun isFuzzyMatch(query: String, target: String): Boolean {
        var queryIndex = 0
        var targetIndex = 0
        val lowerQuery = query.lowercase()
        val lowerTarget = target.lowercase()
        while (queryIndex < lowerQuery.length && targetIndex < lowerTarget.length) {
            if (lowerQuery[queryIndex] == lowerTarget[targetIndex]) {
                queryIndex++
            }
            targetIndex++
        }
        return queryIndex == lowerQuery.length
    }

    private fun updateUI(listToDisplay: List<Song> = songList) {
        displayedSongList.clear()
        displayedSongList.addAll(listToDisplay)

        val listView = findViewById<ListView>(R.id.musicList)
        val adapter = ArrayAdapter(this, R.layout.music_list_item, displayedSongList)
        listView.adapter = adapter
        
        if (listToDisplay === songList || listToDisplay.size == songList.size) {
            findViewById<TextView>(R.id.statusText).text = "Found ${songList.size} files"
        } else {
            findViewById<TextView>(R.id.statusText).text = "Showing ${displayedSongList.size} of ${songList.size} files"
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val realSong = displayedSongList[position]
            val realIndex = songList.indexOf(realSong)
            if(realIndex != -1) {
                playAudio(realIndex)
            }
        }
    }

    private fun checkPermissions() {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), 101)
        } else {
            loadMusic()
        }
    }

    private fun loadMusic() {
        if (songList.isNotEmpty() && mediaPlayer?.isPlaying == true) {
            updateUI()
            return
        }

        val cached = loadCache()
        if (cached.isNotEmpty()) {
            songList.clear()
            songList.addAll(cached)
            updateUI()
            findViewById<TextView>(R.id.statusText).text = "Loaded ${songList.size} from cache"
        } else {
            refreshLibrary()
        }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, p, res)
        if (rc == 101 && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            loadMusic()
        } else {
            Toast.makeText(this, "Permission denied!", Toast.LENGTH_SHORT).show()
        }
    }
}
