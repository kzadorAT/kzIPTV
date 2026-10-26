# HANDOVER - kzIPTV (para retomar en un chat nuevo)

> Fecha de corte: **26/10/2026** (Argentina) - Rama: `main` - APK debug: `ar.kziptv.debug`
>
> **Este archivo es el punto de partida del proximo chat.** Leelo entero antes de tocar nada:
> tiene el estado real, las decisiones tomadas, los comandos exactos para compilar/probar/instalar,
> los limites fisicos de la TV y la lista de lo que falta.

---

## 1 - Que es el proyecto

**kzIPTV** = reproductor IPTV nativo para **Android TV / Google TV**, para TVs propias.
- Se instala un APK en la TV; **no hay servidor externo ni maquina siempre encendida**.
- La **TV misma** levanta un server HTTP embebido (Ktor) que sirve la web del celular (companion),
  asi que el setup se hace **escaneando un QR con el celular** y nunca con un teclado en la TV.
- Features pedidas: zapping con **CH+ / CH-**, favoritos, lista completa por categorias,
  **EPG**, **comparar canales**, y que sea **facil de navegar con el control remoto**.

### Decisiones de producto (vigentes, no reabrir sin motivo)

| Tema | Decision | Por que |
|---|---|---|
| Plataforma | Solo Android TV / Google TV, app nativa (Kotlin + Compose for TV + Media3) | Es lo que tienen las TVs del usuario |
| Sin servidor aparte | El server va **embebido en la app** (Ktor/Netty en hilo daemon) | No hay maquina que dejar prendida |
| Setup | **QR** + codigo de 6 digitos, el celular manda la playlist | La TV no tiene teclado comodo |
| Teclado de busqueda | El **IME del sistema** (Gboard TV), no uno propio | Se-Forza menos codigo y anda mejor |
| Comparar canales | **Mosaico de miniaturas**: un frame congelado por canal | No hay PiP en Android TV y hay **un solo decoder** (ver seccion 4) |
| Surf | Los tiles del mosaico se **refrescan solos** (15 s el enfocado, ~2 min la pagina) | La grilla no debe quedar vieja |
| Persistencia | **Room 2.7.2** (`KzStore` sobre `KzDatabase`) + import 1 vez del JSON legado | Multi-lista, historial y cache EPG con transacciones |
| EPG | Asset **bundled** `epg/ar.xml.gz` + pipeline semanal en GitHub Actions | El repo `iptv-org/epg` scrapea mi.tv (el scraper de DIRECTV esta roto) |

---

## 2 - Estado actual: que YA FUNCIONA (verificado en la TV real)

TV de pruebas: **TCL Smart TV Pro**, Android 11 (API 30), 1920x1080 @ 320 dpi (960x540 dp),
IP `192.168.1.18:5555` (ADB por WiFi; cambio desde `192.168.1.20` el 26/10/2026).

- Zapping con CH+ / CH- (keycodes 166/167) y con los botones del control.
- **Canales logicos agrupados**: 206 feeds -> **201 senales** (`ChannelGrouper`: tvg-id base
  `Base.cc@Qualifier` -> `Base.cc`; si no hay tvg-id util, nombre normalizado).
- **Selector de calidad** con long-press de OK, mostrando la resolucion **real medida**.
- **Fallback automatico** entre variantes del mismo canal + TTL de 6 h para feeds muertos.
- **Busqueda** (tecla VERDE) con IME del sistema y resultados navegables con D-pad.
- **Numero directo** 0-9 + OK.
- **Favoritos** (AMARILLO) y **zap solo-favoritos** (ROJO).
- **EPG** ahora/siguiente (46 canales con guia real, 2.474 programas).
- **Setup por QR** + **companion web** completo (emparejar, mandar playlist, control remoto, lista).
- **Mosaico de 16 tiles** con frame congelado por canal, sin senal para los caidos, y **surf**.
- **Salvapantallas** (`FLAG_KEEP_SCREEN_ON`) durante reproduccion y en el mosaico.
- **BACK doble en la grilla = salir de la app** (ver seccion 5; verificado en la TV real).
- **76 tests unitarios** verdes (`testDebugUnitTest`) + **5 tests instrumentados** en la TV real
  (import del JSON legado, migracion Room v1→v3, cache EPG, historial y escrituras concurrentes).
