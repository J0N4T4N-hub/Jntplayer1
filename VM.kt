package com.jnt.player

import android.app.Application
import android.content.ComponentName
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class SortBy(val label: String) {
    Name("Nome"), Artist("Artista"), Album("Álbum"), Recent("Mais recentes"), Played("Mais tocadas")
}

class VM(private val app: Application) : AndroidViewModel(app) {
    private val dao = Room.databaseBuilder(app, JntDb::class.java, "jnt.db").build().dao()

    val songs = MutableStateFlow<List<Song>>(emptyList())
    val favSet = dao.favs().map { it.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val playlists = dao.playlists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val entries = dao.entries().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val stats = dao.stats().map { l -> l.associateBy { it.songId } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    var controller by mutableStateOf<MediaController?>(null)
        private set
    var currentId by mutableStateOf<Long?>(null)
    var playing by mutableStateOf(false)
    var shuffle by mutableStateOf(false)
    var repeat by mutableIntStateOf(Player.REPEAT_MODE_OFF)
    var position by mutableLongStateOf(0L)

    // estado da biblioteca (sobrevive à navegação)
    var libChip by mutableIntStateOf(0)
    var libQuery by mutableStateOf("")
    var libSort by mutableStateOf(SortBy.Name)

    private var job: Job? = null
    private val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refresh() }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(m: MediaItem?, reason: Int) {
            currentId = m?.mediaId?.toLongOrNull()
            currentId?.let { id ->
                viewModelScope.launch { dao.insStat(PlayStat(id, 0, 0)); dao.bump(id, System.currentTimeMillis()) }
            }
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        override fun onShuffleModeEnabledChanged(enabled: Boolean) { shuffle = enabled }
        override fun onRepeatModeChanged(mode: Int) { repeat = mode }
    }

    init {
        app.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, obs)
        val f = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        f.addListener({
            runCatching { f.get() }.getOrNull()?.let { c ->
                c.addListener(listener); controller = c
                currentId = c.currentMediaItem?.mediaId?.toLongOrNull()
                playing = c.isPlaying; shuffle = c.shuffleModeEnabled; repeat = c.repeatMode
            }
        }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch {
            while (true) {
                val p = controller?.currentPosition ?: 0L
                if (p != position) position = p
                delay(500)
            }
        }
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch { delay(300); songs.value = withContext(Dispatchers.IO) { loadSongs(app) } }
    }

    fun play(list: List<Song>, index: Int, shuffled: Boolean = false) {
        val c = controller ?: return
        if (list.isEmpty()) return
        c.shuffleModeEnabled = shuffled
        c.setMediaItems(list.map { it.item() }, index.coerceIn(0, list.lastIndex), 0L)
        c.prepare(); c.play()
    }
    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { controller?.seekToNext() }
    fun prev() { controller?.seekToPrevious() }
    fun seek(ms: Long) { controller?.seekTo(ms) }
    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun toggleFav(id: Long) = viewModelScope.launch { if (id in favSet.value) dao.delFav(id) else dao.addFav(Fav(id)) }

    fun createPlaylist(name: String, songId: Long? = null) = viewModelScope.launch {
        val id = dao.addPlaylist(Playlist(name = name.trim()))
        if (songId != null) addNow(id, songId)
    }
    private suspend fun addNow(pid: Long, sid: Long) {
        if (dao.has(pid, sid) == 0) dao.addEntry(Entry(playlistId = pid, songId = sid, pos = dao.maxPos(pid) + 1))
    }
    fun addToPlaylist(pid: Long, sid: Long) = viewModelScope.launch { addNow(pid, sid) }
    fun rename(pid: Long, name: String) = viewModelScope.launch { dao.rename(pid, name.trim()) }
    fun deletePlaylist(pid: Long) = viewModelScope.launch { dao.delEntries(pid); dao.delPlaylist(pid) }
    fun removeEntry(e: Entry) = viewModelScope.launch { dao.delEntry(e.id) }
    fun swap(a: Entry, b: Entry) = viewModelScope.launch { dao.upd(a.copy(pos = b.pos)); dao.upd(b.copy(pos = a.pos)) }

    override fun onCleared() {
        app.contentResolver.unregisterContentObserver(obs)
        controller?.release()
    }
}
