# kzIPTV

Reproductor IPTV para **Android TV**: listas m3u (iptv-org y propias) con **setup por QR** desde el celular, zapping con **CH+ / CH−**, favoritos, guía **EPG** y comparación de canales (**mosaico + PiP**).

- 📖 Diseño y plan completo: [docs/PLAN.md](docs/PLAN.md)
- 🧪 Estado de la P0: [docs/P0-STATUS.md](docs/P0-STATUS.md)
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

## Qué hay en la P0 (spike)

- Parser M3U (tvg-id / tvg-logo / group-title / EXTVLCOPT) con tests contra la playlist real de 206 canales.
- Reproductor ExoPlayer con headers por canal y zapping con CH+ / CH− (keycodes 166/167).
- Banner de canal con ahora/siguiente (EPG) y favoritos (long-press OK / tecla amarilla).
- Grilla de canales con logos y categorías.
- **Server HTTP/WebSocket embebido en la app** (Ktor) que sirve la web del celular + QR de emparejamiento.
- Companion web (celular): configurar playlists, control remoto, listado y favoritos.

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
| Comparar canales | mosaico de miniaturas on-device + PiP con 2.º decodificador |
