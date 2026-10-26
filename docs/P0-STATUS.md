# Estado P0 — kzIPTV (spike funcional)

Fecha: 25/10/2026 · Estado: **P0 validada en TV real** · APK: `app-debug.apk` 18,9 MB

La P0 tuvo como objetivo responder "¿esto es viable?" con **código real, no maquetas**:
app Android TV que arranca, lee la playlist real de iptv-org, reproduce HLS/DASH, hace zapping
con `CH+`/`CH-`, muestra EPG, y se configura desde el celular vía QR.

---

## 1. Resultado por feature

| Feature | Estado | Detalle |
|---|---|---|
| Parser M3U/M3U8 | ✅ | tvg-id, tvg-logo, group-title, EXTVLCOPT (User-Agent/Referer/Origin), KODIPROP, nombres con comas |
| Catálogo + numeración | ✅ | 206 canales numerados 1..206, 20 categorías, búsqueda |
| Reproducción | ✅ | Media3/ExoPlayer 1.5.1: HLS, DASH, progresivo; headers por canal (User-Agent/Referer) sin recrear el player |
| Zapping con teclas | ✅ | `KEYCODE_CHANNEL_UP/DOWN` (166/167) → canal siguiente/anterior; `PROG_RED` → modo "zap solo favoritos" |
| Favoritos | ✅ | tecla amarilla (185) o long-press OK; persistidos en Room |
| Banner de canal | ✅ | logo + número + nombre + ★ + "Ahora/Luego" (EPG) al cambiar de canal |
| Grilla de canales | ✅ | Compose for TV, chips de categoría, logo por canal, foco visible (navegación **determinista**, ver §8) |
| Setup por QR | ✅ | la TV sirve una web por HTTP; QR con `<url>/pair#<codigo>` + código de 6 dígitos |
| Companion web (celular) | ✅ | Preact + Vite, 19 KB JS: configurar playlist, control remoto, listado, favoritos |
| API / WebSocket | ✅ | `/api/state`, `/api/pair`, `/api/source`, `/api/favorite`, `/api/command`, `WS /ws?token=` — los endpoints que **modifican** la TV exigen el token que devuelve `/api/pair` |
| EPG | 🟡 | 50/206 canales matchean por tvg-id exacto; 46 con guía real (2.474 programas). Ver §3 |
| Robustez de reproducción | ✅ | error visible en pantalla, salto automático al canal siguiente, retoma el último canal al abrir. Ver §7 |
| Variantes / selector de calidad | ✅ | **hecho en P1**: 206 feeds → **201 canales lógicos** (agrupados por tvg-id base + nombre normalizado), selector de calidad con fallback automático y TTL de 6 h para los feeds muertos |
| Salvapantallas | ✅ | `FLAG_KEEP_SCREEN_ON` mientras se reproduce (verificado en `dumpsys window`) |
| Comparar canales (mosaico de miniaturas) | ✅ | **hecho en P1**: grilla de 16 tiles con un frame congelado por canal. **No hay PiP en Android TV** y el decoder de hardware es único → se congela un frame por canal, no se transmiten varios streams (ver §10) |
| Persistencia Room / multi-lista / PWA | 🟡 | **Room hecho en P1**: esquema v3, import del JSON legado con backup, cache EPG e historial (ver §5.1). Falta el selector de multi-lista en la UI y la PWA |

---

## 2. La playlist real (ar.m3u de iptv-org)

- **206 canales**, 100% con `tvg-logo` y `tvg-id`
- Formatos: **199 HLS (.m3u8)**, 2 DASH (.mpd), 1 MP4, 1 FLV (marcado como no soportado), 3 URLs sin extensión
- **20 categorías** (Undefined 63, General 53, Music 15, News 13, Entertainment 13, Sports 12, Movies 10, Animation 10, Kids 10, Religious 9, Culture 6, Series 5)
- 23 canales traen `#EXTVLCOPT` (User-Agent/Referer) → hay que respetarlos o no reproducen

Va **empaquetada** en `app-tv/app/src/main/assets/playlist/ar.m3u`: la app arranca con contenido
sin depender de internet, y desde el celular se puede reemplazar por cualquier otra m3u.

---

## 3. EPG — lo que se descubrió (lo importante de esta fase)

### 3.1 El origen de los `tvg-id`

Los ids de la playlist (`AmericaTV.ar@SD`, `247CanaldeNoticias.ar@SD`) **no** son inventados:
salen de **mi.tv**, el portal de guía de DIRECTV. En el repo de `iptv-org/epg` está
`sites/mi.tv/mi.tv_ar.channels.xml` con 210 canales argentinos donde `xmltv_id` es exactamente
nuestro `tvg-id` y `site_id` es el id interno de mi.tv (`ar#america-2-capital`).

Por eso el match del EPG es **exacto**, no heurístico.

### 3.2 Cobertura medida (número real, no estimado)

| Métrica | Valor |
|---|---|
| Canales de la playlist | 206 |
| Match por tvg-id (exacto o sin sufijo `@SD`/`@HD`/`@Panregional`) | **50** |
| Canales que además tienen guía descargada | **46** |
| Programas en el XMLTV (2 días) | **2.474** |
| Canales sin EPG público posible | **156** |

