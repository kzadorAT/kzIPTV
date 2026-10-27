package ar.kziptv.ui

import android.content.Context
import android.graphics.Bitmap
import android.view.TextureView
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.media3.ui.PlayerView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
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

    // El estado de scroll de la grilla vive ACIMA de la pantalla. Si se creara
    // dentro de GridScreen, al salir al reproductor se perdia y al volver la
    // grilla se recomponia desde cero: por eso "abrir la grilla" se sentia lento.
    val gridState = rememberLazyGridState()
    val chipState = rememberLazyListState()

    when (screen) {
        Screen.SETUP -> SetupScreen(controller)
        Screen.PLAYER -> PlayerScreen(controller)
        Screen.GRID -> GridScreen(controller, gridState, chipState)
        Screen.QUALITY -> QualityScreen(controller)
        Screen.SEARCH -> SearchScreen(controller)
        Screen.MOSAIC -> MosaicScreen(controller)
        Screen.SETTINGS -> SettingsScreen(controller)
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
    val status by controller.status.collectAsState()
    val statusVisible by controller.statusVisible.collectAsState()
    val playbackState by controller.playbackState.collectAsState()
    val playbackError by controller.playbackError.collectAsState()
    val fitMode by controller.videoFitMode.collectAsState()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = fitMode.resizeMode()
                    this.player = controller.player.exo
                }
            },
            update = { view -> view.resizeMode = fitMode.resizeMode() },
            modifier = Modifier.fillMaxSize()
        )

        // Cartel de estado: se ve unos segundos y se va (no queda pegado).
        if (statusVisible && status.isNotBlank()) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(18.dp)
            ) {
                Text(
                    text = status,
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xE61B2029))
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }

        if (banner && current != null) {
            BannerOverlay(
                group = current!!,
                variant = variant,
                favorite = controller.isFavorite(current!!),
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
fun GridScreen(
    controller: TvController,
    gridState: LazyGridState,
    chipState: LazyListState
) {
    val catalog by controller.catalog.collectAsState()
    val selectedCategory by controller.category.collectAsState()
    val focusedCategory by controller.focusedCategory.collectAsState()
    val exitHint by controller.exitHintVisible.collectAsState()

    // Estos estados se crean una sola vez y cada tarjeta deriva el valor que
    // necesita. Mover el foco NO recompone la grilla entera: solo se recompone
    // la tarjeta que gana el foco y la que lo pierde.
    val focusedIdState = controller.focusedGroupId.collectAsState()
    val favoritesState = controller.favorites.collectAsState()
    val currentState = controller.currentGroup.collectAsState()
    val videoWidthState = controller.videoWidth.collectAsState()
    val videoHeightState = controller.videoHeight.collectAsState()

    // Columnas EXACTAS de la grilla: el D-pad del controller necesita saber
    // cuantas hay por fila para mover el foco de fila en fila.
    val configuration = LocalConfiguration.current
    val columns = remember(configuration.screenWidthDp) {
        val available = configuration.screenWidthDp - 48 // padding 24.dp x 2
        ((available + 12) / (200 + 12)).coerceAtLeast(1)
    }
    LaunchedEffect(columns) { controller.setGridColumns(columns) }

    // La lista filtrada se memoriza: sin esto se reconstruye en cada
    // recomposicion de la grilla (y obliga a recomponer las tarjetas).
    val list = remember(catalog, selectedCategory) { catalog.inCategory(selectedCategory) }

    // El foco lo maneja el controller (navegacion determinista). Aca solo se
    // scrollea lo justo para que el canal enfocado quede a la vista.
    // OJO: el estado DEBE pasarse al LazyVerticalGrid (si no, el scroll no existe).
    // El foco se observa con collect (FUERA de la composicion): asi la grilla
    // no se invalida entera en cada tecla, solo la tarjeta que cambia.
    LaunchedEffect(gridState, list) {
        controller.focusedGroupId.collect { focusedId ->
            alignFocusedItem(gridState, list, focusedId)
            val latency = android.os.SystemClock.elapsedRealtime() - controller.lastFocusChangeAt
            android.util.Log.d("KzGrid", "foco alineado en ${latency}ms")
        }
    }

    // Cambiar de categoria vuelve al principio de la lista.
    LaunchedEffect(selectedCategory) {
        gridState.scrollToItem(0)
    }
    LaunchedEffect(focusedCategory, catalog.categories.size) {
        val chips = listOf("") + catalog.categories
        val index = chips.indexOf(focusedCategory ?: "")
        if (index >= 0) chipState.scrollToItem(index)
    }

    Box(Modifier.fillMaxSize()) {
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
                state = gridState,
                columns = GridCells.Fixed(columns),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(list.size, key = { index -> list[index].id }) { index ->
                    val group = list[index]
                    // Cada tarjeta deriva su propio estado: al mover el foco solo
                    // se recompone la que entra y la que sale (no las 20 visibles).
                    val focused by remember(group.id) {
                        derivedStateOf {
                            focusedIdState.value == group.id && focusedCategory == null
                        }
                    }
                    val favorite by remember(group.id) {
                        derivedStateOf { controller.isFavorite(group, favoritesState.value) }
                    }
                    val active by remember(group.id) {
                        derivedStateOf { currentState.value?.id == group.id }
                    }
                    val measured by remember(group.id) {
                        derivedStateOf {
                            val currentGroup = currentState.value
                            if (currentGroup?.id == group.id && videoWidthState.value > 0) {
                                "${videoHeightState.value}p"
                            } else {
                                group.variants.firstOrNull { it.channelId == group.activeVariantId }
                                    ?.measured?.takeIf { it.isNotBlank() }
                                    ?.let { it.substringAfter('x') + "p" } ?: ""
                            }
                        }
                    }
                    ChannelCard(
                        controller = controller,
                        group = group,
                        favorite = favorite,
                        active = active,
                        focused = focused,
                        measuredQuality = measured,
                        onClick = {
                            controller.setFocused(group.id)
                            controller.play(group)
                        }
                    )
                }
            }

            Text(
                text = "OK reproducir \u00b7 OK largo calidad \u00b7 AMARILLO favorito \u00b7 ROJO zap favoritos \u00b7 AZUL ajustes",
                color = Color(0xFF56616F),
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        // Aviso de "BACK otra vez para salir": la app se cierra de verdad.
        if (exitHint) {
            Box(
                Modifier.fillMaxSize().padding(bottom = 60.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(
                    text = "Presiona BACK otra vez para salir",
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xE6222733))
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/**
 * Mantiene a la vista el canal enfocado: scrollea lo justo (una fila por paso)
 * y sin animacion, que en TV se siente mas rapido que un scroll suave.
 */
private suspend fun alignFocusedItem(
    gridState: LazyGridState,
    list: List<ChannelGroup>,
    focusedId: String?
) {
    if (focusedId.isNullOrBlank()) return
    val index = list.indexOfFirst { it.id == focusedId }
    if (index < 0) return
    val info = gridState.layoutInfo
    val visible = info.visibleItemsInfo
    val target = visible.firstOrNull { it.index == index }
    if (target != null) {
        val top = target.offset.y
        val bottom = top + target.size.height
        val delta = when {
            bottom > info.viewportEndOffset -> bottom - info.viewportEndOffset
            top < info.viewportStartOffset -> top - info.viewportStartOffset
            else -> 0
        }
        if (delta != 0) gridState.scrollBy(delta.toFloat())
        return
    }
    // El item enfocado todavia no esta compuesto: se avanza lo justo
    // (una fila por paso) para que entre en pantalla.
    val pitch = visible.zipWithNext()
        .firstOrNull { (a, b) -> b.offset.y > a.offset.y }
        ?.let { (a, b) -> b.offset.y - a.offset.y }
        ?: visible.firstOrNull()?.size?.height
        ?: return
    val anchor = if (index > (visible.lastOrNull()?.index ?: -1)) {
        visible.lastOrNull()
    } else {
        visible.firstOrNull()
    } ?: return
    val delta = (index - anchor.index) * pitch
    if (delta != 0) gridState.scrollBy(delta.toFloat())
}

/**
 * Busqueda en la TV.
 *
 * El campo de texto es un EditText NATIVO (vía AndroidView): usa el teclado DEL
 * SISTEMA, que ya sabe manejar acentos, dictados y teclados externos. No hay
 * ningun teclado propio que mantener.
 *
 * La navegacion entre el campo y la lista de resultados sigue siendo determinista
 * (mismo patron que la grilla), pero cuando el IME del sistema esta abierto las
 * teclas las maneja el sistema.
 */
@Composable
fun SearchScreen(controller: TvController) {
    val query by controller.query.collectAsState()
    val results by controller.searchResults.collectAsState()
    val focusedId by controller.focusedSearchId.collectAsState()
    val fieldRequest by controller.searchFieldRequest.collectAsState()
    val fieldFocused by controller.searchFieldFocused.collectAsState()
    val resultState = rememberLazyListState()
    var editText by remember { mutableStateOf<EditText?>(null) }

    // Cuando el controller pide el foco del campo, se lo damos.
    LaunchedEffect(fieldRequest) {
        if (fieldRequest > 0) {
            editText?.requestFocus()
            editText?.let {
                val imm = it.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    // Si el controller suelta el foco del campo, la UI lo hace tambien
    // (asi el IME del sistema se cierra).
    LaunchedEffect(fieldFocused) {
        if (!fieldFocused) {
            editText?.clearFocus()
        }
    }

    // Scroll minimo para que el resultado enfocado quede a la vista.
    LaunchedEffect(focusedId, results.size) {
        val index = results.indexOfFirst { it.id == focusedId }
        if (index < 0) return@LaunchedEffect
        val info = resultState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item == null) {
            resultState.scrollToItem(index)
        } else {
            val top = item.offset
            val bottom = top + item.size
            val delta = when {
                bottom > info.viewportEndOffset -> bottom - info.viewportEndOffset
                top < info.viewportStartOffset -> top - info.viewportStartOffset
                else -> 0
            }
            if (delta != 0) resultState.scrollBy(delta.toFloat())
        }
    }

    Column(Modifier.fillMaxSize().background(KzBg).padding(24.dp)) {
        Text(text = "Buscar canal", color = Color(0xFF7C8899), fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))

        // Campo de texto NATIVO: el teclado del sistema se abre solo al enfocarlo.
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFF141B24), RoundedCornerShape(10.dp))
                .border(
                    width = if (focusedId == null) 3.dp else 1.dp,
                    color = if (focusedId == null) KzBlue else Color(0xFF232C38),
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AndroidView(
                modifier = Modifier.weight(1f),
                factory = { ctx ->
                    EditText(ctx).apply {
                        setText(query)
                        setTextColor(Color.White.toArgb())
                        setHintTextColor(Color(0xFF56616F).toArgb())
                        hint = "Escribi el nombre del canal…"
                        textSize = 22f
                        isSingleLine = true
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        // Solo texto: el teclado del sistema no trae de mas.
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                                controller.setQuery(s?.toString().orEmpty())
                            }
                            override fun afterTextChanged(s: android.text.Editable?) {}
                        })
                        setOnFocusChangeListener { _, hasFocus ->
                            controller.setSearchFieldFocused(hasFocus)
                        }
                    }
                },
                update = { view ->
                    editText = view
                    // Solo sincronizamos si el texto cambio por fuera (companion).
                    if (view.text.toString() != query && !fieldFocused) {
                        view.setText(query)
                    }
                }
            )
            if (query.isNotEmpty()) {
                Text(
                    text = "×",
                    color = Color(0xFF7C8899),
                    fontSize = 24.sp,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .clickable { controller.clearSearch() }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val count = results.size
            Text(
                text = if (count == 0) {
                    if (query.isBlank()) "201 canales" else "Sin coincidencias"
                } else {
                    "$count coincidencias"
                },
                color = Color(0xFF7C8899),
                fontSize = 14.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "OK abre el teclado · BACK vuelve",
                color = Color(0xFF56616F),
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(6.dp))

        LazyColumn(
            state = resultState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(results.size, key = { index -> results[index].id }) { index ->
                val group = results[index]
                SearchResultRow(
                    group = group,
                    favorite = controller.isFavorite(group),
                    focused = focusedId == group.id,
                    onClick = { controller.play(group) }
                )
            }
        }
    }
}

/** Fila de un resultado de busqueda. */
@Composable
private fun SearchResultRow(
    group: ChannelGroup,
    favorite: Boolean,
    focused: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (focused) Color(0xFF1E2A38) else KzCard, RoundedCornerShape(10.dp))
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) KzBlue else Color(0xFF232C38),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = group.logo,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(44.dp)
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                text = group.number.toString() + ". " + group.name + if (favorite) "  *" else "",
                color = Color.White,
                fontSize = 17.sp,
                maxLines = 1
            )
            Text(
                text = group.groups.joinToString(" \u00b7 "),
                color = Color(0xFF6F7C8C),
                fontSize = 13.sp,
                maxLines = 1
            )
        }
        if (group.variantCount > 1) {
            val n = group.variantCount
            Text(text = "$n calidades", color = KzBlue, fontSize = 13.sp)
        }
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
    controller: TvController,
    group: ChannelGroup,
    favorite: Boolean,
    active: Boolean,
    focused: Boolean,
    measuredQuality: String,
    onClick: () -> Unit
) {
    controller.countCardComposition()
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
    val running by controller.serverRunning.collectAsState()
    val busy by controller.serverBusy.collectAsState()
    // Sin server no hay QR que escanear: la pantalla lo dice y OK lo enciende.
    val bitmap = remember(url, code, running) {
        if (running) qrBitmap("${url}/pair#${code}", 720) else null
    }

    // En dos columnas: en vertical el contenido no entra en 1080p y el codigo quedaba cortado.
    Row(
        Modifier.fillMaxSize().background(KzBg).padding(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (running && bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "QR de emparejamiento",
                    modifier = Modifier.size(320.dp)
                )
                Spacer(Modifier.height(18.dp))
                Text(text = "Codigo: ${code}", color = KzBlue, fontSize = 30.sp)
                Text(text = url, color = Color(0xFF9FB0C3), fontSize = 16.sp)
            } else {
                Text(
                    text = if (busy) "Encendiendo el servidor web\u2026" else "Servidor web apagado",
                    color = Color.White,
                    fontSize = 28.sp
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (busy) "Esperando\u2026" else "Presiona OK para encenderlo",
                    color = Color(0xFF7C8899),
                    fontSize = 18.sp
                )
            }
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
/**
 * Mosaico: una grilla de canales con un frame congelado de cada uno.
 *
 * En Android TV NO existe el PiP del sistema (el feature `picture_in_picture`
 * no esta en las TVs). Ademas el decoder de video por hardware de la TV es
 * UNICO: mantener cuatro streams a la vez lleva al 86% de CPU y cuelga la app.
 *
 * Asi que cada tile se captura reproduciendo el canal unos segundos: se
 * congela un frame y se sigue con el siguiente (ver ThumbnailManager). El grid
 * se va llenando solo mientras el usuario mira, y al mover el foco se captura
 * ese canal al instante.
 */
@Composable
fun MosaicScreen(controller: TvController) {
    val channels by controller.mosaicChannels.collectAsState()
    val focus by controller.mosaicFocus.collectAsState()
    val errors by controller.mosaicTileErrors.collectAsState()
    val thumbs by controller.mosaicThumbs.collectAsState()
    val liveIndex by controller.mosaicLiveIndex.collectAsState()
    val epg by controller.epg.collectAsState()

    // El "ahora" del EPG se refresca cada medio minuto: si no, el programa que
    // se muestra queda desactualizado mientras el usuario mira el mosaico.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val columns = TvController.MOSAIC_COLUMNS

    // Mientras se navega el mosaico la pantalla no se apaga: hay contenido
    // moviendose (el surf) y no tiene sentido que la TV se duerma.
    KeepScreenOn()

    Column(Modifier.fillMaxSize().background(KzBg).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Comparar canales", color = KzBlue, fontSize = 24.sp)
            Text(
                text = "   ${thumbs.size}/${channels.size} capturados" +
                    if (errors.isNotEmpty()) " · ${errors.size} sin señal" else "",
                color = Color(0xFF7C8899),
                fontSize = 13.sp
            )
            // El surf esta vivo: los tiles se refrescan solos.
            if (liveIndex >= 0) {
                Text(
                    text = "  · surf",
                    color = Color(0xFF34D399),
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // El alto disponible se reparte entre las filas: el mosaico nunca se
        // corta, por mas canales o programas que haya.
        Column(
            Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            channels.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    row.forEachIndexed { colIndex, group ->
                        val index = rowIndex * columns + colIndex
                        val program = epg.nowNextByTvgId(group.tvgId, now).first
                        MosaicTile(
                            controller = controller,
                            group = group,
                            index = index,
                            focused = index == focus,
                            live = index == liveIndex,
                            thumb = thumbs[index],
                            error = errors[index],
                            favorite = controller.isFavorite(group),
                            programTitle = program?.title.orEmpty(),
                            modifier = Modifier.fillMaxHeight().weight(1f)
                        )
                    }
                }
            }
        }

        Text(
            text = "OK ver en pantalla completa \u00b7 BACK volver \u00b7 los tiles se refrescan solos (surf)",
            color = Color(0xFF56616F),
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** Un tile del mosaico: el frame congelado (o el video mientras se captura). */
@Composable
private fun MosaicTile(
    controller: TvController,
    group: ChannelGroup,
    index: Int,
    focused: Boolean,
    live: Boolean,
    thumb: Bitmap?,
    error: String?,
    favorite: Boolean,
    programTitle: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF12171E))
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) KzBlue else Color(0xFF232C38),
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        if (thumb != null) {
            // El frame congelado del canal.
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Todavia no se capturo: el logo del canal de fondo.
            Box(
                Modifier.fillMaxSize().background(Color(0xFF0D1218)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = group.logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(56.dp),
                    alpha = 0.4f
                )
            }
        }

        // Mientras se captura, el video real (UN solo a la vez: el decoder de
        // hardware de la TV no da para mas).
        if (live) {
            key(index) {
                AndroidView(
                    factory = { ctx ->
                        val textureView = TextureView(ctx)
                        controller.mosaic.attach(textureView)
                        textureView
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { controller.mosaic.detach(it) }
                )
            }
        }

        // Scrim para que el texto se lea sobre cualquier imagen.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color(0xCC000000)
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY
                    )
                )
        )

        // Numero + nombre, arriba a la izquierda.
        Column(Modifier.align(Alignment.TopStart).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#${group.number}",
                    color = if (focused) KzBlue else Color(0xFF9FB0C3),
                    fontSize = 12.sp
                )
                if (favorite) {
                    Text(text = "  *", color = KzBlue, fontSize = 11.sp)
                }
            }
            Text(
                text = group.name,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 1.dp)
            )
        }

        // Indicador de captura en curso (el surf esta pasando por este canal).
        if (live) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE5484D))
                )
                Text(
                    text = " ON AIR",
                    color = Color(0xFFE5484D),
                    fontSize = 10.sp
                )
            }
        }

        // Abajo: el programa actual (EPG) o el error.
        Column(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
            if (error != null && thumb == null) {
                Text(text = "sin senal", color = Color(0xFFF59E0B), fontSize = 11.sp)
            } else if (programTitle.isNotBlank()) {
                Text(
                    text = programTitle,
                    color = Color(0xFFE2E8F0),
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Ajustes: server web (encendido/apagado), emparejamiento con QR, modo de
 * zap y encuadre del video. El foco lo maneja el controller (mismas reglas
 * que en la grilla: el control remoto y el companion se comportan igual).
 */
@Composable
fun SettingsScreen(controller: TvController) {
    val focus by controller.settingsFocus.collectAsState()
    val running by controller.serverRunning.collectAsState()
    val busy by controller.serverBusy.collectAsState()
    val serverUrl by controller.serverUrl.collectAsState()
    val zapOnly by controller.zapFavoritesOnly.collectAsState()
    val fitMode by controller.videoFitMode.collectAsState()
    val catalog by controller.catalog.collectAsState()
    val epg by controller.epg.collectAsState()
    val epgRefreshing by controller.epgRefreshing.collectAsState()
    val epgSource by controller.epgSource.collectAsState()
    val playlistRefreshing by controller.playlistRefreshing.collectAsState()
    val status by controller.status.collectAsState()

    val serverLabel = when {
        busy -> "Encendiendo\u2026"
        running -> "Encendido"
        else -> "Apagado"
    }
    // Se construye desde el enum para que el foco del controller y las filas
    // conserven siempre el mismo orden.
    val rows = Setting.entries.map { setting ->
        setting to when (setting) {
            Setting.SERVER -> serverLabel
            Setting.PAIRING -> "QR"
            Setting.ZAP -> (if (zapOnly) "Solo favoritos" else "Todos los canales")
            Setting.VIDEO -> controller.videoFitLabel(fitMode)
            Setting.EPG -> controller.epgLabel()
            Setting.PLAYLIST -> (if (playlistRefreshing) "Actualizando\u2026" else "Actualizar")
        }
    }

    // Seis filas + informacion: el padding compacto hace que todo entre en
    // una pantalla de TV de 540 dp de alto.
    Column(
        Modifier.fillMaxSize().background(KzBg).padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Text(text = "Ajustes", color = Color.White, fontSize = 28.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "OK para cambiar \u00b7 BACK para volver",
            color = Color(0xFF56616F),
            fontSize = 13.sp
        )
        Spacer(Modifier.height(10.dp))

        rows.forEachIndexed { index, row ->
            val selected = index == focus
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (selected) Color(0xFF1B3A5C) else KzCard)
                    .padding(horizontal = 18.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (row.first) {
                        Setting.SERVER -> "Servidor web"
                        Setting.PAIRING -> "Emparejar el celular"
                        Setting.ZAP -> "Zap"
                        Setting.VIDEO -> "Video"
                        Setting.EPG -> "Guia EPG"
                        Setting.PLAYLIST -> "Actualizar playlist"
                    },
                    color = if (selected) KzBlue else Color.White,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(text = row.second, color = Color(0xFF9FB0C3), fontSize = 15.sp)
            }
            Spacer(Modifier.height(5.dp))
        }

        Spacer(Modifier.height(7.dp))
        Text(
            text = "Canales: ${catalog.groups.size} \u00b7 Programas EPG: ${epg.programs.size}" +
                "\nServidor: " + (if (serverUrl.isNotBlank()) serverUrl else "apagado") +
                "\nGuia: " + epgSource + (if (epgRefreshing) " (actualizando)" else "") +
                if (playlistRefreshing) "\nPlaylist: actualizando\u2026" else "",
            color = Color(0xFF56616F),
            fontSize = 13.sp
        )
        if (!playlistRefreshing && status.isNotBlank()) {
            Text(
                text = status,
                color = Color(0xFF9FB0C3),
                fontSize = 13.sp
            )
        }
    }
}

/** Traduce el modo de encuadre de Ajustes al constante de Media3. */
private fun Int.resizeMode(): Int = when (this) {
    1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}
