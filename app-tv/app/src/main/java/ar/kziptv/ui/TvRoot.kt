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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
import ar.kziptv.core.nav.NumberPad
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

    // La grilla se mantiene COMPUESTA mientras se mira el video (solo no se
    // dibuja): al volver ya esta lista, con los logos cargados y en la posicion
    // donde estaba. Medido en la TV: abrir la grilla paso de ~2,4 s (recomponer
    // 20 tarjetas + logos) a 118-174 ms. Se usa el MISMO sitio de composicion
    // para que Compose conserve la grilla viva al cambiar de pantalla.
    // El menu contextual se dibuja ENCIMA de la grilla (o del video): lo de
    // fondo no se recompone ni se interrumpe.
    val menuFromPlayer by controller.channelMenuFromPlayer.collectAsState()
    val gridVisible = screen == Screen.GRID ||
        (screen == Screen.CHANNEL_MENU && !menuFromPlayer)
    val keepGrid = screen == Screen.GRID || screen == Screen.PLAYER ||
        screen == Screen.SETTINGS || screen == Screen.LISTS ||
        screen == Screen.CHANNEL_MENU || screen == Screen.NUMBER_PAD

    Box(Modifier.fillMaxSize()) {
        if (keepGrid) {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent { if (gridVisible) drawContent() }
            ) {
                GridScreen(controller, gridState, chipState, visible = gridVisible)
            }
        }
        when (screen) {
            Screen.SETUP -> SetupScreen(controller)
            Screen.PLAYER -> PlayerScreen(controller)
            Screen.GRID -> Unit
            Screen.QUALITY -> QualityScreen(controller)
            Screen.SEARCH -> SearchScreen(controller)
            Screen.MOSAIC -> MosaicScreen(controller)
            Screen.SETTINGS -> SettingsScreen(controller)
            Screen.LISTS -> ListsScreen(controller)
            Screen.CHANNEL_MENU -> {
                // El video sigue detras del menu: no se corta la reproduccion.
                if (menuFromPlayer) PlayerScreen(controller)
                ChannelMenuOverlay(controller)
            }
            Screen.NUMBER_PAD -> NumberPadScreen(controller)
        }
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
    chipState: LazyListState,
    visible: Boolean = true
) {
    val catalog by controller.catalog.collectAsState()
    val selectedCategory by controller.category.collectAsState()
    val focusedCategory by controller.focusedCategory.collectAsState()
    val exitHint by controller.exitHintVisible.collectAsState()

    // Cuanto tarda en REALLY verse la grilla cuando se vuelve del reproductor.
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        withFrameNanos {
            val delta = android.os.SystemClock.elapsedRealtime() - controller.lastScreenChangeAt
            android.util.Log.d("KzGrid", "grilla visible en ${delta}ms")
        }
    }

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
        val available = configuration.screenWidthDp - RAIL_WIDTH.value.toInt() - 48 // padding 24.dp x 2
        ((available + 12) / (236 + 12)).coerceAtLeast(1)
    }
    LaunchedEffect(columns) { controller.setGridColumns(columns) }

    // La lista filtrada se memoriza: sin esto se reconstruye en cada
    // recomposicion de la grilla (y obliga a recomponer las tarjetas).
    // El filtro de favoritos vive en el controller: la UI y el foco usan la
    // MISMA lista (si no, el foco apuntaria a un canal que no se ve).
    val favoritesOnly by controller.favoritesOnly.collectAsState()
    val zapFavoritesOnly by controller.zapFavoritesOnly.collectAsState()
    val list = remember(catalog, selectedCategory, favoritesOnly, favoritesState.value) {
        controller.visibleChannels()
    }

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
        Row(Modifier.fillMaxSize()) {
            // La barra lateral: la columna de secciones (LEFT la enfoca).
            RailColumn(controller)

            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${list.size} canales" +
                            (if (catalog.variantCount > catalog.groups.size) " / ${catalog.variantCount} feeds" else "") +
                            (if (favoritesOnly) " / solo FAVORITOS" else "") +
                            (if (zapFavoritesOnly) " / zap: FAVORITOS" else ""),
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
                    // weight y no fillMaxSize: asi la linea de ayuda entra en pantalla.
                    modifier = Modifier.fillMaxWidth().weight(1f)
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
                            measuredQuality = measured
                        )
                    }
                }

                Text(
                    text = "OK reproducir \u00b7 OK largo acciones \u00b7 LEFT secciones \u00b7 BACK volver",
                    color = Color(0xFF56616F),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 10.dp)
                )
                }
            }
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
    measuredQuality: String
) {
    controller.countCardComposition()
    val bestQuality = group.variants.firstOrNull()?.quality?.uppercase().orEmpty()
    val quality = if (measuredQuality.isNotBlank()) measuredQuality else bestQuality

    val cardModifier = Modifier
        .width(236.dp)
        .height(124.dp)
        .border(
            width = if (focused) 3.dp else 1.dp,
            color = if (focused) KzBlue else Color(0xFF232C38),
            shape = RoundedCornerShape(12.dp)
        )
        // Sin clickable: en TV el OK lo maneja el controller (el foco es propio)

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
                modifier = Modifier.size(72.dp)
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
 * Selector de lista: que playlists mira el usuario.
 *
 * OK activa o desactiva la lista enfocada; OK largo la quita (pide confirmacion
 * con una segunda pulsacion). BACK vuelve a Ajustes.
 */
@Composable
fun ListsScreen(controller: TvController) {
    val playlists by controller.playlists.collectAsState()
    val focus by controller.listsFocus.collectAsState()
    val busy by controller.listsBusy.collectAsState()
    val removing by controller.listsRemovingId.collectAsState()
    val status by controller.status.collectAsState()

    Column(
        Modifier.fillMaxSize().background(KzBg).padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Text(text = "Listas de canales", color = Color.White, fontSize = 28.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "OK activa o desactiva \u00b7 OK largo quita \u00b7 BACK para volver",
            color = Color(0xFF56616F),
            fontSize = 13.sp
        )
        Spacer(Modifier.height(10.dp))

        if (playlists.isEmpty()) {
            Text(
                text = "No hay playlists guardadas",
                color = Color(0xFF9FB0C3),
                fontSize = 16.sp
            )
        } else {
            playlists.forEachIndexed { index, item ->
                val selected = index == focus
                val quitting = removing == item.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (selected) Color(0xFF1B3A5C) else KzCard)
                        .padding(horizontal = 18.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            color = if (selected) KzBlue else Color.White,
                            fontSize = 17.sp,
                            maxLines = 1
                        )
                        Text(
                            text = listSubtitle(item),
                            color = Color(0xFF9FB0C3),
                            fontSize = 13.sp,
                            maxLines = 1
                        )
                    }
                    Text(
                        text = when {
                            quitting -> "OK otra vez"
                            item.active -> "ACTIVA"
                            else -> "apagada"
                        },
                        color = when {
                            quitting -> Color(0xFFFF8A80)
                            item.active -> KzBlue
                            else -> Color(0xFF56616F)
                        },
                        fontSize = 15.sp
                    )
                }
                Spacer(Modifier.height(5.dp))
            }
        }

        Spacer(Modifier.height(7.dp))
        Text(
            text = buildString {
                append("Activas: " + playlists.count { it.active } + " de " + playlists.size)
                if (busy) append(" \u00b7 actualizando\u2026")
                if (status.isNotBlank()) append("\n" + status)
            },
            color = Color(0xFF56616F),
            fontSize = 13.sp
        )
    }
}