Los 156 sin guía son **canales locales/regionales** (ABTV Bariloche, Canal 2 de Ushuaia, Canal 4
San Juan…) y panregionales que mi.tv no cubre en su índice argentino. **No existe EPG público para
ellos**: la app los muestra sin "Ahora/Luego" y eso es el techo real de esta fuente.

### 3.3 El scraper oficial de DIRECTV está roto

`iptv-org/epg` tiene un scraper para `directv.com.ar` que pegaba a
`directv.com.ar/guia/ChannelDetail.aspx/GetProgramming`. **Hoy devuelve HTML en vez de JSON**:
742 requests → 742 errores, 0 programas (probado: 6m18s de grab). Por eso el pipeline **no** usa el
grab genérico sino un scraper propio contra `mi.tv`, que hoy responde 200 con la grilla completa.

### 3.4 Pipeline implementado

```
tools/epg/scripts/fetch-mitv-epg.mjs
  → lee la playlist (tvg-id)
  → lee el índice mi.tv (xmltv_id → site_id)
  → match exacto → GET https://mi.tv/ar/async/channel/{id}/{fecha}/0  (2 días, concurrencia 4)
  → XMLTV recortado a la playlist → epg/ar.xml.gz + epg/report.json
```

- Salida: `epg/ar.xml.gz` (176 KB) → empaquetada en los assets de la app
  - Ojo: AGP **descomprime** los assets `.gz` al empaquetar (en el APK queda `assets/epg/ar.xml`),
    por eso la app prueba las dos variantes y detecta el gzip por sus magic bytes.
- CI: `.github/workflows/epg.yml` corre **todos los lunes** (gratis con GitHub Actions), publica
  `epg/ar.xml.gz` y el reporte. En P1 la app lo baja sola desde `raw.githubusercontent`.
- `tools/epg/scripts/measure-epg-sources.mjs` mide la cobertura de *todas* las fuentes de
  iptv-org/epg (253 sitios) — hoy la mejor es mi.tv.

---

## 4. Stack y decisiones técnicas de la P0

| Área | Elección | Por qué |
|---|---|---|
| UI | Compose for TV 1.0.0 (tv-foundation / tv-material) | foco y navegación D-pad nativos |
| Player | Media3/ExoPlayer 1.5.1 | HLS/DASH/progresivo, headers por canal |
| Server | Ktor 3.0.3 + Netty embebido | la TV sirve la web de setup: sin servidor externo |
| Estado | `MutableStateFlow` + Room 2.7.2 (`KzStore`) | escrituras transaccionales y serializadas; en P0 era un store JSON |
| Companion | Vite 5 + Preact 10 + TS | 19 KB de JS, carga instantánea en el celular |
| QR | ZXing core 3.5.3 | sin dependencias de servicios |

### Fricciones reales que aparecieron (resueltas)

1. **Compose for TV**: el `Surface` clickeable exige `ClickableSurfaceShape`/`ClickableSurfaceColors`;
   se resolvió usando el `Surface` no clickeable + `Modifier.clickable()` + `border()` propio.
2. **Netty**: 12 jars con el mismo `META-INF/INDEX.LIST` rompían `mergeDebugJavaResource` →
   `packaging.resources.excludes`.
3. **Headers por canal**: `ExoPlayer.setMediaSourceFactory` no existe; `MediaItem.setHttpRequestHeaders`
   tampoco en 1.5.1 → se inyecta un `DataSource.Factory` propio que crea un `DefaultHttpDataSource`
   con los headers del canal actual (zapping instantáneo, sin recrear el player).
4. **Ktor 3**: `close(CloseReason)` no compila en el scope de WebSocket → se responde un frame de error.
5. **Parser M3U**: el nombre visible corta en la **primera coma fuera de comillas** (no la última),
   y se toma de la línea `#EXTINF` (no de la URL).
6. **ANR en la TV real**: el parser de XMLTV usaba un regex con `DOT_MATCHES_ALL` sobre ~2 MB →
   backtracking catastrófico en el hilo principal (86% CPU, ANR a los 10 s). Se reemplazó por un
   escáner lineal con `indexOf`, y la lectura + parseo del EPG y de la playlist se movió a
   `Dispatchers.IO`. El server Ktor pasó a un hilo daemon para no competir con el main thread.
7. **Media3 exige el hilo con el que se creó**: todo lo que toque el ExoPlayer tiene que correr en
   el hilo principal. Ktor atiende en hilos de Netty, así que `/api/command` devolvía **HTTP 500**
   (`IllegalStateException` del player) mientras que el zapping con el control remoto de la TV
   funcionaba (ese camino llega al main thread). Se agregó `TvController.onMain { }` y todos los
   comandos del server pasan por ahí.
8. **Layout del setup**: en vertical el contenido no entraba en 1080p y el código de 6 dígitos
   quedaba cortado → el setup pasó a **dos columnas** (QR a la izquierda, texto a la derecha).
