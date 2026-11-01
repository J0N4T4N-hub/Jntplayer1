package com.jnt.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import java.util.Calendar
import kotlin.math.roundToInt

sealed interface Detail {
    data class Album(val name: String) : Detail
    data class Artist(val name: String) : Detail
    data class Pl(val id: Long) : Detail
    data object Fav : Detail
}

class Nav(val open: (Detail) -> Unit, val add: (Song) -> Unit, val expand: () -> Unit, val library: () -> Unit)

@Composable
fun Root(vm: VM) {
    val ctx = LocalContext.current
    val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(has(audioPerm)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = has(audioPerm) }
    fun ask() {
        val need = buildList {
            add(audioPerm); if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { !has(it) }
        if (need.isNotEmpty()) launcher.launch(need.toTypedArray())
    }
    LaunchedEffect(Unit) { ask() }
    LaunchedEffect(granted) { if (granted) vm.refresh() }

    if (!granted) { PermissionScreen { ask() }; return }

    val songs by vm.songs.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    var full by remember { mutableStateOf(false) }
    var addTo by remember { mutableStateOf<Song?>(null) }
    val current = songs.firstOrNull { it.id == vm.currentId }
    val nav = remember { Nav({ detail = it }, { addTo = it }, { full = true }, { vm.libChip = 0; tab = 1; detail = null }) }

    BackHandler(full) { full = false }
    BackHandler(!full && detail != null) { detail = null }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).statusBarsPadding()) {
                AnimatedContent(
                    targetState = tab to detail,
                    transitionSpec = { (fadeIn(tween(240)) + slideInHorizontally(tween(260)) { it / 14 }) togetherWith fadeOut(tween(140)) },
                    label = "nav"
                ) { (t, d) ->
                    when {
                        d != null -> DetailScreen(d, vm, nav) { detail = null }
                        t == 0 -> Home(vm, nav)
                        else -> Library(vm, nav)
                    }
                }
            }
            AnimatedVisibility(current != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                if (current != null) Mini(current, vm) { full = true }
            }
            Row(Modifier.fillMaxWidth().background(Ink).navigationBarsPadding().padding(vertical = 6.dp)) {
                NavItem(Icons.Rounded.Home, "Início", tab == 0 && detail == null, Modifier.weight(1f)) { tab = 0; detail = null }
                NavItem(Icons.Rounded.LibraryMusic, "Biblioteca", tab == 1 && detail == null, Modifier.weight(1f)) { tab = 1; detail = null }
            }
        }
        AnimatedVisibility(full && current != null, enter = slideInVertically(tween(380)) { it } + fadeIn(), exit = slideOutVertically(tween(300)) { it } + fadeOut()) {
            if (current != null) PlayerScreen(current, vm, nav) { full = false }
        }
    }
    addTo?.let { PlaylistPicker(vm, it) { addTo = null } }
}

@Composable
fun NavItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c by animateColorAsState(if (selected) Color.White else Ash, label = "navc")
    Column(modifier.bounce(onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = c, modifier = Modifier.size(26.dp))
        Text(label, color = c, fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
fun PermissionScreen(onAsk: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink).padding(32.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.logo), "JNT Player", Modifier.size(120.dp).clip(RoundedCornerShape(28.dp)))
        Spacer(Modifier.height(24.dp))
        Text("JNT Player", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("Para montar sua biblioteca automaticamente, o app precisa de acesso às músicas do seu aparelho.", color = Ash, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Button(onAsk, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink)) { Text("Permitir acesso") }
    }
}

