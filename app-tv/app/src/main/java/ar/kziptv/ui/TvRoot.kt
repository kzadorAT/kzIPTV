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
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
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
import ar.kziptv.core.model.ChannelGroup
import ar.kziptv.core.model.ChannelVariant

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
    KeepScreenOn()
    when (screen) {
        Screen.SETUP -> SetupScreen(controller)
        Screen.PLAYER -> PlayerScreen(controller)
        Screen.GRID -> GridScreen(controller)
        Screen.QUALITY -> QualityScreen(controller)
    }
}

/**
 * El salvapantallas de la TV se activaba en mitad de la reproduccion.
 * Mientras la app esta en primer plano pedimos pantalla encendida
 * (View.KEEP_SCREEN_ON -> WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON).
 */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
fun PlayerScreen(controller: TvController) {
    val current by controller.currentGroup.collectAsState()
    val variant by controller.currentVariant.collectAsState()
    val banner by controller.bannerVisible.collectAsState()
    val favorites by controller.favorites.collectAsState()
    val status by controller.status.collectAsState()
    val playbackState by controller.playbackState.collectAsState()
    val playbackError by controller.playbackError.collectAsState()

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
                group = current!!,
                variant = variant,
                favorite = favorites.contains(current!!.id) ||
                    current!!.variants.any { favorites.contains(it.channelId) },
                controller = controller
            )
        }

        when (playbackState) {
            "buffering" -> CenterOverlay(
                title = "Cargando canal\u2026",
                detail = listOfNotNull(current?.name, variant?.label).joinToString(" \u00b7 "),
                color = Color.White
            )
            "error" -> CenterOverlay(
                title = "\u26A0 Este canal no se pudo reproducir",
                detail = "$playbackError\nBuscando otra calidad o el siguiente canal\u2026",
                color = Color(0xFFFF8A80)
            )
        }
    }
}