9. **Seguridad del companion**: los endpoints mutables (`source`, `favorite`, `command`) ahora
   exigen el token de emparejamiento; antes cualquiera de la red podía cambiar la playlist.

---

## 5. Pruebas en la TV real

La app se instaló por ADB sobre Wi-Fi en una **TCL Smart TV Pro (Android 11, 1920×1080)** y se
probó de punta a punta con el companion abierto en un navegador — exactamente lo que hace el
celular tras escanear el QR.

| Prueba | Resultado |
|---|---|
| Arranque con la playlist embebida | ✅ `206 canales · 2474 programas EPG` |
| Zapping con `CH+`/`CH-` del control de la TV | ✅ cambia de canal (verificado por `/api/state`) |
| Pantalla de setup | ✅ QR legible + código de 6 dígitos completo (sin cortes) |
| Emparejar desde el navegador con el código | ✅ 593819 → "Emparejado" |
| `Canal +` / `Canal −` desde la web | ✅ cambian el canal en la TV |
| `★ Favorito` desde la web | ✅ la TV marca el favorito y lo persiste |
| `Zap favoritos` desde la web | ✅ la TV conmuta a modo "solo favoritos" |
| `Emparejar / QR` desde la web | ✅ la TV abre la pantalla de setup |
| Tocar un canal en la lista de la web | ✅ la TV salta a ese canal |
| Listado en la web con EPG | ✅ "Los Jóvenes Titanes en acción", "Rescatando a papá"… |
| Estabilidad | ✅ sin ANR ni crash (logcat limpio) |

> Nota: `adb screencap` no muestra el video (el player dibuja en un `SurfaceView`); la
> reproducción se verificó por el estado del player y los banners, no por la captura.

### Cómo instalarlo en otra TV

```sh
# 1. en la TV: Configuración → red → activar ADB debugging (Android TV / Google TV)
# 2. en la PC (misma red):
adb connect <IP-DE-LA-TV>:5555
adb install -r app-tv/app/build/outputs/apk/debug/app-debug.apk

# 3. abrir "kzIPTV" en la TV
```

### 5.1 Room en la TV real (P1)

| Prueba | Resultado |
|---|---|
| Import del JSON legado | ✅ se importa una sola vez y queda `kz-state.json.room-backup` (4.136 bytes) |
| Migración de esquema en la TV | ✅ `user_version` 1 → 2 → 3 sin perder datos (favoritos, tokens, pairing, calidad, resoluciones) |
| Tests instrumentados en la TV | ✅ `OK (5 tests)`: import JSON, migración v1→v3, cache EPG + historial, 20 updates concurrentes |
| Cache EPG | ✅ 2.474 programas en Room; arranque con parseo **10,5 s** → con cache **5,5 s** (`EPG listo ... (cache)`) |
| Token después de migrar | ✅ el token guardado en Room sirve (`POST /api/favorite` → 200); token inválido → 401 |
| Historial | ✅ `watch_history` guarda las reproducciones (acotado a 500 filas) |
| Suite JVM | ✅ 76 tests verdes (`testDebugUnitTest`) |

### 5.2 Observaciones del usuario (26/10/2026)

| Lo que se reporta | Estado | Próximo paso |
|---|---|---|
| En la grilla, al seguir bajando la pantalla queda estática | **bug — arreglado**: el `LazyVerticalGrid` no recibía `state = gridState` | **hecho (26/10):** `state = gridState` + scroll "lo justo"; verificado en la TV (`5de8a5f`) |
| Guía lineal de EPG con el canal actual visible (también en grilla) | **nuevo** (la grilla EPG completa ya estaba en P2) | diseñar el ticker horizontal (P2) |
| Cambiar audio / subtítulos / resolución | **parcial**: la resolución ya existe (selector de calidad) y los streams actuales no traen pistas alternas (0 de 24 medidos) | implementarlo solo cuando el usuario cargue una playlist multi-audio |
| Menú de configuraciones generales | **contemplado** en el plan (§3, §8) | **hecho (26/10):** pantalla AJUSTES con 4 filas, tecla AZUL (`f17ae64`) |
| Server web siempre prendido | **contemplado** en el plan (§3) | **hecho (26/10):** el server no arranca solo; toggle en Ajustes + QR desde el emparejamiento (runtime: se apaga al salir de la app) |
| Cartel "201 canales · 2474 programas EPG" pegado en pantalla | **no es debug**: es el `status` persistente | **hecho (26/10):** `setStatus(sticky)` + cartel transitorio con recuadro que se va solo a los 3,5 s (`5de8a5f`) |

**Qué probar en 5 minutos**

1. La TV arranca en el reproductor (la playlist viene embebida).
2. `CH+` / `CH-` cambian de canal (zapping), el banner aparece 4 s con logo + "Ahora/Luego".
3. Tecla **AZUL** → pantalla **AJUSTES** (Servidor web / Emparejar el celular / Zap / Video);
   desde ahí, "Emparejar el celular" → setup con **QR** y código de 6 dígitos.
