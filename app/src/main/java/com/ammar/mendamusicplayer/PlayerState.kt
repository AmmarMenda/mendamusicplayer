package com.ammar.mendamusicplayer

import android.net.Uri

data class Song(val name: String, val uri: Uri) {
    override fun toString(): String = name
}

object PlayerState {
    val songList = mutableListOf<Song>()
    var currentSongIndex = -1
    var isShuffleEnabled = false
    var shuffledIndices = mutableListOf<Int>()
}