/** Detalle de una lista: de donde sale, cuantos canales tiene y cuando se actualizo. */
private fun listSubtitle(item: PlaylistDto): String {
    val origen = when {
        item.bundled -> "embebida"
        item.url.isNotBlank() -> item.url.substringAfter("://").substringBefore('/')
        else -> "archivo"
    }
    val fecha = if (item.lastRefreshAt > 0) {
        java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(item.lastRefreshAt))
    } else {
        "sin actualizar"
    }
    return origen + " \u00b7 " + item.channelCount + " canales \u00b7 " + fecha
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
            Setting.LISTS -> controller.listsLabel()
        }
    }

    // Siete filas + informacion: el padding compacto hace que todo entre en
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
                        Setting.LISTS -> "Listas de canales"
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
/** Ancho de la barra lateral (la columna de secciones). */
private val RAIL_WIDTH = 160.dp

/**
 * Barra lateral: la columna de secciones de la app, a la izquierda de la grilla.
 *
 * Es la casa de todo lo que antes exigia una tecla de color (zap, buscar,
 * ajustes, mosaico): hay controles remotos que no las tienen. Se enfoca con
 * LEFT en la primera columna y se deja con RIGHT o BACK.
 */
@Composable
private fun RailColumn(controller: TvController) {
    val active by controller.railActive.collectAsState()
    val focus by controller.railFocus.collectAsState()
    val favoritesOnly by controller.favoritesOnly.collectAsState()
    val zapOnly by controller.zapFavoritesOnly.collectAsState()

    Column(
        Modifier
            .width(RAIL_WIDTH)
            .fillMaxHeight()
            .background(Color(0xFF101722))
            .padding(horizontal = 9.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "kzIPTV", color = KzBlue, fontSize = 19.sp)
        Spacer(Modifier.height(14.dp))
        Rail.entries.forEachIndexed { index, item ->
            RailItem(
                label = when (item) {
                    Rail.CANALES -> "Canales"
                    Rail.FAVORITOS -> "Favoritos"
                    Rail.ZAP -> "Zap \u2605"
                    Rail.BUSCAR -> "Buscar"
                    Rail.LISTAS -> "Listas"
                    Rail.COMPARAR -> "Comparar"
                    Rail.AJUSTES -> "Ajustes"
                    Rail.IR_A_CANAL -> "Ir a canal #"
                },
                mark = when (item) {
                    Rail.FAVORITOS -> if (favoritesOnly) "\u25CF" else null
                    Rail.ZAP -> if (zapOnly) "\u25CF" else null
                    else -> null
                },
                selected = active && index == focus
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Una seccion de la barra lateral. */
@Composable
private fun RailItem(label: String, mark: String?, selected: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF1B3A5C) else Color.Transparent)
            // El borde solo cuando esta enfocada: con 0.dp igual se ve un pelo.
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = KzBlue,
                        shape = RoundedCornerShape(10.dp)
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else Color(0xFF8D9AAC),
            fontSize = 14.sp,
            maxLines = 1
        )
        if (mark != null) {
            Text(
                text = mark,
                color = KzBlue,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 5.dp)
            )
        }
    }
}