4. Celular: escanear el QR → web del companion → *Configurar* → pegar la URL de la m3u → *Enviar*.
5. La TV cambia la lista al instante (se ve en la grilla).
6. Tecla **ROJA** → zapping solo por favoritos; tecla **AMARILLA** → marca/desmarca favorito.
7. En el celular: *Control* → D-pad virtual → los botones mandan las teclas a la TV por WebSocket.

---

## 6. Qué sigue (P1, en orden de valor)

1. **Agrupar canales duplicados + selector de calidad con fallback** (§5 del plan): hoy la misma
   señal aparece 2-4 veces (distinta región/resolución) y el primer feed puede estar muerto.
   Con agrupación, el zapping recorre canales lógicos y la app cae en una variante viva.
2. **Mosaico + PiP** (lo único grande que falta del pedido original): miniaturas de N canales +
   PiP con un 2.º decoder.
3. **EPG siempre fresco**: la app baja `epg/ar.xml.gz` del repo en background y cachea en Room
   (y lo parsea una sola vez, no en cada arranque). → **el cache en Room ya está** (P1, §5.1);
   falta la descarga en background con parseo incremental.
4. **Room + multi-lista**: playlists propias, guardado de EPG, historial. → **Room hecho en P1**
   (esquema v3, import del JSON legado, cache EPG e historial); falta el selector de lista en la TV.
5. ~~**UX y ajustes** (observaciones del 26/10/2026, §5.2)~~ **Hecho:** scroll de la grilla,
   menú de Ajustes, server apagable y cartel de estado transitorio (ver §12).
6. **Favoritos y perfiles por usuario**, cola de reproducción, "ver después".
7. **EPG de canales locales**: buscar una fuente alternativa por zona (el scraper de cada signal provider).
8. Release firmado + distribución (sideload por QR / Play private track).

---

## 7. La pantalla negra: causa raíz y arreglo

**Síntoma**: al abrir la app (o después de cerrar y reabrir) la TV quedaba negra, con el texto
"206 canales · 2474 programas EPG" abajo a la izquierda.

**Causa raíz** (no era la UI): el canal que la app reproduce al abrir es el **#1 del catálogo**,
`Cartoon Network Latin America`, y su stream responde **HTTP 403 desde cualquier cliente**
(curl con UA de navegador, con UA de la app, con sus propios headers EXTVLCOPT: 403).
El mismo canal existe en otro feed — `Cartoon Network Latin America South Atlantic (1080p)` —
que responde **200**.

O sea: **no era un bug de reproducción sino un feed muerto**, y la app no lo decía: el error
del player no se mostraba, así que la TV quedaba en negro sin explicación.

**Arreglo**:

- El player ahora reporta estado y error (`Player.Listener`): `buffering` / `playing` / `error`.
- El error se ve en pantalla ("⚠ Este canal no se pudo reproducir — Response code: 403") y en la
  web del companion.
- **Salto automático**: si el canal falla, al cabo de 1,8 s se prueba el siguiente (hasta 6 fallos
  seguidos; después avisa que no responde ninguno).
- **Retoma el último canal** al abrir (persistido en el store), en vez de empezar siempre por el #1.
- `/api/state` expone `playbackState`, `playbackError` y `videoWidth/videoHeight`.

**Verificado en la TV**:

```
t+0s  buffering   → Cartoon Network Latin America
t+4s  error 403   → el error se muestra en pantalla
t+6s  auto-saltó  → Cartoon Network Latin America South Atlantic (1080p)
t+8s  playing     → video 1280×720 decodificando
```

Este caso es exactamente el que resuelve la **agrupación de variantes + calidad** (P1): con
fallback entre variantes, la app cae en "South Atlantic (1080p)" sin cambiar de canal.

### Rendimiento medido (TCL, Android 11, build debug)

| Métrica | Valor |
|---|---|
| Arranque en frío | 5,75 s |
| Primer frame | 3,34 s |
| Playlist (206 canales) | 2,64 s |
| EPG (2.474 programas) | 7,10 s (background) |
| Memoria PSS | 138 MB |
| Reproducción | 1280×720 ✓ |

La estrategia de optimización (medir primero, optimizar de a pares con cada feature, y no tocar
el main thread) está en `docs/PLAN.md` §14.

---

## 8 · P1 en la TV: lo que se resolvió (y cómo)

### El foco de Compose no funcionaba en la TV — dos intentos fallidos

Con Compose **1.10.3** (el que resuelve el proyecto, no el 1.7.5 del BOM):

1. `Modifier.clickable` **no crea un focus target**. Mirando el bytecode de `ClickableKt`:
   `AbstractClickableNode` implementa `PointerInputModifierNode`, `KeyInputModifierNode`,
   `SemanticsModifierNode`, `TraversableNode`, `CompositionLocalConsumerModifierNode`,
   `ObserverModifierNode` e `IndirectPointerInputModifierNode` — **nada de `FocusableModifierNode`**
   (aunque internamente tenga un `FocusableNode` privado, que solo sirve para delegar key input).
