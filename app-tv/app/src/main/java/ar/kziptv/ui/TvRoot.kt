package ar.kziptv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import ar.kziptv.core.model.Channel

val KzBlue = Color(0xFF38BDF8)
val KzBg = Color(0xFF0B0F14)
val KzCard = Color(0xFF151C26)

@Composable
fun KzTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = KzBlue,
            background = KzBg,
            surface = KzCard
        ),
        content = content
    )
}

@Composable
fun TvRoot(controller: TvController) {
    val screen by controller.screen.collectAsState()
    when (screen) {
        Screen.SETUP -> SetupScreen(controller)
        Screen.PLAYER -> PlayerScreen(controller)
        Screen.GRID -> GridScreen(controller)
    }
}

@Composable
fun PlayerScreen(controller: TvController) {
    val current by controller.current.collectAsState()
    val banner by controller.bannerVisible.collectAsState()
    val favorites by controller.favorites.collectAsState()
    val status by controller.status.collectAsState()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    this.player = controller.player.exo
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Text(
            text = status,
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
            modifier = Modifier.padding(16.dp)
        )

        if (banner && current != null) {
            BannerOverlay(
                channel = current!!,
                favorite = favorites.contains(current!!.id),
                controller = controller
            )
        }
    }
}

@Composable
private fun BannerOverlay(channel: Channel, favorite: Boolean, controller: TvController) {
    val (now, next) = controller.epg.value.nowNext(channel)
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.78f))
                .padding(horizontal = 28.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(76.dp)
            )
            Column(Modifier.padding(start = 20.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${channel.number}. ${channel.name}",
                        color = Color.White,
                        fontSize = 26.sp
                    )
                    if (favorite) {
                        Text(text = "  *", color = KzBlue, fontSize = 26.sp)
                    }
                }
                if (now != null) {
                    Text(text = "Ahora: ${now.title}", color = Color(0xFFB8C4D4), fontSize = 16.sp)
                }
                if (next != null) {
                    Text(text = "Luego: ${next.title}", color = Color(0xFF8A97A8), fontSize = 14.sp)
                }
            }
            Text(
                text = channel.groups.joinToString(" / "),
                color = Color(0xFF6F7C8C),
                fontSize = 13.sp,
                textAlign = TextAlign.End
            )
        }
    }
}

@Composable
fun GridScreen(controller: TvController) {
    val catalog by controller.catalog.collectAsState()
    val selectedCategory by controller.category.collectAsState()
    val favorites by controller.favorites.collectAsState()
    val current by controller.current.collectAsState()
    val focusedId by controller.focusedChannelId.collectAsState()

    val list = catalog.inCategory(selectedCategory)

    Column(Modifier.fillMaxSize().background(KzBg).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "kzIPTV", color = KzBlue, fontSize = 30.sp)
            Text(
                text = "   ${list.size} canales${if (controller.zapFavoritesOnly.value) " / zap: FAVORITOS" else ""}",
                color = Color(0xFF7C8899),
                fontSize = 16.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                CategoryChip("Todos", selectedCategory == null) { controller.setCategory(null) }
            }
            items(catalog.categories.size) { index ->
                val category = catalog.categories[index]
                CategoryChip(category, selectedCategory == category) { controller.setCategory(category) }
            }
        }

        Spacer(Modifier.height(16.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(200.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(list.size, key = { index -> list[index].id }) { index ->
                val channel = list[index]
                ChannelCard(
                    channel = channel,
                    favorite = favorites.contains(channel.id),
                    active = current?.id == channel.id,
                    focused = focusedId == channel.id,
                    onFocused = { controller.setFocused(channel.id) },
                    onClick = {
                        controller.setFocused(channel.id)
                        controller.play(channel)
                    }
                )
            }
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        colors = SurfaceDefaults.colors(
            containerColor = if (selected) KzBlue else KzCard,
            contentColor = if (selected) Color(0xFF07131A) else Color(0xFF9FB0C3)
        ),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(text = label, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
    }
}

@Composable
private fun ChannelCard(
    channel: Channel,
    favorite: Boolean,
    active: Boolean,
    focused: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(
            containerColor = when {
                focused -> Color(0xFF1E2A38)
                active -> Color(0xFF16303F)
                else -> KzCard
            },
            contentColor = Color.White
        ),
        modifier = Modifier
            .width(200.dp)
            .height(116.dp)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) KzBlue else Color(0xFF232C38),
                shape = RoundedCornerShape(12.dp)
            )
            .onFocusChanged { state -> if (state.isFocused) onFocused() }
            .clickable { onClick() }
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(66.dp)
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(text = channel.name, fontSize = 15.sp, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("#${channel.number}")
                        if (favorite) append("  *")
                        if (!channel.playable) append("  (no soportado)")
                    },
                    color = Color(0xFF7C8899),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun SetupScreen(controller: TvController) {
    val url by controller.serverUrl.collectAsState()
    val code by controller.pairingCode.collectAsState()
    val status by controller.status.collectAsState()
    val bitmap = remember(url, code) { qrBitmap("${url}/pair#${code}", 720) }

    // En dos columnas: en vertical el contenido no entra en 1080p y el codigo quedaba cortado.
    Row(
        Modifier.fillMaxSize().background(KzBg).padding(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "QR de emparejamiento",
                    modifier = Modifier.size(320.dp)
                )
            } else {
                Text(text = "QR no disponible", color = Color.Gray, fontSize = 18.sp)
            }
            Spacer(Modifier.height(18.dp))
            Text(text = "Codigo: ${code}", color = KzBlue, fontSize = 30.sp)
            Text(text = url, color = Color(0xFF9FB0C3), fontSize = 16.sp)
        }
        Column(modifier = Modifier.width(480.dp)) {
            Text(text = "Configura kzIPTV desde tu celular", color = Color.White, fontSize = 32.sp)
            Spacer(Modifier.height(22.dp))
            Text(
                text = "1. Escanea el QR con la camara del celular" +
                    "\n2. Pega la URL de tu playlist m3u (o subi el archivo)" +
                    "\n3. La TV recibe la lista y queda lista para ver",
                color = Color(0xFF7C8899),
                fontSize = 17.sp,
                textAlign = TextAlign.Start
            )
            Spacer(Modifier.height(18.dp))
            Text(text = status, color = Color(0xFF56616F), fontSize = 14.sp)
        }
    }
}

fun qrBitmap(data: String, size: Int): Bitmap? = try {
    val matrix = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size)
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[y * width + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        .apply { setPixels(pixels, 0, width, 0, 0, width, height) }
} catch (e: Exception) {
    null
}
