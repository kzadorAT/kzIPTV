# kzIPTV

Reproductor IPTV para **Android TV**: listas m3u (iptv-org y propias) con **setup por QR** desde el celular, zapping con **CH+ / CH−**, favoritos, guía **EPG** y comparación de canales (**mosaico de miniaturas**).

- 📖 Diseño y plan completo: [docs/PLAN.md](docs/PLAN.md)
- 🧪 Estado y aprendizajes: [docs/P0-STATUS.md](docs/P0-STATUS.md)
- Playlist de referencia: <https://iptv-org.github.io/iptv/countries/ar.m3u>

## Estructura

```
kzIPTV/
  app-tv/                     # app Android TV (Kotlin + Compose for TV + Media3 + Ktor embebido)
  companion/                  # web del celular (Vite + Preact) → se empaqueta en los assets del APK
  tools/epg/                  # pipeline EPG: XMLTV general → XMLTV recortado a la playlist
  .github/workflows/epg.yml   # pipeline semanal (GitHub Actions, gratis)
  docs/PLAN.md                # diseño y plan
```

## Qué hay hoy (P0 + P1)

### En la TV

- **Zapping** con CH+ / CH− (keycodes 166/167) y con los botones del control remoto.
- **Canales lógicos agrupados**: 206 feeds → **201 señales**. La clave es el `tvg-id` base
  (`Base.cc@Qualifier` → `Base.cc`); si no hay tvg-id útil, se agrupa por nombre normalizado.
- **Selector de calidad**: long-press de OK abre las variantes del canal (1080p / 720p /
  regional) y muestra la resolución **real** medida, no la que promete el nombre del playlist.
- **Fallback automático**: si un feed muere, se prueban las otras variantes del mismo canal
  antes de saltar al siguiente.
- **Búsqueda**: tecla VERDE. El texto se escribe con el **teclado del sistema** (Gboard TV) o
  desde el celular; los resultados salen en pantalla y se navegan con el D-pad.
- **Número directo**: teclas **0-9** + OK saltan a un canal por número (ej. `12` → Catamarca TV).
- **Favoritos** (tecla amarilla), **modo zap solo-favoritos** (tecla roja).
- **EPG** con ahora/siguiente en el banner y en el listado.
- **Comparar canales** (tecla **MENU** o **INFO**, o el botón *Comparar canales* del celular):
  grilla de **16 tiles** con un frame congelado de cada canal. Cada tile se reproduce unos
  segundos, se congela y se sigue con el siguiente, porque la TV tiene **un solo decoder de
  video por hardware**. Un canal caído muestra su logo y "sin señal" en vez de un cuadro negro.
- **Surf**: los tiles se refrescan solos — el que tenés enfocado se vuelve a capturar cada
  15 s y la página entera se renueva cada ~2 min. El tile en captura lleva un punto rojo
  **ON AIR**.
- **Salvapantallas**: la pantalla no se apaga durante la reproducción (`FLAG_KEEP_SCREEN_ON`).
- **Setup por QR**: la TV levanta un server embebido, el celular entra por QR con código de 6
  dígitos y queda emparejado con un token.

### Navegación determinista

El foco de **Compose** no engancha en la TV real (ver [docs/P0-STATUS.md](docs/P0-STATUS.md) §8).
Por eso el `TvController` es el dueño del foco: el D-pad, el control remoto físico y el
companion usan **exactamente el mismo código**, y el estado del foco es observable por la API.

### En el celular (companion web)

- Emparejar por QR o por código.
- Mandar playlists (URL o archivo .m3u).
- Control remoto completo con D-pad virtual.
- Listado de canales con EPG y cantidad de calidades.
- Favoritos, buscar y elegir canal.

### Lo que falta (P2 en adelante)

- EPG siempre fresco (descarga periódica + cache en disco).
- PiP real: **Android TV no tiene PiP del sistema** (solo enCelular/tablet). La comparación
  se resuelve con el mosaico de miniaturas.
- Room + multi-lista, historial, perfiles.
- Release firmado + R8 + baseline profile.

## Build local

Requisitos: **JDK 17+** (probado con Temurin 21), **Android SDK** con platform 35 y build-tools 35.0.0, Node 20+ para el companion.

```sh
# app Android TV
cd app-tv
gradle testDebugUnitTest assembleDebug      # o ./gradlew si tenés el wrapper generado
# APK: app/build/outputs/apk/debug/app-debug.apk

# companion web (se copia a los assets del APK)
cd companion
npm install && npm run build
```

Instalar en una TV por ADB:

```sh
adb connect <IP-DE-LA-TV>:5555
adb install -r app-tv/app/build/outputs/apk/debug/app-debug.apk
```

## Decisiones vigentes

| Tema | Decisión |
|---|---|
| Plataforma | Android TV / Google TV (nativa, Kotlin + Compose for TV + Media3) |
| Sin servidor aparte | la app lleva el server embebido (Ktor) y sirve la web de setup/control por QR |
| EPG | pipeline semanal gratis: GitHub Action con utilidades iptv-org/epg → XMLTV recortado a la playlist |
| Comparar canales | mosaico de miniaturas on-device (16 tiles). **No hay PiP en Android TV** y el decoder de hardware es único: por eso se congela un frame por canal en vez de transmitir varios streams |
| Foco en TV | navegación determinista en el `TvController` (el foco de Compose no engancha) |
| Teclado | el del sistema (Gboard TV): sin teclado propio que mantener |
| Estado de salud por feed | `ok`/`dead`/`slow` con TTL de 6 h + fallback entre variantes |