2. Agregando `.focusable()` + un `FocusRequester` por tarjeta, el foco **seguía sin enganchar**:
   el logcat mostraba `focused=false` en todas las tarjetas y ni una sola línea `focus OK`.
   El `FocusRequester` queda atado a un nodo, y en una grilla lazy ese nodo nunca terminó
   siendo el focusable activo.

**Solución implementada: navegación determinista.** Se abandona el foco nativo de Compose para la
grilla y el **`TvController` pasa a ser el dueño del foco**:

| Pieza | Qué hace |
|---|---|
| `TvController.focusedGroupId` | canal enfocado (grilla) |
| `TvController.focusedCategory` | chip enfocado (`null` = el foco está en la grilla) |
| `TvController.focusedVariantId` | variante enfocada (selector de calidad) |
| `TvController.onDpad(code)` | mueve el foco según el D-pad |
| `TvController.setGridColumns(n)` | la UI informa cuántas columnas tiene la grilla |
| `/api/state` | expone `focusedId`, `focusedName`, `focusedCategory` |

Reglas: ←/→ mueven dentro de la fila · ↑/↓ saltan una fila completa · ↑ en la primera fila sube a
los chips de categoría · ↓ en los chips vuelve a la grilla · en el selector de calidad ↑/↓ recorren
las variantes.

La grilla pasó de `GridCells.Adaptive(200.dp)` a `GridCells.Fixed(columns)` con las columnas
calculadas exactamente (4 en 1920×1080 @ 320 dpi), así el salto de fila del D-pad coincide con lo
que se ve en pantalla.

**Por qué esta decisión y no otra**: con foco determinista, el **control remoto físico y el
companion hacen exactamente lo mismo** (mismo método, misma ruta de código), no depende de quirks de
foco por versión de Compose ni por fabricante de TV, y el estado del foco es observable desde la
API.

### Verificado en la TV (TCL Smart TV Pro)

```
OK                → abre la grilla (foco en el canal actual)
D-pad RIGHT       → Xtrema Cine Clasico (idx 6, col 1) → Comedy Central (idx 7, col 2)
D-pad RIGHT (borde)→ no desborda la fila
D-pad DOWN        → Catamarca TV (idx 11)  [salta 4 = una fila]
D-pad UP (fila 0) → sube a la fila de chips
D-pad RIGHT       → chips: 'Animation' → 'Classic'
D-pad DOWN        → vuelve a la grilla
Selector calidad → Disney Channel: DOWN recorre '1080P · Panregional' → 'South'
Companion (HTTP)  → {"type":"key","code":20} mueve el foco igual que el control remoto
```

