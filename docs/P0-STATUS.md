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
| Favoritos | ✅ | tecla amarilla (185) o long-press OK; persistidos en JSON |
| Banner de canal | ✅ | logo + número + nombre + ★ + "Ahora/Luego" (EPG) al cambiar de canal |
| Grilla de canales | ✅ | Compose for TV, chips de categoría, logo por canal, foco visible |
| Setup por QR | ✅ | la TV sirve una web por HTTP; QR con `<url>/pair#<codigo>` + código de 6 dígitos |
| Companion web (celular) | ✅ | Preact + Vite, 19 KB JS: configurar playlist, control remoto, listado, favoritos |
| API / WebSocket | ✅ | `/api/state`, `/api/pair`, `/api/source`, `/api/favorite`, `/api/command`, `WS /ws?token=` — los endpoints que **modifican** la TV exigen el token que devuelve `/api/pair` |
| EPG | 🟡 | 50/206 canales matchean por tvg-id exacto; 46 con guía real (2.474 programas). Ver §3 |
| Comparar canales (mosaico + PiP) | ❌ | fuera de P0 → P1 |
| Persistencia Room / multi-lista / PWA | ❌ | fuera de P0 → P1/P2 |

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
| Estado | `MutableStateFlow` + store JSON (`KzStore`) | sin KSP/Room en P0 (Room llega en P1) |
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

**Qué probar en 5 minutos**

1. La TV arranca en el reproductor (la playlist viene embebida).
2. `CH+` / `CH-` cambian de canal (zapping), el banner aparece 4 s con logo + "Ahora/Luego".
3. `MENU` o tecla **AZUL** → pantalla de setup con **QR** y código de 6 dígitos.
4. Celular: escanear el QR → web del companion → *Configurar* → pegar la URL de la m3u → *Enviar*.
5. La TV cambia la lista al instante (se ve en la grilla).
6. Tecla **ROJA** → zapping solo por favoritos; tecla **AMARILLA** → marca/desmarca favorito.
7. En el celular: *Control* → D-pad virtual → los botones mandan las teclas a la TV por WebSocket.

---

## 6. Qué sigue (P1, en orden de valor)

1. **Mosaico + PiP** (lo único grande que falta del pedido original): miniaturas de N canales +
   PiP con un 2.º decoder.
2. **EPG siempre fresco**: la app baja `epg/ar.xml.gz` del repo en background y cachea en Room.
3. **Room + multi-lista**: playlists propias, guardado de EPG, historial.
4. **Favoritos y perfiles por usuario**, cola de reproducción, "ver después".
5. **Epg de canales locales**: buscar una fuente alternativa por zona (el scraper de cada signal provider).
6. Release firmado + distribución (sideload por QR / Play private track).