- **Persistencia en Room** (esquema v3) con multi-lista, historial y cache EPG (ver seccion 2.1).

### 2.1 Persistencia en Room (P1, verificado en la TV real)

- **Room 2.7.2 + KSP 2.0.21-1.0.28**. `KzStore` queda como fachada (`StateFlow<PersistedState>`),
  pero las escrituras son `suspend`, se serializan con un `Mutex` y se aplican en `Dispatchers.IO`
  dentro de `database.withTransaction`.
- **Esquema v3** (`app-tv/app/schemas/`): `playlists` (incluye el M3U que sube el companion),
  `favorites`, `device_tokens`, `app_settings`, `quality_preferences`, `variant_states`,
  `epg_programs` (+ `epg_cache_meta`) y `watch_history` (top 500 reproducciones).
- **Migracion del JSON legado**: se importa una sola vez (solo si no existe la fila de settings) y
  en una transaccion; el archivo pasa a `kz-state.json.room-backup`. Un JSON invalido queda como
  `.invalid` y uno sobrante con Room ya cargado como `.stale-room-backup`.
- **Migraciones Room**: `1→2` (columna `content` + tablas de EPG/historial) y `2→3` (EPG con
  `ordinal` para conservar el orden del XMLTV). Los esquemas exportados por KSP alimentan el
  `MigrationTestHelper` que corre en la TV.
- **Cache EPG**: se hashea el XMLTV bundled (SHA-256); si el fingerprint coincide con el guardado,
  se leen los 2.474 programas de Room sin parsear. Medido en la TV: **10,5 s con parseo → 5,5 s con
  cache** (el log dice `EPG listo en ... (cache)`).
- **Multi-lista**: la tabla `playlists` ya soporta varias fuentes; el contenido de una playlist
  subida por el celular se guarda en Room y sobrevive al reinicio, y el merge del arranque no pisa
  una lista agregada mientras se cargaba la red.
- **Verificado en la TV** (TCL Smart TV Pro): la base paso de v1 a v3 sin perder favoritos, tokens,
  codigo de emparejamiento, preferencias de calidad ni resoluciones medidas; el token guardado en
  Room sigue sirviendo (`POST /api/favorite` → 200, token invalido → 401).

### Bugs ya resueltos (no reintroducirlos)

1. **Pantalla negra al arrancar** -> era un feed 403 (`Cartoon Network Latin America`), no la UI.
   Ahora: error visible + salto automatico al siguiente + fallback entre variantes.
2. **El foco de Compose no engancha en la TV real** -> se adopto **navegacion determinista**
   (`TvController` es el dueno del foco; ver seccion 4).
3. **ANR al parsear el EPG** -> regex con `DOT_MATCHES_ALL` sobre 2 MB. Ahora escaner lineal + `Dispatchers.IO`.
4. **HTTP 500 en `/api/command`** -> Media3 exige el hilo que creo el player. Ahora todo pasa por
   `controller.onMain { }`.
5. **Mosaico solo funcionaba la primera vez** -> `closeMosaic()` liberaba el player de captura.
   Ahora `mosaic.stop()`; el `release()` real ocurre en `MainActivity.onDestroy`.
6. **Cuadros negros en el mosaico** -> `getBitmap()` devuelve negro aunque no haya frame.
   Ahora se trackea `frameRendered` con `onRenderedFirstFrame` filtrado por `mediaId`.
7. **BACK desde el player no volvia a la grilla** -> ahora `playerBackScreen` recuerda el origen.

---

## 3 - Estructura del repo