Capturas: el borde azul sigue al canal enfocado (LAMIL35 TV #4) y el selector de calidad marca la
variante elegida.

### Además, en P1

- **Agrupación de canales**: 206 feeds → **201 canales lógicos** (solo 4 grupos tienen más de un
  feed). La clave es el `tvg-id` **base** (`Base.cc@Qualifier` → `Base.cc`); si no hay tvg-id
  útil, se agrupa por nombre normalizado (sacando tokens de calidad y región).
- **Fallback entre variantes**: si la variante activa falla, se prueban las otras **del mismo
  canal** antes de saltar al canal siguiente.
- **Errores transitorios**: `ERROR_CODE_BEHIND_LIVE_WINDOW` / `IO_READ_POSITION_OUT_OF_RANGE` se
  reintentan (2 veces, 1,5 s de espera) en la misma variante; un 403 real marca el feed como muerto.
- **Feed muerto con TTL**: el estado `dead` expira a las 6 h y el usuario puede reintentar
  explícitamente.
- **Resolución medida**: se guarda la resolución real detectada por variant (`measuredResolution`).
---

## 9 · Búsqueda, número directo y el bug del bucle de reintentos

### El teclado propio se descartó: usamos el del sistema

La primera versión de la búsqueda traía un **teclado en pantalla propio** (A-Z, 0-9, ESPACIO,
BORRAR, LIMPIAR) navegado con el D-pad. La decisión se revirtió por dos motivos:

1. **El teclado del sistema ya existe y anda mejor.** La TV tiene Gboard TV
   (`com.google.android.inputmethod.latin`) y el IME de TCL. El sistema resuelve acentos,
   dictado y **teclados físicos externos**; un teclado propio no.
2. **El foco de Compose no es el foco de Android.** El problema de §8 era que los focus targets
   de **Compose** no enganchaban. Un `EditText` **nativo** (vía `AndroidView`) usa el sistema de
   focus de la **plataforma**, que sí funciona. La generalización que hice al principio
   ("no hay input de texto porque no hay foco") era incorrecta.

**Lo que quedó**:

| Pieza | Qué hace |
|---|---|
| `Screen.SEARCH` | pantalla de búsqueda |
| `AndroidView` + `EditText` | campo de texto **nativo**, con el IME del sistema |
| `TvController.searchFieldRequest` | token para pedirle a la UI que enfoque el campo |
| `TvController.searchFieldFocused` | el campo tiene foco de plataforma → el IME está abierto |
| `TvController.applySearch(q)` | recalcula resultados con `NumberEntry.search` |
| `NumberEntry` | lógica pura: buffer numérico, resolución por número, búsqueda |

Cuando el IME está abierto, `MainActivity.dispatchKeyEvent` **deja pasar las teclas** (no las
intercepta): las maneja el sistema. El BACK primero cierra el IME y solo después sale de la
pantalla.

### Las dos formas de buscar (ninguna necesita teclado propio)

1. **Desde el celular** (camino principal): el companion manda `{"type":"search","text":"..."}` y
   la TV muestra los resultados. Ya no hace falta tocar la TV.
2. **Desde la TV**: tecla VERDE → pantalla de búsqueda → OK abre el **IME del sistema**.
3. **Número directo**: teclas **0-9** escriben el número de canal; se confirma con OK o solo a
   los 2,5 s. Ej: `12` → Catamarca TV.

### El bug: la app recargaba el mismo canal una y otra vez

**Síntoma**: la TV cargaba el mismo canal repetidamente, sin terminar nunca.

**Causa raíz** (en `TvController.onStateChanged`):

```kotlin
if (state == "playing") {
    consecutiveFailures = 0
    val id = currentVariant.value?.channelId
    if (id != null) retryCount.remove(id)   // <-- el contador se reiniciaba
    markVariant(id, ChannelVariant.STATUS_OK)
}
```

Un feed moribundo dispara `"playing"` un instante antes de caerse. Al ver ese `"playing"` el
contador de reintentos se **reiniciaba**, así que el feed volvía a fallar, volvía a decir
"playing", y el contador se reiniciaba otra vez: **bucle infinito**. Además
`consecutiveFailures = 0` en el mismo lugar anulaba el corte de seguridad (`MAX_AUTO_SKIP`),
porque nunca acumulaba fallas.

El logcat lo mostraba clarísimo:

```
21:24:39  reintento 0 de Cartoon Network Latin America South Atlantic (1080p)
21:24:51  reintento 0 de Cartoon Network Latin America South Atlantic (1080p)  <-- reinició
21:24:59  reintento 0 de Cartoon Network Latin America South Atlantic (1080p)  <-- reinició
21:25:07  reintento 1 de Cartoon Network Latin America South Atlantic (1080p)
```

**Arreglo**:

- El feed solo se confirma como **estable** si el **mismo** feed sigue reproduciendo después de
  `STABLE_PLAYING_MS` (8 s). Se verifica con un job programado (`scheduleStableCheck`), no con
  un evento puntual: un feed sano puede reproducir 30 s seguidos sin emitir más "playing".
- Recién ahí se limpian `retryCount` y `consecutiveFailures`.
- Los errores **transitorios** ya no cuentan como "canal muerto": el corte `MAX_AUTO_SKIP`
  cuenta solo feeds que murieron de verdad.
- Tope **global** de reintentos (`MAX_TRANSIENT_RETRIES = 6`): aunque un feed se reinicie,
  ningún feed puede reintentar para siempre.
- La política quedó en `RetryPolicy` (código puro, testeable sin Android).

**Verificado en la TV** (antes / después):

```
antes:  reintento 0 -> reintento 0 -> reintento 0 -> reintento 1 -> ... (infinito)
ahora:  reintento 0 -> reintento 1 -> "error de reproduccion" -> siguiente feed
```

### Verificado en la TV: búsqueda

```
VERDE            -> abre la pantalla de búsqueda (201 coincidencias)
OK               -> abre el IME del sistema (mInputShown=true en dumpsys)
input text "cartoon" -> "2 coincidencias": Cartoon Network Latin America + Xtrema Cartoons
BACK             -> cierra el IME, la pantalla sigue en SEARCH
DOWN             -> baja al primer resultado
OK               -> reproduce y vuelve al PLAYER
1 2 + OK        -> salta directo al canal 12 (Catamarca TV)
```

---

## 10 · Comparar canales: por qué NO hay mosaico en vivo (y cómo se resolvió)

### Los tres hechos que cambiaron el diseño

1. **La TV tiene UN solo decoder de video por hardware.**

   ```
   OMX.realtek.video.decoder (RTKOMX_CORE, refCount 0)   <-- UNICA instancia
   c2.android.aac.decoder                                 <-- audio por software
   ```

   La primera versión del mosaico era "cuatro canales en vivo a la vez" (2x2 con PiP). Con
   cuatro `ExoPlayer` los que no entran al decoder caen a **decodificación por software**: la
   app llegó al **86 % de CPU** (64 % user + 22 % kernel, 458.353 faults) y murió sin excepción
   Java. El Dropbox de la TV lo dejó escrito:

   ```
   Subject: Input dispatching timed out (... is not responding. Waited 10005ms for FocusEvent)
   ```

2. **Android TV no tiene PiP del sistema.** `pm list features` de la TV solo declara
   `android.hardware.type.television`, `android.software.leanback` y `android.software.leanback_only`.
   No hay `android.software.picture_in_picture`: el PiP es de celular/tablet.

3. **El pedido se puede cumplir igual.** Comparar canales no es "mirar cuatro streams": es
   **ver de un vistazo qué está pasando en cada señal**. Un frame congelado alcanza, y como
   no hay decoder compartido, se pueden mostrar **muchos más canales**.

### La solución: mosaico de miniaturas

Un único `ExoPlayer` + un único `TextureView` (`ThumbnailManager`):

| Pieza | Qué hace |
|---|---|
| `ThumbnailManager.capture(channel)` | reproduce el canal, espera el primer frame, congela con `textureView.getBitmap()` y detiene el player |
| `TvController.captureTile(index)` | prueba las variantes del canal (primero las que ya se saben sanas), reintenta los errores transitorios y marca como muerto lo que falla en serio |
| `TvController.startMosaicSweep()` | recorre los 16 tiles de a uno: el grid se va llenando solo mientras el usuario mira |
| `MosaicTile` (Compose) | muestra el frame congelado, el logo del canal si todavía no se capturó, y "sin señal" si el feed está caído |

- **16 tiles (4x4)**, con el programa actual del EPG abajo de cada uno.
- Tecla **MENU** o **INFO** abre el mosaico; también el botón **Comparar canales** del celular
  (`{"type":"mosaic"}`).
- Al mover el foco se cancela el barrido y se captura ese canal al instante; **OK** lo abre en
  pantalla completa y **BACK** vuelve a la grilla.

### El surf: los tiles se refrescan solos

El mosaico no queda con 16 fotos viejas: es una pared que **navega** sola.

| Ritmo | Qué pasa |
|---|---|
| cada **15 s** | se vuelve a capturar el tile **enfocado** (el que está mirando el usuario): el canal se despierta unos segundos y vuelve a congelarse |
| cada **~2 min** | vuelta completa a la página: los demás tiles se refrescan en rolling |
| al **mover el foco** | se corta la captura en curso y se captura el canal nuevo al instante |

En la UI el header muestra **"· surf"** mientras el loop está vivo, y el tile que se está
capturando lleva un punto rojo **ON AIR** (por arriba del frame congelado se ve el video real).

Los feeds que ya sabemos que están caídos **no se vuelven a intentar** (quedan marcados como
`dead` por 6 h), así que el surf no gasta el decoder con canales muertos.

El ritmo lo decide **`SurfPlanner`** (en `core/nav`, lógica pura sin Android): devuelve cuál es
el próximo tile a capturar y con qué cadencia. Hay **9 tests unitarios** para el planificador.

### Tres bugs que aparecieron y cómo se resolvieron

**a) Cuadro negro en los canales caídos.** `textureView.getBitmap()` devuelve un bitmap
-aunque sea negro- aunque el feed nunca haya renderizado un frame. El mosaico congelaba
negros y el contador decía "12/12 capturados" con tres canales sin imagen. Ahora
`ThumbnailManager` marca `frameRendered` en `onRenderedFirstFrame` (filtrando por
`mediaId`, para no contar frames del canal anterior) y `capture()` devuelve `null` cuando
no hubo frame: el tile muestra el logo y "sin señal".

