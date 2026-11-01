package com.jnt.player

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class Song(
    val id: Long, val title: String, val artist: String, val album: String,
    val albumId: Long, val duration: Long, val added: Long,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val artUri: Uri get() = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
    fun item(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString()).setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(title).setArtist(artist)
                .setAlbumTitle(album).setArtworkUri(artUri).build()
        ).build()
}

/** Lê todas as músicas do aparelho via MediaStore, sem duplicatas. */
fun loadSongs(ctx: Context): List<Song> {
    val out = ArrayList<Song>()
    val seen = HashSet<String>()
    val A = MediaStore.Audio.Media
    val proj = arrayOf(A._ID, A.TITLE, A.ARTIST, A.ALBUM, A.ALBUM_ID, A.DURATION, A.DATE_ADDED)
    try {
        ctx.contentResolver.query(
            A.EXTERNAL_CONTENT_URI, proj, "${A.IS_MUSIC} != 0 AND ${A.DURATION} > 10000",
            null, "${A.TITLE} COLLATE NOCASE"
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1)?.trim().orEmpty().ifBlank { "Sem título" }
                val artist = c.getString(2)?.takeIf { it != "<unknown>" }?.trim() ?: "Artista desconhecido"
                val album = c.getString(3)?.takeIf { it != "<unknown>" }?.trim() ?: "Álbum desconhecido"
                val dur = c.getLong(5)
                val key = "${title.lowercase()}|${artist.lowercase()}|${dur / 1000}"
                if (seen.add(key)) out += Song(c.getLong(0), title, artist, album, c.getLong(4), dur, c.getLong(6))
            }
        }
    } catch (_: Exception) { }
    return out
}

@Entity data class Fav(@PrimaryKey val songId: Long)
@Entity data class Playlist(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)
@Entity data class Entry(@PrimaryKey(autoGenerate = true) val id: Long = 0, val playlistId: Long, val songId: Long, val pos: Int)
@Entity data class PlayStat(@PrimaryKey val songId: Long, val count: Int, val last: Long)

@Dao interface JntDao {
    @Query("SELECT songId FROM Fav") fun favs(): Flow<List<Long>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addFav(f: Fav)
    @Query("DELETE FROM Fav WHERE songId=:id") suspend fun delFav(id: Long)

    @Query("SELECT * FROM Playlist ORDER BY id") fun playlists(): Flow<List<Playlist>>
    @Insert suspend fun addPlaylist(p: Playlist): Long
    @Query("UPDATE Playlist SET name=:n WHERE id=:id") suspend fun rename(id: Long, n: String)
    @Query("DELETE FROM Playlist WHERE id=:id") suspend fun delPlaylist(id: Long)
    @Query("DELETE FROM Entry WHERE playlistId=:id") suspend fun delEntries(id: Long)

    @Query("SELECT * FROM Entry ORDER BY pos") fun entries(): Flow<List<Entry>>
    @Query("SELECT COALESCE(MAX(pos), -1) FROM Entry WHERE playlistId=:id") suspend fun maxPos(id: Long): Int
    @Query("SELECT COUNT(*) FROM Entry WHERE playlistId=:id AND songId=:sid") suspend fun has(id: Long, sid: Long): Int
    @Insert suspend fun addEntry(e: Entry)
    @Query("DELETE FROM Entry WHERE id=:id") suspend fun delEntry(id: Long)
    @Update suspend fun upd(e: Entry)

    @Query("SELECT * FROM PlayStat") fun stats(): Flow<List<PlayStat>>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insStat(p: PlayStat)
    @Query("UPDATE PlayStat SET count=count+1, last=:t WHERE songId=:id") suspend fun bump(id: Long, t: Long)
}

@Database(entities = [Fav::class, Playlist::class, Entry::class, PlayStat::class], version = 1, exportSchema = false)
abstract class JntDb : RoomDatabase() { abstract fun dao(): JntDao }