```
kzIPTV/
|- app-tv/                                  # app Android TV (Kotlin + Compose for TV)
|  `- app/src/main/
|     |- java/ar/kziptv/
|     |  |- MainActivity.kt                 # Activity + dispatchKeyEvent (todas las teclas)
|     |  |- core/
|     |  |  |- catalog/ChannelCatalog.kt    # indice de grupos, categorias, resolve(id)
|     |  |  |- catalog/ChannelGrouper.kt    # feeds -> canal logico (tvg-id base / nombre limpio)
|     |  |  |- epg/EpgParser.kt             # XMLTV lineal + EpgIndex.nowNextByTvgId()
|     |  |  |- epg/EpgCache.kt              # fingerprint SHA-256 del XMLTV (invalida el cache)
|     |  |  |- model/Models.kt              # Channel, ChannelVariant, ChannelGroup, EpgProgram
|     |  |  |- nav/GridNavigator.kt         # matematica de foco (fila/columna/chips)
|     |  |  |- nav/NumberEntry.kt           # buffer 0-9 + busqueda por texto
|     |  |  |- nav/RetryPolicy.kt           # isTransient(msg): reintentar vs marcar muerto
|     |  |  |- nav/SurfPlanner.kt           # planificador del surf (puro, testeado)
|     |  |  `- parse/M3uParser.kt           # m3u/m3u8 (EXTVLCOPT, KODIPROP, comas)
|     |  |- data/KzStore.kt                 # fachada de persistencia sobre Room (StateFlow + Mutex)
|     |  |- data/PlaylistMerge.kt           # merge puro de playlists (no pisa altas del companion)
|     |  |- data/room/KzDatabase.kt         # Room v3 + migraciones 1→2 y 2→3
|     |  |- data/room/KzEntities.kt         # playlists, favoritos, tokens, settings, EPG, historial
|     |  |- data/room/KzDao.kt              # DAO suspend (queries + upserts)
|     |  |- data/room/RoomStateMapper.kt    # PersistedState <-> filas de Room
|     |  |- data/SourceRepository.kt        # descarga playlists (URL o contenido)
|     |  |- input/RemoteKeys.kt             # keycodes del control remoto
|     |  |- playback/PlayerManager.kt       # ExoPlayer principal (headers por canal)
|     |  |- playback/ThumbnailManager.kt    # ExoPlayer de miniaturas + TextureView.getBitmap()
|     |  |- server/EmbeddedServer.kt        # Ktor: /api/* + /ws + companion estatico
|     |  |- ui/TvController.kt              # cerebro: estado, foco, screens, mosaico, surf
|     |  `- ui/TvRoot.kt                    # Compose for TV: Player/Grid/Search/Quality/Setup/Mosaic
|     `- assets/
|        |- playlist/ar.m3u                 # 206 feeds embebidos
|        |- epg/ar.xml.gz                   # 2.474 programas (AGP lo descomprime en el APK)
|        `- companion/                      # build del web del celular (gitignored)
|- companion/                               # web del celular (Vite 5 + Preact 10 + TS)
|- tools/epg/scripts/                      # fetch-mitv-epg.mjs, build-epg.mjs, measure-epg-sources.mjs
|- .github/workflows/epg.yml                # lunes 06:23 UTC: regenera epg/ar.xml.gz
|- docs/PLAN.md                             # diseno v1
|- docs/P0-STATUS.md                        # estado real + aprendizajes de la TV (seccion 10 = mosaico)
`- docs/HANDOVER.md                         # ESTE archivo
```

### Donde tocar para cada cosa

| Quiero cambiar... | Archivo |
|---|---|
| Comportamiento de una tecla | `MainActivity.dispatchKeyEvent` + `input/RemoteKeys.kt` |
| Que pantalla se ve / foco / mosaico / surf | `ui/TvController.kt` |
| Como se ve | `ui/TvRoot.kt` |
| Reglas de agrupacion de canales | `core/catalog/ChannelGrouper.kt` |
| Ritmo del surf | `core/nav/SurfPlanner.kt` (+ `SurfPlannerTest.kt`) |
| API del celular | `server/EmbeddedServer.kt` + `companion/src/app.tsx` |
| Persistencia | `data/KzStore.kt` + `data/room/` (esquema, DAO, migraciones) |

---

## 4 - Limites fisicos y trampas de la TV (no pelees contra esto a fuerza)

1. **Un solo decoder de video por hardware.**
   `OMX.realtek.video.decoder` es **una unica instancia**. Con 4 players, los que no entran
   caen a decode por software -> **86 % de CPU y ANR**. Por eso el mosaico congela frames
   (1 player + 1 `TextureView`) en vez de transmitir varios streams.