**b) El mosaico solo funcionaba la primera vez.** `closeMosaic()` liberaba el player de
captura (`mosaic.release()`), pero el `ThumbnailManager` es único por sesión: al abrir el
mosaico de nuevo, el player estaba **muerto** y el log repetía

```
W MessageQueue: java.lang.IllegalStateException: Handler sending message to a Handler on a dead thread
```

La TV se quedaba con "0/16 capturados". Ahora `closeMosaic()` solo **detiene** el player
(`mosaic.stop()`) y el `release()` real ocurre en `MainActivity.onDestroy`.

**c) BACK desde el canal no volvía a la grilla.** `back()` mandaba siempre al player. Ahora
el controller recuerda de dónde salió el canal (`playerBackScreen`) y vuelve a la grilla, a
la búsqueda o al mosaico según corresponda.

### Verificado en la TV

```
MENU / INFO       -> abre "Comparar canales"
(barrido)         -> "13/16 capturados · 3 sin señal": frames reales congelados
OK                -> abre el canal enfocado en pantalla completa
BACK              -> vuelve a la grilla
celular           -> botón "Comparar canales" -> la TV abre el mosaico
2.a apertura      -> vuelve a capturar (el player se reutiliza, ya no queda muerto)
```

**Surf** (mosaico abierto, capturas de pantalla separadas 18 s):

```
5/16 capturados · "· surf" · ON AIR en el tile #30
13/16 capturados · ON AIR en el tile #42   (los tiles se van llenando solos)
14/16 · 1 sin señal · ON AIR en el tile #44
RIGHT    -> el tile #2 queda enfocado y se captura AL INSTANTE (ON AIR en #2)
           el tile enfocado anterior ya tenía un frame NUEVO (refresco a los ~15 s)
BACK     -> vuelve a la grilla y el surf se corta
```

Sin ANR y sin "dead thread" en el log: el surf no rompe el decoder único.

---

## 11 · Salir de la app: BACK doble en la pantalla de canales

### Qué se implementó

`BACK` siempre fue "un paso atrás". Ahora, cuando ya no queda adónde ir (la **grilla** es la
pantalla raíz), el primer BACK **muestra el aviso** y un segundo BACK dentro de 3 s **cierra la
app de verdad** (vuelve al launcher y saca la tarea de recientes).

