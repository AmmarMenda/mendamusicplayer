package com.ammar.mendamusicplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.documentfile.provider.DocumentFile
import android.net.Uri
import android.provider.DocumentsContract
import android.content.ContentUris
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 200L
    }

    private var displayedSongList = mutableListOf<Song>()
    private var playlistDisplaySongs = mutableListOf<Song>()
    private var playlistRealIndices = mutableListOf<Int>()

    private lateinit var musicAdapter: ArrayAdapter<Song>
    private lateinit var playlistAdapter: PlaylistAdapter

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var updateSeekBar: Runnable
    private lateinit var seekBar: BloodTubeSeekBar
    private var searchDebounceRunnable: Runnable? = null
    private var albumArtJob: Job? = null
    private var isPlaylistVisible = false
    private var lastIconPause = false
    private var showingAlbumArtTab = true
    private lateinit var tabAlbumArt: TextView
    private lateinit var tabUpNext: TextView
    private lateinit var albumArtSection: View
    private lateinit var upNextSection: View
    private var binder: MusicService.LocalBinder? = null
    private var isBound = false

    private val playbackStateListener = { syncPlaybackUi() }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as? MusicService.LocalBinder
            binder?.service?.addOnPlaybackStateChangedListener(playbackStateListener)
            syncPlaybackUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
        }
    }

    private val requestStoragePermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            refreshLibrary()
        } else {
            Toast.makeText(
                this,
                "Storage permission is required to load your music library.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                Toast.makeText(this, "Search button tapped!", Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun saveSelectedFolderUri(uriString: String) {
        val prefs = getSharedPreferences("MendaPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("selected_folder_uri", uriString).apply()
    }

    private suspend fun scanFolderForAudio(folderUri: Uri): List<Song> = withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val audioExtensions = setOf("mp3", "m4a", "flac", "wav", "ogg", "aac", "opus", "wma", "mka", "mid", "midi")

        fun isAudio(name: String, mime: String?): Boolean {
            if (mime != null && mime.startsWith("audio/", ignoreCase = true)) return true
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in audioExtensions
        }

        // 1. Direct query using DocumentsContract (fast single-cursor traversal without per-file roundtrips)
        try {
            if (DocumentsContract.isTreeUri(folderUri)) {
                val rootDocId = DocumentsContract.getTreeDocumentId(folderUri)
                val visitedDocs = mutableSetOf<String>()

                fun scanDir(docId: String) {
                    if (!visitedDocs.add(docId)) return
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)
                    val projection = arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    )
                    contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                        val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                        while (cursor.moveToNext()) {
                            val childDocId = (if (idIdx != -1) cursor.getString(idIdx) else null) ?: continue
                            val displayName = (if (nameIdx != -1) cursor.getString(nameIdx) else null) ?: "Unknown"
                            val mimeType = (if (mimeIdx != -1) cursor.getString(mimeIdx) else null) ?: ""

                            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                                scanDir(childDocId)
                            } else if (isAudio(displayName, mimeType)) {
                                val childUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, childDocId)
                                songs.add(Song(displayName, childUri))
                            }
                        }
                    }
                }

                scanDir(rootDocId)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Direct DocumentsContract scanning failed", e)
        }

        // 2. Fallback to DocumentFile if direct scan produced no results
        if (songs.isEmpty()) {
            try {
                val rootDoc = if (DocumentsContract.isTreeUri(folderUri)) {
                    DocumentFile.fromTreeUri(applicationContext, folderUri)
                } else {
                    DocumentFile.fromSingleUri(applicationContext, folderUri)
                }

                fun walkDocumentFile(dir: DocumentFile) {
                    val files = dir.listFiles()
                    for (file in files) {
                        val name = file.name ?: ""
                        val mime = file.type ?: ""
                        if (file.isDirectory) {
                            walkDocumentFile(file)
                        } else if (isAudio(name, mime)) {
                            songs.add(Song(name, file.uri))
                        }
                    }
                }

                rootDoc?.let { walkDocumentFile(it) }
            } catch (e: Exception) {
                Log.e("MainActivity", "DocumentFile traversal failed", e)
            }
        }

        // 3. Fallback to MediaStore if tree URI maps to a storage path and 0 songs were found via SAF
        if (songs.isEmpty() && DocumentsContract.isTreeUri(folderUri)) {
            try {
                val docId = DocumentsContract.getTreeDocumentId(folderUri)
                val relativeFolder = if (docId.contains(':')) docId.substringAfter(':') else docId
                if (relativeFolder.isNotEmpty()) {
                    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    } else {
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    }
                    val projection = arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.TITLE,
                        MediaStore.Audio.Media.DISPLAY_NAME
                    )
                    val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
                    } else {
                        "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DATA} LIKE ?"
                    }
                    val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        arrayOf("$relativeFolder%")
                    } else {
                        arrayOf("%/$relativeFolder/%")
                    }
                    contentResolver.query(collection, projection, selection, selectionArgs, "${MediaStore.Audio.Media.TITLE} ASC")?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                        val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                        val nameCol = cursor.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idCol)
                            val name = if (nameCol != -1) cursor.getString(nameCol) else null
                            val title = cursor.getString(titleCol) ?: name ?: "Unknown"
                            val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                            songs.add(Song(title, contentUri))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "MediaStore relative path fallback failed", e)
            }
        }

        songs.sortBy { it.name.lowercase() }
        return@withContext songs
    }

    private fun loadMusicFromSelectedFolder(folderUri: Uri) {
        findViewById<TextView>(R.id.statusText).text = "Scanning folder for music..."
        lifecycleScope.launch(Dispatchers.IO) {
            val newSongList = scanFolderForAudio(folderUri)
            saveCache(newSongList)

            withContext(Dispatchers.Main) {
                if (isPlaylistVisible) {
                    togglePlaylistView()
                }
                PlayerState.currentSongIndex = -1
                PlayerState.songList.clear()
                PlayerState.songList.addAll(newSongList)
                if (PlayerState.isShuffleEnabled) {
                    PlayerState.shuffledIndices = PlayerState.songList.indices.shuffled().toMutableList()
                } else {
                    PlayerState.shuffledIndices.clear()
                }
                updateUI()
                if (newSongList.isEmpty()) {
                    findViewById<TextView>(R.id.statusText).text = "No audio files found in selected folder"
                    Toast.makeText(
                        this@MainActivity,
                        "No playable audio files found in selected folder",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    findViewById<TextView>(R.id.statusText).text = "Loaded ${newSongList.size} files from folder"
                    Toast.makeText(
                        this@MainActivity,
                        "Loaded ${newSongList.size} songs from folder",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // Register the launcher globally in your Activity/Fragment
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                try {
                    val flags = (result.data?.flags ?: 0) and
                            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    contentResolver.takePersistableUriPermission(
                        uri,
                        if (flags != 0) flags else Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    Log.w("MainActivity", "Could not take persistable permission: ${e.message}")
                }

                saveSelectedFolderUri(uri.toString())
                loadMusicFromSelectedFolder(uri)
            }
        }
    }

    private fun checkStorageAndLoadMusic() {
        val prefs = getSharedPreferences("MendaPrefs", Context.MODE_PRIVATE)
        val savedFolderUri = prefs.getString("selected_folder_uri", null)

        if (savedFolderUri != null) {
            AlertDialog.Builder(this)
                .setTitle("Music Folder")
                .setItems(arrayOf("Rescan current folder", "Choose a new folder")) { _, which ->
                    when (which) {
                        0 -> loadMusicFromSelectedFolder(Uri.parse(savedFolderUri))
                        1 -> pickNewFolder()
                    }
                }
                .show()
        } else {
            pickNewFolder()
        }
    }

    private fun pickNewFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
        }
        folderPickerLauncher.launch(intent)
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

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val selectionArgs: Array<String>? = null
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        try {
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
                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    newSongList.add(Song(title, contentUri))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext newSongList
    }

    private inner class PlaylistAdapter(
        private val songs: MutableList<Song>,
        private val realIndices: MutableList<Int>
    ) : ArrayAdapter<Song>(this@MainActivity, R.layout.playlist_item, songs) {
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

                if (realIndex == PlayerState.currentSongIndex) {
                    binder?.service?.stop()
                    findViewById<TextView>(R.id.nowPlayingText).text = "Not Playing"
                    setPlayIcon(false)
                }

                PlayerState.songList.removeAt(realIndex)

                if (PlayerState.isShuffleEnabled) {
                    PlayerState.shuffledIndices = PlayerState.songList.indices.shuffled().toMutableList()
                    if (PlayerState.currentSongIndex != -1 && PlayerState.currentSongIndex < PlayerState.songList.size) {
                        PlayerState.shuffledIndices.remove(PlayerState.currentSongIndex)
                        PlayerState.shuffledIndices.add(0, PlayerState.currentSongIndex)
                    }
                }

                if (realIndex < PlayerState.currentSongIndex) {
                    PlayerState.currentSongIndex--
                } else if (realIndex == PlayerState.currentSongIndex) {
                    PlayerState.currentSongIndex = -1
                }

                updatePlaylistUI()
                updateUI()
            }
            return view
        }
    }

    private fun selectPlaylistTab(showAlbumArt: Boolean) {
        showingAlbumArtTab = showAlbumArt

        albumArtSection.visibility = if (showAlbumArt) View.VISIBLE else View.GONE
        upNextSection.visibility = if (showAlbumArt) View.GONE else View.VISIBLE

        val activeColor = ContextCompat.getColor(this, R.color.primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)

        tabAlbumArt.setTextColor(if (showAlbumArt) activeColor else inactiveColor)
        tabUpNext.setTextColor(if (showAlbumArt) inactiveColor else activeColor)

        tabAlbumArt.setTypeface(null, if (showAlbumArt) Typeface.BOLD else Typeface.NORMAL)
        tabUpNext.setTypeface(null, if (showAlbumArt) Typeface.NORMAL else Typeface.BOLD)

        if (!showAlbumArt) updatePlaylistUI()
    }

    private fun togglePlaylistView() {
        val viewAllMusic = findViewById<LinearLayout>(R.id.viewAllMusic)
        val viewPlaylist = findViewById<LinearLayout>(R.id.viewPlaylist)
        val btnToggle = findViewById<ImageButton>(R.id.btnTogglePlaylist)

        isPlaylistVisible = !isPlaylistVisible

        if (isPlaylistVisible) {
            viewAllMusic.visibility = View.GONE
            viewPlaylist.visibility = View.VISIBLE
            btnToggle.animate().rotation(90f).setDuration(200).start()
            selectPlaylistTab(true)          // <-- new: default to Album Art
        } else {
            viewAllMusic.visibility = View.VISIBLE
            viewPlaylist.visibility = View.GONE
            btnToggle.animate().rotation(-90f).setDuration(200).start()
        }
    }

    private fun updatePlaylistUI() {
        if (!::playlistAdapter.isInitialized) return
        if (PlayerState.currentSongIndex < 0 || PlayerState.songList.isEmpty()) return

        playlistDisplaySongs.clear()
        playlistRealIndices.clear()

        if (PlayerState.isShuffleEnabled) {
            val currentPos = PlayerState.shuffledIndices.indexOf(PlayerState.currentSongIndex)
            if (currentPos != -1) {
                val upcoming = PlayerState.shuffledIndices.subList(currentPos, PlayerState.shuffledIndices.size)
                for (i in upcoming) {
                    playlistDisplaySongs.add(PlayerState.songList[i])
                    playlistRealIndices.add(i)
                }
            }
        } else {
            for (i in PlayerState.currentSongIndex until PlayerState.songList.size) {
                playlistDisplaySongs.add(PlayerState.songList[i])
                playlistRealIndices.add(i)
            }
        }

        playlistAdapter.notifyDataSetChanged()
    }

    private fun sendCommand(action: String, index: Int = -1) {
        val intent = Intent(this, MusicService::class.java).setAction(action)
        if (index >= 0) {
            intent.putExtra(MusicService.EXTRA_INDEX, index)
            if (index in PlayerState.songList.indices) {
                intent.data = PlayerState.songList[index].uri
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun playTrackFromUi(index: Int) {
        sendCommand(MusicService.ACTION_PLAY_TRACK, index)
        handler.postDelayed({
            syncPlaybackUi()
            if (isPlaylistVisible) updatePlaylistUI()
        }, 250)
    }

    private fun setPlayIcon(isPlaying: Boolean) {
        if (lastIconPause == isPlaying) return
        lastIconPause = isPlaying
        findViewById<ImageButton>(R.id.btnPlayPause).setImageResource(
            if (isPlaying) R.drawable.ic_pause_vec else R.drawable.ic_play_vec
        )
    }

    private fun syncPlaybackUi() {
        val service = binder?.service ?: return
        val playing = service.isPlaying
        setPlayIcon(playing)

        if (::seekBar.isInitialized) {
            val trackDuration = service.duration
            if (trackDuration > 0 && seekBar.max != trackDuration) {
                seekBar.max = trackDuration
            }
            seekBar.progress = service.currentPosition.coerceIn(0, seekBar.max)
            seekBar.setPlaying(playing)
        }

        val idx = PlayerState.currentSongIndex
        if (idx >= 0 && idx < PlayerState.songList.size) {
            val song = PlayerState.songList[idx]
            findViewById<TextView>(R.id.nowPlayingText).text = song.name
            updateAlbumArt(song)
        }

        if (playing) {
            startTicker()
        } else if (::updateSeekBar.isInitialized) {
            handler.removeCallbacks(updateSeekBar)
        }
    }

    private fun startTicker() {
        if (!::updateSeekBar.isInitialized) return
        handler.removeCallbacks(updateSeekBar)
        handler.post(updateSeekBar)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        isBound = bindService(
            Intent(this, MusicService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )

        setContentView(R.layout.activity_main)
        val defaultTopBar = findViewById<View>(R.id.defaultTopBar)
        val searchTopBar = findViewById<View>(R.id.searchTopBar)
        val btnSearchClose = findViewById<ImageButton>(R.id.btnSearchClose)
        val etSearchInput = findViewById<EditText>(R.id.etSearchInput)
        val btnSearch = findViewById<ImageButton>(R.id.btnSearch)

        val bottomPlayerBar = findViewById<View>(R.id.bottomPlayerBar)
        val bottomDivider = findViewById<View>(R.id.bottomDivider)

        btnSearch.setOnClickListener {
            defaultTopBar.visibility = View.GONE
            searchTopBar.visibility = View.VISIBLE
            bottomPlayerBar.visibility = View.GONE
            bottomDivider.visibility = View.GONE

            etSearchInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(etSearchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        btnSearchClose.setOnClickListener {
            searchTopBar.visibility = View.GONE
            defaultTopBar.visibility = View.VISIBLE
            bottomPlayerBar.visibility = View.VISIBLE
            bottomDivider.visibility = View.VISIBLE

            etSearchInput.text.clear()
            updateUI(PlayerState.songList)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(etSearchInput.windowToken, 0)
        }

        etSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchDebounceRunnable?.let { handler.removeCallbacks(it) }
                val query = s?.toString() ?: ""
                val runnable = Runnable {
                    if (query.isEmpty()) {
                        updateUI(PlayerState.songList)
                    } else {
                        val filtered = PlayerState.songList.filter { isFuzzyMatch(query, it.name) }
                        updateUI(filtered)
                    }
                }
                searchDebounceRunnable = runnable
                handler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        setupControls()

        if (PlayerState.isShuffleEnabled) {
            findViewById<ImageButton>(R.id.btnShuffle).setColorFilter(ContextCompat.getColor(this, R.color.primary))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (binder?.service?.isPlaying == true) {
            startTicker()
        }
    }

    override fun onPause() {
        super.onPause()
        if (::updateSeekBar.isInitialized) {
            handler.removeCallbacks(updateSeekBar)
        }
        if (::seekBar.isInitialized) {
            seekBar.setPlaying(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::updateSeekBar.isInitialized) {
            handler.removeCallbacks(updateSeekBar)
        }
        searchDebounceRunnable?.let { handler.removeCallbacks(it) }
        albumArtJob?.cancel()
        binder?.service?.removeOnPlaybackStateChangedListener(playbackStateListener)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        binder = null
    }

    private fun setupControls() {
        val btnShuffle = findViewById<ImageButton>(R.id.btnShuffle)
        val btnPrev = findViewById<ImageButton>(R.id.btnPrev)
        val btnPlayPause = findViewById<ImageButton>(R.id.btnPlayPause)
        val btnNext = findViewById<ImageButton>(R.id.btnNext)
        seekBar = findViewById<BloodTubeSeekBar>(R.id.seekBar)
        val btnTogglePlaylist = findViewById<ImageButton>(R.id.btnTogglePlaylist)
        val btnFolder = findViewById<ImageButton>(R.id.btnFolder)
        val musicList = findViewById<ListView>(R.id.musicList)
        val playlistListView = findViewById<ListView>(R.id.playlistList)

        tabAlbumArt = findViewById(R.id.tabAlbumArt)
        tabUpNext = findViewById(R.id.tabUpNext)
        albumArtSection = findViewById(R.id.albumArtSection)
        upNextSection = findViewById(R.id.playlistList)

        tabAlbumArt.setOnClickListener { selectPlaylistTab(true) }
        tabUpNext.setOnClickListener { selectPlaylistTab(false) }
        musicAdapter = ArrayAdapter(this, R.layout.music_list_item, displayedSongList)
        musicList.adapter = musicAdapter
        musicList.setOnItemClickListener { _, _, position, _ ->
            val realSong = displayedSongList[position]
            val realIndex = PlayerState.songList.indexOf(realSong)
            if (realIndex != -1) {
                playTrackFromUi(realIndex)
            }
        }

        playlistAdapter = PlaylistAdapter(playlistDisplaySongs, playlistRealIndices)
        playlistListView.adapter = playlistAdapter
        playlistListView.setOnItemClickListener { _, _, position, _ ->
            val realIndex = playlistRealIndices[position]
            playTrackFromUi(realIndex)
        }

        btnFolder.setOnClickListener {
            checkStorageAndLoadMusic()
        }

        btnShuffle.setOnClickListener {
            PlayerState.isShuffleEnabled = !PlayerState.isShuffleEnabled
            if (PlayerState.isShuffleEnabled) {
                PlayerState.shuffledIndices = PlayerState.songList.indices.shuffled().toMutableList()
                if (PlayerState.currentSongIndex != -1) {
                    PlayerState.shuffledIndices.remove(PlayerState.currentSongIndex)
                    PlayerState.shuffledIndices.add(0, PlayerState.currentSongIndex)
                }
                btnShuffle.setColorFilter(ContextCompat.getColor(this, R.color.primary))
            } else {
                PlayerState.shuffledIndices.clear()
                btnShuffle.clearColorFilter()
            }
            updatePlaylistUI()
        }

        btnTogglePlaylist.setOnClickListener {
            togglePlaylistView()
        }

        btnPlayPause.setOnClickListener {
            val service = binder?.service
            if (service != null) {
                val wasPlaying = service.isPlaying
                if (wasPlaying) {
                    service.pause()
                    handler.removeCallbacks(updateSeekBar)
                } else {
                    service.play()
                    ensureServiceStarted()
                    startTicker()
                }
            } else {
                sendCommand(MusicService.ACTION_TOGGLE_PLAY)
                handler.postDelayed({ syncPlaybackUi() }, 250)
            }
        }

        btnNext.setOnClickListener { playTrackCommand(MusicService.ACTION_NEXT) }
        btnPrev.setOnClickListener { playTrackCommand(MusicService.ACTION_PREV) }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binder?.service?.seekTo(progress)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        updateSeekBar = object : Runnable {
            override fun run() {
                val service = binder?.service
                if (service?.isPlaying == true) {
                    seekBar.progress = service.currentPosition.coerceIn(0, seekBar.max)
                    handler.postDelayed(this, 250)
                }
            }
        }
    }

    private fun playTrackCommand(action: String) {
        sendCommand(action)
        handler.postDelayed({
            syncPlaybackUi()
            if (isPlaylistVisible) updatePlaylistUI()
        }, 250)
    }

    private fun ensureServiceStarted() {
        sendCommand(MusicService.ACTION_REFRESH)
    }

    private fun refreshLibrary() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = loadSongsFromMediaStore()
            saveCache(list)
            withContext(Dispatchers.Main) {
                PlayerState.songList.clear()
                PlayerState.songList.addAll(list)
                updateUI()
                findViewById<TextView>(R.id.statusText).text = "Found ${PlayerState.songList.size} files"
            }
        }
    }

    private fun saveCache(list: List<Song>) {
        val prefs = getSharedPreferences("MendaPrefs", Context.MODE_PRIVATE)
        val arr = JSONArray()
        for (song in list) {
            val obj = JSONObject()
            obj.put("name", song.name)
            obj.put("uri", song.uri.toString())
            arr.put(obj)
        }
        prefs.edit().putString("cached_songs", arr.toString()).apply()
    }

    private fun loadCache(): List<Song> {
        val prefs = getSharedPreferences("MendaPrefs", Context.MODE_PRIVATE)
        val cached = prefs.getString("cached_songs", "") ?: ""
        if (cached.isEmpty()) return emptyList()

        val list = mutableListOf<Song>()
        try {
            val arr = JSONArray(cached)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Song(obj.getString("name"), Uri.parse(obj.getString("uri"))))
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to parse song cache", e)
        }
        return list
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
        val imgLarge = findViewById<ImageView>(R.id.imgAlbumArtLarge)
        findViewById<TextView>(R.id.txtAlbumArtSongName)?.text = song.name

        val cachedBitmap = AlbumArtCache.getBitmapFromMemCache(imageKey)
        if (cachedBitmap != null) {
            imgLarge?.setImageBitmap(cachedBitmap)
            return
        }

        albumArtJob?.cancel()
        albumArtJob = lifecycleScope.launch(Dispatchers.IO) {
            var newBitmap: Bitmap? = null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(applicationContext, song.uri)
                val art = retriever.embeddedPicture
                if (art != null) {
                    val options = BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    BitmapFactory.decodeByteArray(art, 0, art.size, options)
                    options.inSampleSize = calculateInSampleSize(options, 400, 400)
                    options.inJustDecodeBounds = false
                    options.inPreferredConfig = Bitmap.Config.RGB_565
                    newBitmap = BitmapFactory.decodeByteArray(art, 0, art.size, options)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                retriever.release()
            }
            if (newBitmap != null) {
                AlbumArtCache.addBitmapToMemoryCache(imageKey, newBitmap)
            }
            withContext(Dispatchers.Main) {
                val currentIdx = PlayerState.currentSongIndex
                if (currentIdx < 0 || currentIdx >= PlayerState.songList.size) return@withContext
                if (PlayerState.songList[currentIdx].uri.toString() != imageKey) return@withContext

                if (newBitmap != null) {
                    imgLarge?.setImageBitmap(newBitmap)
                } else {
                    imgLarge?.setImageResource(R.drawable.ic_album_placeholder_vec)
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

    private fun updateUI(listToDisplay: List<Song> = PlayerState.songList) {
        if (!::musicAdapter.isInitialized) return

        displayedSongList.clear()
        displayedSongList.addAll(listToDisplay)
        musicAdapter.notifyDataSetChanged()

        if (listToDisplay === PlayerState.songList || listToDisplay.size == PlayerState.songList.size) {
            findViewById<TextView>(R.id.statusText).text = "Found ${PlayerState.songList.size} files"
        } else {
            findViewById<TextView>(R.id.statusText).text = "Showing ${displayedSongList.size} of ${PlayerState.songList.size} files"
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
        if (PlayerState.songList.isNotEmpty() && binder?.service?.isPlaying == true) {
            updateUI()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = getSharedPreferences("MendaPrefs", Context.MODE_PRIVATE)
            val hasCache = prefs.contains("cached_songs")
            val cached = loadCache()

            withContext(Dispatchers.Main) {
                if (hasCache) {
                    PlayerState.songList.clear()
                    PlayerState.songList.addAll(cached)
                    updateUI()
                    findViewById<TextView>(R.id.statusText).text =
                        if (cached.isEmpty()) "No songs in cache — tap the folder icon to rescan"
                        else "Loaded ${PlayerState.songList.size} from cache"
                    return@withContext
                }

                // No cache yet — first run, so we must scan once.
                val savedFolderUri = prefs.getString("selected_folder_uri", null)
                if (savedFolderUri != null) {
                    val uri = Uri.parse(savedFolderUri)
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Could not take persistable permission on startup: ${e.message}")
                    }
                    loadMusicFromSelectedFolder(uri)
                } else {
                    refreshLibrary()
                }
            }
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