2. **Android TV no tiene PiP del sistema** (`pm list features` solo declara television/leanback).
   Comparar canales = mosaico de frames. No se puede PiP en vivo.
3. **El foco nativo de Compose no engancha en la TV.**
   `Modifier.clickable` no crea focus target y `FocusRequester` en una grilla lazy nunca queda
   activo. Solucion adoptada: **navegacion determinista** (`TvController` + `GridNavigator`).
   La UI solo pinta el borde y hace scroll.
4. **Media3 exige el hilo que creo el player.** Todo lo que toque `ExoPlayer` va por
   `controller.onMain { }` (Ktor atiende en hilos de Netty).
5. **`adb screencap` sale negro en el video** (SurfaceView). Para verificar reproduccion:
   `/api/state` -> `playbackState`, `videoWidth/videoHeight`.
6. **AGP descomprime los assets `.gz`** al empaquetar: en el APK queda `assets/epg/ar.xml`.
   El codigo prueba las dos variantes y detecta el gzip por magic bytes.
7. **El parser de m3u corta el nombre en la PRIMERA coma fuera de comillas** y lo toma de la
   linea `#EXTINF` (no de la URL).

---

## 5 - BACK doble para salir (implementado y verificado en la TV)

**Pedido**: cerrar la aplicacion presionando BACK 2 veces, cuando estamos en el inicio de la app.

**Implementado y comiteado** (71 tests verdes, build OK):

- `MainActivity.kt`
  - `lastBackAt` + `closing` + `EXIT_WINDOW_MS = 3_000L`.
  - En `RemoteKeys.BACK` (ACTION_UP): si hay buffer numerico -> lo cancela; si el IME esta abierto ->
    lo cierra; si `controller.back()` devuelve `false` (estamos en la **raiz**) -> aplica la
    ventana de 3 s: 1er BACK muestra el aviso, 2do BACK dentro de la ventana **cierra la app**.
  - `closeApp()`: log `salida por BACK doble`, `server.stop()`, `controller.release()`,
    `player.release()`, `finishAndRemoveTask()` y `Process.killProcess(myPid())`.
    El ultimo paso evita que Android deje el proceso cacheado (y ~100 MB de RAM) despues de salir.
  - `onDestroy()` solo libera recursos si `!closing` (no se suelta dos veces).
- `TvController.kt`
  - `back()` ahora devuelve `Boolean`: `false` **solo en `Screen.GRID`** (la raiz).
    SEARCH->GRID, MOSAIC->cierra mosaico, QUALITY->PLAYER, SETUP->GRID, PLAYER->`playerBackScreen`.
  - `playerBackScreen` **cambio de `Screen.PLAYER` a `Screen.GRID`** (antes el BACK en el player
    no volvia a la grilla).
  - `exitHintVisible: MutableStateFlow<Boolean>` + `showExitHint()` / `hideExitHint()`
    (el aviso se oculta solo a los 3 s).
- `TvRoot.kt`: `GridScreen` envuelto en `Box` + pastilla `Presiona BACK otra vez para salir`
  abajo al centro.
- `EmbeddedServer.kt`: el comando `{"type":"key","code":4}` del celular **ahora se reinyecta**
  como tecla real (`MainActivity.injectKey`) para que el celular pueda hacer el mismo BACK doble.

**Verificado en la TV real el 26/10/2026** (TCL Smart TV Pro, IP `192.168.1.18`):
1. App abierta en **PLAYER** (restaura el ultimo canal) -> BACK -> **GRILLA**.
2. En la grilla, BACK -> aparece la pastilla; BACK dentro de 3 s -> **la app se cierra de verdad**:
   vuelve al launcher, `pidof ar.kziptv.debug` queda vacio y aparece el log `salida por BACK doble`.
3. La pastilla se oculta sola despues de 3 s (comparado con el arbol de accesibilidad de la pantalla).
4. Sin crash, sin `dead thread` y sin ANR de `ar.kziptv.debug` en el logcat.
5. El **companion** tambien funciona: `POST /api/command {"type":"key","code":4}` dos veces
   en la grilla cierra la app de la misma manera.