| Pieza | Qué hace |
|---|---|
| `MainActivity.lastBackAt` + `EXIT_WINDOW_MS = 3_000L` | Ventana de tiempo entre los dos BACK |
| `TvController.back(): Boolean` | Devuelve `false` **sólo en la raíz** (`Screen.GRID`) |
| `TvController.exitHintVisible` + `showExitHint()` / `hideExitHint()` | El aviso, que se oculta solo a los 3 s |
| `MainActivity.closeApp()` | Log `salida por BACK doble`, para el server, libera players, hace `finishAndRemoveTask()` y termina el proceso (`Process.killProcess`) |
| `GridScreen` (Compose) | La pastilla "Presiona BACK otra vez para salir" abajo al centro |

De paso se corrigió un bug de navegación: `playerBackScreen` pasó de `Screen.PLAYER` a
`Screen.GRID`, así que **BACK desde el video vuelve a la grilla** (antes se quedaba en el
player). Y el BACK que llega desde el celular (`{"type":"key","code":4}`) ahora **se reinyecta
como tecla real**, para que el celular pueda hacer el mismo BACK doble.

### El flujo real (Ojo con esto)

```
PLAYER (la app arranca aqui: retoma el ultimo canal)
   BACK  -> GRILLA
   BACK  -> aviso: Presiona BACK otra vez para salir
   BACK  -> la app se cierra (vuelve al launcher)
```

### Verificado en la TV real (26/10/2026)

- ✅ El aviso aparece en la grilla y se oculta solo a los 3 s.
- ✅ El **cierre es real**: vuelve al launcher, `pidof ar.kziptv.debug` queda vacio y el log
  `salida por BACK doble` aparece.
- ✅ Sin crash, sin `dead thread` y sin ANR de la app en el logcat.
- ✅ El BACK reinyectado desde el companion hace el mismo cierre con dos comandos.

---

## 12 · UX: la grilla, el cartel de estado y la pantalla de AJUSTES

### Qué se implementó (26/10/2026)

| Pieza | Qué hace |
|---|---|
| `TvRoot.kt`: `LazyVerticalGrid(state = gridState)` + `LaunchedEffect` que desplaza "lo justo" | la grilla acompaña al foco: si el destino ya está compuesto alcanza con `scrollBy`; si no, calcula el paso de fila con los items visibles y salta las filas que falten |
| `TvController.setStatus(text, sticky = false)` + `statusVisible` | los avisos normales se van solos a los 3,5 s; los errores (`sticky = true`) quedan hasta que llegue otro mensaje |
| `TvRoot.kt`: cartel con recuadro redondeado | el `status` ya no queda pegado: se ve como caja oscura arriba a la izquierda |
| `TvController.startWebServer()` / `stopWebServer()` | el server web **no arranca solo**: se enciende desde AJUSTES o con OK en el emparejamiento (que es lo que muestra el QR) |
| `TvController.openSettings()` + `Screen.SETTINGS` + `Setting` | pantalla AJUSTES con 4 filas: Servidor web (Encendido/Apagado), Emparejar el celular (QR), Zap (todos/favoritos), Video (Ajustar/Rellenar/Ampliar) |
| `MainActivity`: AZUL (186) | abre AJUSTES (antes abría el setup con QR) |
| `TvController.setupBackScreen` | BACK desde el emparejamiento vuelve a AJUSTES (antes saltaba a la grilla) |

El encuadre se aplica en el `PlayerView` con el `resizeMode` de Media3 (FIT, FILL o ZOOM). El
estado del server y el encuadre son **runtime**: se pierden al salir de la app.

### Verificado en la TV real (26/10/2026)

- ✅ Al abrir la app el server está **apagado** (la conexión a `192.168.1.18:8080` se rechaza).
- ✅ AZUL → AJUSTES; "Servidor web" pasa de Apagado a Encendido y muestra la URL.
- ✅ "Emparejar el celular" → setup con QR; con el server apagado avisa "Presiona OK para
  encenderlo" y lo enciende.
- ✅ BACK desde el emparejamiento vuelve a AJUSTES; BACK desde AJUSTES vuelve a la pantalla de la
  que se salió (el reproductor).
- ✅ "Video" cicla Ajustar → Rellenar → Ampliar y el video se ve con el nuevo encuadre.
- ✅ Suite JVM **76 tests verdes** (`testDebugUnitTest`) + **5 tests instrumentados** de Room
  verdes en la TV (`am instrument`).

---

## 13 · Trampa de trabajo: las capturas por ADB cortan la conexión

`adb exec-out screencap` (o un `adb pull` grande) por ADB sobre WiFi deja la conexión colgada y
la sesión se frena; la TV puede quedar sin responder a `adb connect` aunque la app esté
perfecta. Para capturas: `adb shell screencap -p /sdcard/s.png` → `adb pull` → leer el archivo,
y para verificar estado usar `/api/state` (JSON) en vez de una imagen. El detalle está en
[docs/HANDOVER.md](HANDOVER.md) §7.