/** Mensaje centrado sobre el video (carga / error). */
@Composable
private fun CenterOverlay(title: String, detail: String, color: Color) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = title, color = color, fontSize = 26.sp, textAlign = TextAlign.Center)
        if (detail.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = detail,
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun BannerOverlay(
    group: ChannelGroup,
    variant: ChannelVariant?,
    favorite: Boolean,
    controller: TvController
) {
    val (now, next) = controller.epg.value.nowNextByTvgId(group.tvgId)
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
                model = group.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(76.dp)
            )
            Column(Modifier.padding(start = 20.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${group.number}. ${group.name}",
                        color = Color.White,
                        fontSize = 26.sp
                    )
                    if (favorite) {
                        Text(text = "  *", color = KzBlue, fontSize = 26.sp)
                    }
                }
                if (variant != null && variant.label.isNotBlank()) {
                    Text(text = variant.label, color = KzBlue, fontSize = 15.sp)
                }
                if (now != null) {
                    Text(text = "Ahora: ${now.title}", color = Color(0xFFB8C4D4), fontSize = 16.sp)
                }
                if (next != null) {
                    Text(text = "Luego: ${next.title}", color = Color(0xFF8A97A8), fontSize = 14.sp)
                }
            }
            Text(
                text = group.groups.joinToString(" / "),
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
    val current by controller.currentGroup.collectAsState()
    val focusedId by controller.focusedGroupId.collectAsState()
    val videoWidth by controller.videoWidth.collectAsState()
    val videoHeight by controller.videoHeight.collectAsState()
    val gridState = rememberLazyGridState()
    val chipState = rememberLazyListState()
    val focusedCategory by controller.focusedCategory.collectAsState()

    // Columnas EXACTAS de la grilla: el D-pad del controller necesita saber
    // cuantas hay por fila para mover el foco de fila en fila.
    val configuration = LocalConfiguration.current
    val columns = remember(configuration.screenWidthDp) {
        val available = configuration.screenWidthDp - 48 // padding 24.dp x 2
        ((available + 12) / (200 + 12)).coerceAtLeast(1)
    }
    LaunchedEffect(columns) { controller.setGridColumns(columns) }

    val list = catalog.inCategory(selectedCategory)

    // El foco lo maneja el controller (navegacion determinista). Aca solo se
    // scrollea lo justo para que el canal enfocado quede a la vista.
    LaunchedEffect(focusedId, list.size, selectedCategory) {
        val index = list.indexOfFirst { it.id == focusedId }
        if (index < 0) return@LaunchedEffect
        val info = gridState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null) {
            gridState.scrollToItem(index)
        } else {
            val top = item.offset.y
            val bottom = top + item.size.height
            val delta = when {
                bottom > info.viewportEndOffset -> bottom - info.viewportEndOffset
                top < info.viewportStartOffset -> top - info.viewportStartOffset
                else -> 0
            }
            if (delta != 0) gridState.scrollBy(delta.toFloat())
        }
    }
    LaunchedEffect(focusedCategory, catalog.categories.size) {
        val chips = listOf("") + catalog.categories
        val index = chips.indexOf(focusedCategory ?: "")
        if (index >= 0) chipState.scrollToItem(index)
    }

    Column(Modifier.fillMaxSize().background(KzBg).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "kzIPTV", color = KzBlue, fontSize = 30.sp)
            Text(
                text = "   ${list.size} canales" +
                    (if (catalog.variantCount > catalog.groups.size) " / ${catalog.variantCount} feeds" else "") +
                    (if (controller.zapFavoritesOnly.value) " / zap: FAVORITOS" else ""),
                color = Color(0xFF7C8899),
                fontSize = 16.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        LazyRow(state = chipState, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                CategoryChip(
                    label = "Todos",
                    selected = selectedCategory == null,
                    focused = focusedCategory == "",
                    onClick = {
                        controller.setCategory(null)
                        controller.focusGrid()
                    }
                )
            }
            items(catalog.categories.size) { index ->
                val category = catalog.categories[index]
                CategoryChip(
                    label = category,
                    selected = selectedCategory == category,
                    focused = focusedCategory == category,
                    onClick = {
                        controller.setCategory(category)
                        controller.focusGrid()
                    }
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(list.size, key = { index -> list[index].id }) { index ->
                val group = list[index]
                val measured = if (current?.id == group.id && videoWidth > 0) {
                    "${videoHeight}p"
                } else {
                    group.variants.firstOrNull { it.channelId == group.activeVariantId }
                        ?.measured?.takeIf { it.isNotBlank() }?.let { it.substringAfter('x') + "p" } ?: ""
                }
                ChannelCard(
                    group = group,
                    favorite = favorites.contains(group.id) ||
                        group.variants.any { favorites.contains(it.channelId) },
                    active = current?.id == group.id,
                    focused = focusedId == group.id && focusedCategory == null,
                    measuredQuality = measured,
                    onClick = {
                        controller.setFocused(group.id)
                        controller.play(group)
                    }
                )
            }
        }

        Text(
            text = "OK reproducir \u00b7 OK largo calidad \u00b7 AMARILLO favorito \u00b7 ROJO zap favoritos \u00b7 MENU QR",
            color = Color(0xFF56616F),
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

/** Selector de calidad: una fila por variante del canal. */
@Composable
fun QualityScreen(controller: TvController) {
    val target by controller.qualityTarget.collectAsState()
    val focusedId by controller.focusedVariantId.collectAsState()

    val group = target
    if (group == null) {
        Text(text = "No hay canal seleccionado", color = Color.White, fontSize = 22.sp)
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(KzBg)
            .padding(44.dp)
    ) {
        Text(text = "Calidad de canal", color = Color(0xFF7C8899), fontSize = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(text = group.name, color = Color.White, fontSize = 32.sp)
        Spacer(Modifier.height(22.dp))

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            group.variants.forEach { variant ->
                VariantRow(
                    variant = variant,
                    focused = focusedId == variant.channelId,
                    onClick = { controller.selectVariant(variant.channelId) }
                )
            }
        }

        Text(
            text = "OK reproduce esta calidad \u00b7 BACK vuelve",
            color = Color(0xFF56616F),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun VariantRow(
    variant: ChannelVariant,
    focused: Boolean,
    onClick: () -> Unit
) {
    val statusLabel = when (variant.status) {
        ChannelVariant.STATUS_OK -> "funciona"
        ChannelVariant.STATUS_DEAD -> "muerto"
        ChannelVariant.STATUS_SLOW -> "lento"
        else -> "sin probar"
    }
    val statusColor = when (variant.status) {
        ChannelVariant.STATUS_OK -> Color(0xFF7EE787)
        ChannelVariant.STATUS_DEAD -> Color(0xFFFF8A80)
        ChannelVariant.STATUS_SLOW -> Color(0xFFF0B429)
        else -> Color(0xFF6F7C8C)
    }

    val modifier = Modifier
        .fillMaxWidth()
        .background(if (focused) Color(0xFF1E2A38) else KzCard, RoundedCornerShape(12.dp))
        .border(
            width = if (focused) 3.dp else 1.dp,
            color = if (focused) KzBlue else Color(0xFF232C38),
            shape = RoundedCornerShape(12.dp)
        )
        .clickable { onClick() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = Color.White),
        modifier = modifier
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (variant.quality.isNotBlank()) variant.quality.uppercase() else "AUTO",
                color = if (variant.quality.isNotBlank()) KzBlue else Color(0xFF8A97A8),
                fontSize = 18.sp,
                modifier = Modifier.width(90.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(text = variant.name, fontSize = 18.sp, maxLines = 1)
                Text(
                    text = if (variant.measured.isNotBlank()) {
                        variant.host + " \u00b7 real " + variant.measured
                    } else {
                        variant.host
                    },
                    color = if (variant.measured.isNotBlank()) Color(0xFF9FB0C3) else Color(0xFF6F7C8C),
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
            if (variant.region.isNotBlank()) {
                Text(text = variant.region, color = Color(0xFF9FB0C3), fontSize = 15.sp)
            }
            Spacer(Modifier.width(14.dp))
            Text(text = statusLabel, color = statusColor, fontSize = 13.sp)
        }
    }
}

@Composable
private fun CategoryChip(
    label: String,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        colors = SurfaceDefaults.colors(
            containerColor = if (selected) KzBlue else KzCard,
            contentColor = if (selected) Color(0xFF07131A) else Color(0xFF9FB0C3)
        ),
        modifier = Modifier
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = Color(0xFF9EE6FF),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
    ) {
        Text(text = label, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
    }
}

@Composable
private fun ChannelCard(
    group: ChannelGroup,
    favorite: Boolean,
    active: Boolean,
    focused: Boolean,
    measuredQuality: String,
    onClick: () -> Unit
) {
    val bestQuality = group.variants.firstOrNull()?.quality?.uppercase().orEmpty()
    val quality = if (measuredQuality.isNotBlank()) measuredQuality else bestQuality

    val cardModifier = Modifier
        .width(200.dp)
        .height(116.dp)
        .border(
            width = if (focused) 3.dp else 1.dp,
            color = if (focused) KzBlue else Color(0xFF232C38),
            shape = RoundedCornerShape(12.dp)
        )
        .clickable { onClick() }

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
        modifier = cardModifier
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = group.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(66.dp)
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(text = group.name, fontSize = 15.sp, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "#${group.number}", color = Color(0xFF7C8899), fontSize = 12.sp)
                    if (favorite) {
                        Text(text = "  *", color = KzBlue, fontSize = 13.sp)
                    }
                    if (quality.isNotBlank()) {
                        Text(text = "  ${quality}", color = KzBlue, fontSize = 12.sp)
                    }
                    if (group.variantCount > 1) {
                        Text(text = "  x${group.variantCount}", color = Color(0xFF9FB0C3), fontSize = 12.sp)
                    }
                }
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