**Decision de diseno**: se mantiene el flujo actual. Desde PLAYER hacen falta **3 BACK**
(player -> grilla, grilla -> aviso, grilla -> salir), pero **desde la pantalla de inicio**
(el pedido original) son **2 BACK**. No se muestra el aviso de salida en el player para no
cerrar la app por accidente mientras se mira television.

**Commits**: `919b416` implemento el flujo completo; `989fe6a` agrega la terminacion real
del proceso, que si no dejaba el proceso Android cacheado.

---

## 6 - Comandos exactos (copiar y pegar)

### Build + tests (Windows, PowerShell)

```powershell
$env:JAVA_HOME='E:\Repos\kzIPTV\.tools\jdk\jdk-21.0.12.1+1'   # JDK 21 portable
$env:ANDROID_HOME='C:\Users\fabri\AppData\Local\Android\Sdk'
& 'E:\Repos\kzIPTV\.tools\gradle-8.11.1\bin\gradle.bat' -p 'E:\Repos\kzIPTV\app-tv' testDebugUnitTest assembleDebug
```

- Gradle 8.11.1 en `.tools/gradle-8.11.1` (no hay wrapper funcional: usar el `.bat` de arriba).
- AGP 8.7.3 - Kotlin 2.0.21 - compile/target SDK 35 - minSdk 23 - JVM 17.
- APK: `app-tv/app/build/outputs/apk/debug/app-debug.apk`.
- Tests: `app-tv/app/build/test-results/testDebugUnitTest/` (9 clases, 71 tests).

### Companion web

```powershell
cd companion; npm install; npm run build
# el build va a companion/dist y hay que copiarlo a los assets del APK:
Copy-Item -Recurse -Force companion\dist\* app-tv\app\src\main\assets\companion\
```

### Instalar / probar en la TV

```powershell
$adb='C:\Users\fabri\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb connect 192.168.1.18:5555
& $adb -s 192.168.1.18:5555 install -r app-tv\app\build\outputs\apk\debug\app-debug.apk
& $adb -s 192.168.1.18:5555 shell am force-stop ar.kziptv.debug
& $adb -s 192.168.1.18:5555 shell am start -n ar.kziptv.debug/ar.kziptv.MainActivity
```

- **Paquete debug**: `ar.kziptv.debug` (el de release seria `ar.kziptv`).
- Servidor embebido: puerto **8080** (si esta ocupado prueba 8081-8090).
- URL en la TV: `http://192.168.1.18:8080/` (la IP de la PC es `192.168.1.17`).
- Token de la sesion anterior (puede haber expirado): `7462d1df-c781-4964-bc3b-63693c2d56ff`.
- Log: `& $adb -s ... logcat -s KzIPTV:D KzStartup:D`. ANRs: `& $adb -s ... shell dumpsys dropbox --print`.

### Teclas (para probar por ADB: `input keyevent <code>`)

| Tecla | Code | Accion |
|---|---|---|
| CH+ / CH- | 166 / 167 | zapping |
| ROJA / VERDE / AMARILLA / AZUL | 183 / 184 / 185 / 186 | zap favoritos / buscar / favorito / **AJUSTES** (el QR sale de Ajustes) |
| MENU / INFO | 82 / 172 | **mosaico** (comparar canales) |
| OK | 23 | reproducir (largo >600 ms = selector de calidad) |
| BACK | 4 | un paso atras; **doble en la grilla = salir** |
| 0-9 / NUMPAD_0-9 | 7-16 / 96-105 | numero directo |
| D-pad | 19/20/21/22 | navegacion |

---

## 7 - TRAMPA OPERATIVA: las capturas cortan la conexion ADB

**Cada `adb exec-out screencap` (o un `adb pull` grande) por ADB-WiFi deja la conexion
colgada/muerta y la sesion de trabajo se frena.** Es lo que paso al final de la sesion
anterior: la TV dejo de responder a `adb connect` (timeout 10060) y `ping 192.168.1.18` dio
false. Cuando la TV parece muerta, **no asumas que se rompio la app**: casi seguro es la
conexion ADB por WiFi.

Como trabajar alrededor:

1. **Reconectar primero**: `adb kill-server; adb start-server; adb connect 192.168.1.18:5555`.
   Si la TV cambio de IP, hay que sacar la IP nueva de la TV (Configuracion -> Red).
2. **Capturar en pasos separados y chicos**:
   `adb shell screencap -p /sdcard/s.png` -> `adb pull /sdcard/s.png .tools/s.png` -> leer la imagen.
   (No volcar binarios por stdout en una sola llamada.)
3. **Preferir `/api/state`** para verificar estado (JSON, sin captura):
   `Invoke-RestMethod http://192.168.1.18:8080/api/state`.
4. **Alternativa sin ADB**: la web del companion en `http://192.168.1.18:8080/` muestra el estado
   en vivo (Chrome DevTools puede abrirla).
5. Si la TV quedo colgada por una captura pesada: `adb shell am force-stop ar.kziptv.debug` y
   relanzar; y **no** dejar `screencap` corriendo en loop.

---

## 8 - API del companion (la TV la sirve)

- `GET /api/state` -> `StateDto` (channels, focusedId, playbackState, screen, etc.). **Sin token.**
- `GET /api/qr.svg` -> QR con `<url>/pair#<codigo>` (pesa ~1.5 MB: backlog).
- `POST /api/pair` `{code}` -> `{ok, token}` (el token se guarda en la TV).
- `POST /api/source` `{url|content, name, token}` -> cambia la playlist.
- `POST /api/favorite` `{channelId, enabled, token}`.
- `POST /api/command` `{type, ...}` -> `key, zap, play, variant, quality, favorite, text, search, category, grid, mosaic`.
- `WS /ws?token=` -> estado por push + comandos.

**Todos los endpoints que modifican exigen el token** (401 si no).

---

## 9 - Lo que falta (orden de valor)

### P1 (cerrar el MVP)

1. ~~**Room** en vez del store JSON (multi-lista, historial, EPG cacheado).~~ **Hecho (26/10/2026):**
   esquema v3 con migraciones 1→2→3, import del JSON legado con backup, cache EPG (2.474
   programas) e historial de reproducción. Ver seccion 2.1. Falta solo el selector de lista en la UI.
2. ~~**Arreglar el scroll de la grilla**~~ **Hecho (26/10/2026):** el `LazyVerticalGrid` ahora
   recibe `state = gridState` y el scroll desplaza lo justo (calcula el paso de fila con los items
   visibles). Verificado en la TV: el foco ya no se sale de la pantalla. Commit `5de8a5f`.
3. **Refresh de playlist con diff** (preservar favoritos/ocultos/numeracion por `tvg-id`).
4. **EPG fresco**: la app baja `epg/ar.xml.gz` del repo (raw.githubusercontent) + cache en disco,
   parseo incremental (hoy parsea 2 MB en cada arranque: ~7 s de CPU).
5. **Multi-lista** y selector de lista en la TV.
6. **Ver despues** / cola de reproduccion.
7. **Release firmado + R8 + baselineProfile** (el arranque en debug es 5,75 s; en release baja).
8. Pre-calentar DNS/TLS del canal siguiente (hoy el zapping tarda 0,8-2,5 s).

### Observaciones del usuario (26/10/2026) — triaje

| Qué se reporta | Estado | Dónde | Qué hacer |
|---|---|---|---|
| Grid: al seguir bajando la pantalla queda estática | **Bug — hecho** | `TvRoot.kt`: al `LazyVerticalGrid` no se le pasa `state = gridState` | **Hecho (26/10):** `state = gridState` + scroll "lo justo"; verificado en la TV (`5de8a5f`) |
| Guía lineal de EPG (canal actual visible arriba, también en grilla) | **Nuevo** | la grilla EPG completa ya estaba en P2; la versión lineal no | diseñar el ticker horizontal (P2); se puede construir con el EPG bundled actual |
| Cambiar audio / subtítulos / resolución | **Parcial** | PLAN §8; la resolución ya existe (selector de calidad) | medido: 0 pistas de audio y 0 subtítulos en los streams actuales → solo cuando haya playlist multi-audio |
| Menú de configuraciones generales | **Contemplado — hecho** | PLAN §3 y §8 | **Hecho (26/10):** pantalla AJUSTES con 4 filas (Servidor web / Emparejar el celular / Zap / Video), tecla AZUL (`f17ae64`) |
| El server web debería estar apagado salvo que se pida | **Contemplado — hecho** | PLAN §3 ("server apagable desde Ajustes") | **Hecho (26/10):** el server ya no arranca solo; se enciende/apaga desde Ajustes (o con OK en el emparejamiento). Es runtime: al salir de la app vuelve a apagarse |
| El cartel "201 canales · 2474 programas EPG" queda pegado | **No es debug — hecho** | es el `status` persistente del reproductor (`TvRoot.kt`) | **Hecho (26/10):** `setStatus(text, sticky)` con auto-ocultamiento a los 3,5 s y recuadro redondeado; los errores quedan fijos (`5de8a5f`) |