@Composable
fun Home(vm: VM, nav: Nav) {
    val songs by vm.songs.collectAsState()
    val stats by vm.stats.collectAsState()
    var q by remember { mutableStateOf("") }
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greet = when { hour < 12 -> "Bom dia"; hour < 18 -> "Boa tarde"; else -> "Boa noite" }
    val results = remember(q, songs) { filterSongs(songs, q) }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo), "JNT Player", Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.padding(start = 12.dp)) {
                    Text("JNT Player", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(greet, color = Ash, fontSize = 13.sp)
                }
            }
            SearchField(q) { q = it }
        }
        if (q.isNotBlank()) {
            if (results.isEmpty()) item { Text("Nada encontrado.", color = Ash, modifier = Modifier.padding(24.dp)) }
            items(results, key = { it.id }) { s -> SongRow(s, vm, nav) { vm.play(results, results.indexOf(s)) } }
        } else if (songs.isEmpty()) {
            item { Text("Nenhuma música encontrada neste aparelho.", color = Ash, modifier = Modifier.padding(24.dp)) }
        } else {
            item {
                Row(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Slate).bounce { nav.open(Detail.Fav) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Favorite, null, Modifier.size(24.dp))
                    Text("Músicas favoritas", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(start = 14.dp))
                    Icon(Icons.Rounded.ChevronRight, null, tint = Ash)
                }
            }
            val recent = songs.filter { (stats[it.id]?.last ?: 0) > 0 }.sortedByDescending { stats[it.id]?.last }.take(15)
            if (recent.isNotEmpty()) item {
                SectionTitle("Tocadas recentemente")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(recent, key = { it.id }) { s -> CoverCard(Group(s.title, s.artist, listOf(s))) { vm.play(recent, recent.indexOf(s)) } }
                }
            }
            item {
                val mine = songs.take(15)
                SectionTitle("Suas músicas", "Ver todas") { nav.library() }
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(mine, key = { it.id }) { s -> CoverCard(Group(s.title, s.artist, listOf(s))) { vm.play(songs, songs.indexOf(s)) } }
                }
            }
            item {
                SectionTitle("Álbuns")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(albumsOf(songs).take(20), key = { it.name }) { g -> CoverCard(g) { nav.open(Detail.Album(g.name)) } }
                }
                SectionTitle("Artistas")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(artistsOf(songs).take(20), key = { it.name }) { g -> CoverCard(g, round = true) { nav.open(Detail.Artist(g.name)) } }
                }
            }
        }
    }
}

@Composable
fun Library(vm: VM, nav: Nav) {
    val songs by vm.songs.collectAsState()
    val stats by vm.stats.collectAsState()
    val favs by vm.favSet.collectAsState()
    val pls by vm.playlists.collectAsState()
    val ents by vm.entries.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var newPl by remember { mutableStateOf(false) }
    val chips = listOf("Músicas", "Álbuns", "Artistas", "Favoritas", "Playlists")
    val q = vm.libQuery
    Column(Modifier.fillMaxSize()) {
        Text("Biblioteca", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
        SearchField(q) { vm.libQuery = it }
        LazyRow(contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chips.size) { i ->
                FilterChip(
                    selected = vm.libChip == i, onClick = { vm.libChip = i }, label = { Text(chips[i]) },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Slate, labelColor = Color.White,
                        selectedContainerColor = Color.White, selectedLabelColor = Ink
                    ),
                    border = null
                )
            }
        }
        when (vm.libChip) {
            0, 3 -> {
                val base = if (vm.libChip == 3) songs.filter { it.id in favs } else songs
                val list = remember(base, q, vm.libSort, stats) { sortSongs(filterSongs(base, q), vm.libSort, stats) }
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${list.size} músicas", color = Ash, modifier = Modifier.weight(1f))
                    Box {
                        TextButton({ menu = true }) { Icon(Icons.Rounded.SwapVert, null, tint = Ash); Spacer(Modifier.width(6.dp)); Text(vm.libSort.label, color = Color.White) }
                        DropdownMenu(menu, { menu = false }, containerColor = Slate2) {
                            SortBy.entries.forEach { s -> DropdownMenuItem({ Text(s.label) }, { vm.libSort = s; menu = false }) }
                        }
                    }
                }
                if (list.isEmpty()) Text(if (vm.libChip == 3) "Nenhuma favorita ainda." else "Nada encontrado.", color = Ash, modifier = Modifier.padding(24.dp))
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(list, key = { it.id }) { s -> SongRow(s, vm, nav) { vm.play(list, list.indexOf(s)) } }
                }
            }
            1, 2 -> {
                val groups = (if (vm.libChip == 1) albumsOf(songs) else artistsOf(songs)).filter { it.name.contains(q, true) }
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(groups, key = { it.name }) { g ->
                        Row(Modifier.fillMaxWidth().bounce { nav.open(if (vm.libChip == 1) Detail.Album(g.name) else Detail.Artist(g.name)) }.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Art(g.songs.first(), Modifier.size(56.dp).clip(if (vm.libChip == 2) CircleShape else RoundedCornerShape(12.dp)))
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(g.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (vm.libChip == 1) "${g.sub} · ${g.songs.size} músicas" else g.sub, color = Ash, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
            else -> {
                if (newPl) NameDialog("Nova playlist", "", { newPl = false }) { vm.createPlaylist(it); newPl = false }
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth().bounce { newPl = true }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Slate2), Alignment.Center) { Icon(Icons.Rounded.Add, null) }
                            Text("Criar playlist", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp))
                        }
                    }
                    items(pls.filter { it.name.contains(q, true) }, key = { it.id }) { p ->
                        val n = ents.count { it.playlistId == p.id }
                        Row(Modifier.fillMaxWidth().bounce { nav.open(Detail.Pl(p.id)) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Slate2), Alignment.Center) { Icon(Icons.Rounded.QueueMusic, null, tint = Ash) }
                            Column(Modifier.padding(start = 14.dp)) {
                                Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$n músicas", color = Ash, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailHeader(title: String, sub: String?, list: List<Song>, vm: VM, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Ib(Icons.Rounded.ArrowBack, "Voltar", onClick = onBack)
            Spacer(Modifier.weight(1f)); actions()
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = Ash)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ vm.play(list, 0) }, enabled = list.isNotEmpty(), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink)) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Reproduzir")
                }
                OutlinedButton({ vm.play(list, list.indices.random(), true) }, enabled = list.isNotEmpty()) {
                    Icon(Icons.Rounded.Shuffle, null, tint = Color.White); Spacer(Modifier.width(6.dp)); Text("Aleatório", color = Color.White)
                }
            }
        }
    }
}

