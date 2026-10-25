# Pipeline EPG (kzIPTV)

Genera un XMLTV recortado a los canales de una playlist m3u, listo para empaquetar en la app.

## Flujo

1. Se clonea https://github.com/iptv-org/epg (utilidades oficiales de iptv-org).
2. `npm run grab --- --sites=<sitios>` produce un XMLTV general.
3. Este script matchea los canales del XMLTV contra los tvg-id de la playlist y escribe
   `epg/ar.xml.gz` (más `epg/report.json` con la cobertura real).

## Uso local

```sh
node tools/epg/scripts/build-epg.mjs \
  --playlist app-tv/app/src/test/resources/ar.m3u \
  --guide .tools/epg-src/guide.xml \
  --out epg/ar.xml.gz
```

La app lee `assets/epg/ar.xml.gz` al arrancar (ver `TvController.loadBundledEpg`).

## Cobertura

El informe `report.json` dice cuántos canales de la playlist quedaron con guía:
exactos (tvg-id igual), normalizados (tvg-id sin `@SD`/`@HD`) o por nombre.
