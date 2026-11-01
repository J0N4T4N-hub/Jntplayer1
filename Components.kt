package com.jnt.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Ink = Color(0xFF080808)
val Slate = Color(0xFF151515)
val Slate2 = Color(0xFF232323)
val Ash = Color(0xFF9B9B9B)

@Composable
fun JNTTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(
        primary = Color.White, onPrimary = Ink, background = Ink, onBackground = Color.White,
        surface = Slate, onSurface = Color.White, surfaceVariant = Slate2, onSurfaceVariant = Ash,
        secondaryContainer = Slate2, onSecondaryContainer = Color.White,
    ),
    content = content
)

fun fmt(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Feedback visual ao toque: leve redução de escala. */
fun Modifier.bounce(onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.94f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "bounce")
    this.scale(sc).clickable(interactionSource = src, indication = null, onClick = onClick)
}

@Composable
fun Ib(icon: ImageVector, desc: String, tint: Color = Color.White, size: Dp = 48.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).bounce(onClick), Alignment.Center) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

private object ArtCache : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount
}

private fun loadArt(ctx: Context, s: Song): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(s.uri, Size(512, 512), null)
    else ctx.contentResolver.openInputStream(s.artUri)?.use { BitmapFactory.decodeStream(it) }
}.getOrNull()

/** Capa do álbum (embutida no arquivo ou do MediaStore); ícone vetorial quando não existe. */
@Composable
fun Art(s: Song?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val key = s?.let { if (it.albumId > 0) it.albumId else -it.id }
    val bmp by produceState<Bitmap?>(key?.let { ArtCache.get(it) }, key) {
        value = if (s == null || key == null) null
        else ArtCache.get(key) ?: withContext(Dispatchers.IO) { loadArt(ctx, s) }?.also { ArtCache.put(key, it) }
    }
    Box(modifier.background(Slate2), Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Rounded.MusicNote, null, tint = Ash, modifier = Modifier.fillMaxSize(0.4f))
    }
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onAction) { Text(action, color = Ash) }
    }
}

@Composable
fun SearchField(v: String, onChange: (String) -> Unit) {
    TextField(
        value = v, onValueChange = onChange, singleLine = true,
        placeholder = { Text("Buscar música, artista ou álbum", color = Ash) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Ash) },
        trailingIcon = { if (v.isNotEmpty()) Ib(Icons.Rounded.Close, "Limpar", Ash, 40.dp) { onChange("") } },
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Slate, unfocusedContainerColor = Slate,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, cursorColor = Color.White
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    )
}

data class Group(val name: String, val sub: String, val songs: List<Song>)

fun albumsOf(l: List<Song>) = l.groupBy { it.album }.map { Group(it.key, it.value.first().artist, it.value) }.sortedBy { it.name.lowercase() }
fun artistsOf(l: List<Song>) = l.groupBy { it.artist }.map { Group(it.key, "${it.value.size} músicas", it.value) }.sortedBy { it.name.lowercase() }
fun filterSongs(l: List<Song>, q: String) =
    if (q.isBlank()) l else l.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }
fun sortSongs(l: List<Song>, s: SortBy, st: Map<Long, PlayStat>) = when (s) {
    SortBy.Name -> l.sortedBy { it.title.lowercase() }
    SortBy.Artist -> l.sortedBy { it.artist.lowercase() }
    SortBy.Album -> l.sortedBy { it.album.lowercase() }
    SortBy.Recent -> l.sortedByDescending { it.added }
    SortBy.Played -> l.sortedByDescending { st[it.id]?.count ?: 0 }
}

@Composable
fun CoverCard(g: Group, round: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.width(140.dp).bounce(onClick)) {
        Art(g.songs.first(), Modifier.size(140.dp).clip(if (round) CircleShape else RoundedCornerShape(16.dp)))
        Spacer(Modifier.height(8.dp))
        Text(g.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(g.sub, color = Ash, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SongRow(s: Song, vm: VM, nav: Nav, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val fav = s.id in vm.favSet.collectAsState().value
    Row(Modifier.fillMaxWidth().bounce(onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(s, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(s.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(s.artist, color = Ash, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (vm.currentId == s.id) Icon(Icons.Rounded.GraphicEq, null, Modifier.padding(end = 8.dp).size(20.dp))
        Text(fmt(s.duration), color = Ash, fontSize = 12.sp)
        Box {
            Ib(Icons.Rounded.MoreVert, "Mais opções", Ash, 40.dp) { menu = true }
            DropdownMenu(menu, { menu = false }, containerColor = Slate2) {
                DropdownMenuItem(
                    text = { Text(if (fav) "Remover dos favoritos" else "Favoritar") },
                    leadingIcon = { Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null) },
                    onClick = { vm.toggleFav(s.id); menu = false })
                DropdownMenuItem(
                    text = { Text("Adicionar à playlist") },
                    leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) },
                    onClick = { nav.add(s); menu = false })
            }
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Slate, title = { Text(title) },
        text = { TextField(t, { t = it }, singleLine = true, placeholder = { Text("Nome da playlist", color = Ash) }) },
        confirmButton = { TextButton({ onOk(t) }, enabled = t.isNotBlank()) { Text("Salvar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar", color = Ash) } }
    )
}

@Composable
fun PlaylistPicker(vm: VM, song: Song, onDismiss: () -> Unit) {
    val pls by vm.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    if (creating) NameDialog("Nova playlist", "", onDismiss) { vm.createPlaylist(it, song.id); onDismiss() }
    else AlertDialog(
        onDismissRequest = onDismiss, containerColor = Slate, title = { Text("Adicionar à playlist") },
        text = {
            Column {
                Row(Modifier.fillMaxWidth().bounce { creating = true }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(12.dp)); Text("Nova playlist", fontWeight = FontWeight.SemiBold)
                }
                pls.forEach { p ->
                    Row(Modifier.fillMaxWidth().bounce { vm.addToPlaylist(p.id, song.id); onDismiss() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.QueueMusic, null, tint = Ash); Spacer(Modifier.width(12.dp)); Text(p.name)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Fechar", color = Ash) } }
    )
}