/**
 * Menu contextual del canal (OK largo): las acciones del canal enfocado.
 *
 * Se dibuja encima de la grilla o del video: no se interrumpe nada.
 */
@Composable
private fun ChannelMenuOverlay(controller: TvController) {
    val group = controller.channelMenuGroup()
    val items = controller.channelMenuItems()
    val focus by controller.channelMenuFocus.collectAsState()
    val favorites by controller.favorites.collectAsState()
    val isFavorite = group != null && controller.isFavorite(group, favorites)

    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(430.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF18212E))
                .padding(18.dp)
        ) {
            Text(
                text = group?.name ?: "Canal",
                color = Color.White,
                fontSize = 20.sp,
                maxLines = 1
            )
            Text(
                text = group?.let { "#${it.number} \u00b7 ${it.variants.size} feeds" } ?: "",
                color = Color(0xFF7C8899),
                fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            items.forEachIndexed { index, action ->
                val selected = index == focus
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Color(0xFF1B3A5C) else Color.Transparent)
                        .then(
                            if (selected) {
                                Modifier.border(
                                    width = 2.dp,
                                    color = KzBlue,
                                    shape = RoundedCornerShape(12.dp)
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = when (action) {
                            ChannelAction.REPRODUCIR -> "Reproducir"
                            ChannelAction.FAVORITO ->
                                if (isFavorite) "Quitar de favoritos" else "Agregar a favoritos"
                            ChannelAction.CALIDAD -> "Calidad / feeds"
                            ChannelAction.COMPARAR -> "Comparar en mosaico"
                        },
                        color = if (selected) Color.White else Color(0xFFB9C6D6),
                        fontSize = 16.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "OK ejecuta \u00b7 BACK vuelve",
                color = Color(0xFF56616F),
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Teclado numerico en pantalla: para ir a un canal sin teclas de numeros.
 *
 * Es la misma idea del buffer 0-9, pero dibujada: el numero escrito sale
 * arriba con el nombre del canal apenas coincide.
 */
@Composable
private fun NumberPadScreen(controller: TvController) {
    val buffer by controller.keypadBuffer.collectAsState()
    val focus by controller.keypadFocus.collectAsState()
    val match = controller.keypadMatch()

    Column(
        Modifier
            .fillMaxSize()
            .background(KzBg)
            .padding(34.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Ir a canal", color = Color.White, fontSize = 26.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            text = buffer.ifBlank { "--" },
            color = if (match != null) KzBlue else Color.White,
            fontSize = 54.sp
        )
        Text(
            text = match?.let { "\u2192 ${it.name}" }
                ?: "Elegi el numero del canal (sale en la tarjeta de la grilla)",
            color = Color(0xFF7C8899),
            fontSize = 15.sp
        )
        Spacer(Modifier.height(22.dp))

        // 3 columnas x 4 filas: 1-9, borrar, 0, ir.
        for (row in 0 until 4) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (col in 0 until 3) {
                    val index = row * 3 + col
                    val selected = index == focus
                    val label = when {
                        NumberPad.isDelete(index) -> "\u232B"
                        NumberPad.isGo(index) -> "\u25B6"
                        else -> NumberPad.digitAt(index).toString()
                    }
                    Box(
                        Modifier
                            .size(width = 112.dp, height = 60.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Color(0xFF1B3A5C) else KzCard)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) KzBlue else Color(0xFF232C38),
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (selected) Color.White else Color(0xFFB9C6D6),
                            fontSize = 23.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(11.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = "OK marca \u00b7 \u25B6 reproduce \u00b7 BACK borra y despues vuelve",
            color = Color(0xFF56616F),
            fontSize = 13.sp
        )
    }
}