Detalle y reglas de esta lista en `docs/PLAN.md` §15.

Lo que queda **pendiente** de esta lista: la guía lineal de EPG (P2) y el selector de audio/subtítulos
(solo tiene sentido con una playlist multi-audio: 0 pistas medidas en 24 streams). El encuadre del
video (Ajustar/Rellenar/Ampliar) y el estado del server son **runtime**: se pierden al salir de la app.

### P2

9. **Grilla completa de EPG** (filas = canales, timeline, linea de ahora).
10. Celular como **control remoto completo** (hoy parcial).
11. Parental / ocultos con PIN, perfiles, Favoritos compartidos entre TVs.

### Backlog tecnico

- `/api/qr.svg` pesa 1.5 MB -> QR vectorial mas chico.
- Presupuesto de decoders documentado para mosaico (720p en miniatura, no 1080p).
- Reciclado de imagenes en la grilla (Coil ya lo hace) + `LazyGrid` con keys estables.

---

## 10 - Reglas para el proximo chat

1. **Medir antes de optimizar** y **probar en la TV real**, no en el emulador.
2. **Nunca parsear ni descargar en el main thread**; el server Ktor va en hilo daemon.
3. **Un solo ExoPlayer reutilizado** para reproduccion y **uno solo** para miniaturas.
4. **Logica de navegacion/planificacion en `core/nav`** (puro, testeado) y no en el controller.
5. Cada feature que cambie el comportamiento de la TV: **tests unitarios + verificacion en la TV**.
6. Commits chicos con mensaje en espanol: `feat(p1): ...`, `fix(p0): ...`.
7. **No subir capturas por stdout**: usar el flujo de la seccion 7.

---

## 11 - Historial de commits (para ubicarse)

```
f17ae64 feat(p1): pantalla de ajustes con server web apagable
5de8a5f fix(p1): la grilla acompana al foco y el cartel de estado se va solo
59962ff docs(p1): triar las observaciones de UX y anotarlas en el backlog
2b2d775 test(p1): cubrir la migracion a Room y el cache EPG
3cb838a feat(p1): migrar la persistencia a Room
989fe6a fix(p1): terminar el proceso al salir con BACK doble
919b416 feat(p1): BACK doble en la raiz cierra la app + handover para el proximo chat
c4e736f feat(p1): surf en el mosaico - los tiles se refrescan solos
d8c3ae6 feat(p1): comparar canales con mosaico de miniaturas
14c3577 feat(p1): busqueda con IME del sistema, numero directo y fix del bucle de reintentos
fc5cbc9 feat(p1): navegacion determinista del D-pad, agrupacion de canales y selector de calidad
1bf24b5 fix(p0): la pantalla negra era un feed muerto (403), ahora se ve el error y salta solo
b7ec21e fix(p0): ANR del parser EPG, hilo del player, auth por token y setup en dos columnas
324191f fix(epg): leer el asset tanto .gz como XML descomprimido por AGP
3cb14b2 feat(p0): app Android TV funcional con zapping, EPG y setup por QR
```

Al corte, **todo comiteado y verificado en la TV**: la salida por BACK doble, el BACK del
celular reinyectado como tecla real, la terminacion real del proceso, la migracion de la
persistencia a Room (esquema v3, con cache EPG e historial), el scroll de la grilla, el cartel
de estado transitorio y la pantalla de AJUSTES (server web apagable + QR + encuadre del video).