@Composable
fun DetailScreen(d: Detail, vm: VM, nav: Nav, onBack: () -> Unit) {
    if (d is Detail.Pl) { PlaylistDetail(d.id, vm, nav, onBack); return }
    val songs by vm.songs.collectAsState()
    val favs by vm.favSet.collectAsState()
    val (title, sub, list) = when (d) {
        is Detail.Album -> Triple(d.name, songs.firstOrNull { it.album == d.name }?.artist, songs.filter { it.album == d.name })
        is Detail.Artist -> Triple(d.name, null, songs.filter { it.artist == d.name })
        else -> Triple("Favoritas", null, songs.filter { it.id in favs })
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item { DetailHeader(title, sub ?: "${list.size} músicas", list, vm, onBack) }
        items(list, key = { it.id }) { s -> SongRow(s, vm, nav) { vm.play(list, list.indexOf(s)) } }
    }
}

@Composable
fun PlaylistDetail(id: Long, vm: VM, nav: Nav, onBack: () -> Unit) {
    val songs by vm.songs.collectAsState()
    val pls by vm.playlists.collectAsState()
    val ents by vm.entries.collectAsState()
    val pl = pls.firstOrNull { it.id == id }
    var rename by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf(false) }
    val map = remember(songs) { songs.associateBy { it.id } }
    val items = ents.filter { it.playlistId == id }.sortedBy { it.pos }.mapNotNull { e -> map[e.songId]?.let { e to it } }
    val list = items.map { it.second }
    if (pl == null) { LaunchedEffect(Unit) { onBack() }; return }
    if (rename) NameDialog("Renomear playlist", pl.name, { rename = false }) { vm.rename(id, it); rename = false }
    if (del) AlertDialog(
        onDismissRequest = { del = false }, containerColor = Slate, title = { Text("Excluir playlist?") },
        text = { Text("As músicas continuam no aparelho.", color = Ash) },
        confirmButton = { TextButton({ vm.deletePlaylist(id); del = false; onBack() }) { Text("Excluir") } },
        dismissButton = { TextButton({ del = false }) { Text("Cancelar", color = Ash) } }
    )
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            DetailHeader(pl.name, "${list.size} músicas", list, vm, onBack) {
                Ib(Icons.Rounded.Edit, "Renomear") { rename = true }
                Ib(Icons.Rounded.Delete, "Excluir") { del = true }
            }
        }
        if (items.isEmpty()) item { Text("Playlist vazia. Use o menu de uma música para adicioná-la.", color = Ash, modifier = Modifier.padding(24.dp)) }
        itemsIndexed(items, key = { _, p -> p.first.id }) { i, (e, s) ->
            Row(Modifier.fillMaxWidth().bounce { vm.play(list, i) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(s, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(s.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.artist, color = Ash, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (i > 0) Ib(Icons.Rounded.KeyboardArrowUp, "Subir", Ash, 36.dp) { vm.swap(e, items[i - 1].first) }
                else Spacer(Modifier.size(36.dp))
                if (i < items.lastIndex) Ib(Icons.Rounded.KeyboardArrowDown, "Descer", Ash, 36.dp) { vm.swap(e, items[i + 1].first) }
                else Spacer(Modifier.size(36.dp))
                Ib(Icons.Rounded.Close, "Remover", Ash, 36.dp) { vm.removeEntry(e) }
            }
        }
    }
}

@Composable
fun Mini(s: Song, vm: VM, onClick: () -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Slate).bounce(onClick)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Art(s, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(s.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.artist, color = Ash, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Ib(if (vm.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause") { vm.toggle() }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Slate2)) {
            Box(Modifier.fillMaxWidth((vm.position.toFloat() / s.duration.coerceAtLeast(1)).coerceIn(0f, 1f)).fillMaxHeight().background(Color.White))
        }
    }
}

@Composable
fun Seek(frac: Float, onSeek: (Float) -> Unit, onDone: () -> Unit) {
    var w by remember { mutableIntStateOf(1) }
    val seek by rememberUpdatedState(onSeek)
    val done by rememberUpdatedState(onDone)
    Box(
        Modifier.fillMaxWidth().height(28.dp).onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures(onTap = { o -> seek((o.x / w).coerceIn(0f, 1f)); done() }) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = { done() }, onDragCancel = { done() }) { ch, _ -> seek((ch.position.x / w).coerceIn(0f, 1f)) }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Slate2))
        Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(3.dp).clip(CircleShape).background(Color.White))
        Box(Modifier.offset { IntOffset((w * frac.coerceIn(0f, 1f) - 6.dp.toPx()).roundToInt(), 0) }.size(12.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
fun PlayerScreen(s: Song, vm: VM, nav: Nav, onClose: () -> Unit) {
    val favs by vm.favSet.collectAsState()
    var drag by remember { mutableStateOf<Float?>(null) }
    val dur = s.duration.coerceAtLeast(1)
    val frac = drag ?: (vm.position.toFloat() / dur)
    val sc by animateFloatAsState(if (vm.playing) 1f else 0.88f, spring(0.7f, Spring.StiffnessLow), label = "art")
    var picker by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF1E1E1E), Ink))).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp)
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ib(Icons.Rounded.KeyboardArrowDown, "Fechar", size = 44.dp, onClick = onClose)
            Text("Tocando agora", Modifier.weight(1f), textAlign = TextAlign.Center, color = Ash, fontSize = 13.sp)
            Spacer(Modifier.size(44.dp))
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f, false).fillMaxWidth(), Alignment.Center) {
            AnimatedContent(s, transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(250)) }, label = "cover",
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).scale(sc).shadow(24.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))
            ) { song -> Art(song, Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(s.artist, fontSize = 16.sp, color = Ash, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val fav = s.id in favs
            Ib(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorito", size = 48.dp) { vm.toggleFav(s.id) }
        }
        Spacer(Modifier.height(16.dp))
        Seek(frac, { drag = it }, { drag?.let { vm.seek((it * dur).toLong()) }; drag = null })
        Row(Modifier.fillMaxWidth()) {
            Text(fmt((frac * dur).toLong()), color = Ash, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(fmt(s.duration), color = Ash, fontSize = 12.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Ib(Icons.Rounded.Shuffle, "Aleatório", if (vm.shuffle) Color.White else Ash) { vm.toggleShuffle() }
            Ib(Icons.Rounded.SkipPrevious, "Anterior", size = 60.dp) { vm.prev() }
            Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White).bounce { vm.toggle() }, Alignment.Center) {
                Icon(if (vm.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = Ink, modifier = Modifier.size(38.dp))
            }
            Ib(Icons.Rounded.SkipNext, "Próxima", size = 60.dp) { vm.next() }
            Ib(if (vm.repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repetir",
                if (vm.repeat == Player.REPEAT_MODE_OFF) Ash else Color.White) { vm.cycleRepeat() }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), Arrangement.Center) { Ib(Icons.Rounded.PlaylistAdd, "Adicionar à playlist", Ash) { picker = true } }
    }
    if (picker) PlaylistPicker(vm, s) { picker = false }
}
