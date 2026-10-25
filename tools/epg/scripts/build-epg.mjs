/**
 * Convierte un XMLTV general en un XMLTV recortado a los canales de una playlist m3u.
 *
 *   node scripts/build-epg.mjs --playlist fixtures/ar.m3u --guide guide.xml --out epg/ar.xml.gz
 *
 * Match: 1) tvg-id exacto  2) tvg-id normalizado (sin @SD/@HD, sin signos)
 *        3) nombre normalizado. El id final siempre es el tvg-id de la playlist.
 */
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";

const args = {};
process.argv.slice(2).forEach((arg, i, all) => {
  if (arg.startsWith("--")) args[arg.slice(2)] = all[i + 1];
});

const playlistPath = args.playlist;
const guidePath = args.guide;
const outPath = args.out || "epg/ar.xml.gz";

if (!playlistPath || !guidePath) {
  console.error("uso: node scripts/build-epg.mjs --playlist <m3u> --guide <xml> --out <gz>");
  process.exit(1);
}

const norm = (s) => (s || "").toLowerCase().replace(/[^a-z0-9]/g, "");
const normName = (s) =>
  (s || "")
    .toLowerCase()
    .replace(/[^a-z0-9 ]/g, "")
    .replace(/\b(sd|hd|fhd|uhd|1080p|720p|480p|tv|canal|channel)\b/g, "")
    .replace(/\s+/g, " ")
    .trim();

// ---- playlist ----
const playlist = fs.readFileSync(playlistPath, "utf8");
const channels = [];
const re = /#EXTINF:([^\n]*)\n([^\n]+)/g;
let m;
while ((m = re.exec(playlist)) !== null) {
  const attrs = {};
  const attrRe = /([A-Za-z0-9_-]+)="([^"]*)"/g;
  let a;
  while ((a = attrRe.exec(m[1])) !== null) attrs[a[1]] = a[2];
  const q = m[1].lastIndexOf('"');
  const tail = q >= 0 ? m[1].slice(q + 1) : m[1];
  const c = tail.indexOf(",");
  const name = (c >= 0 ? tail.slice(c + 1) : tail).trim() || attrs["tvg-name"] || "";
  channels.push({
    id: attrs["tvg-id"] || "",
    name: name,
    logo: attrs["tvg-logo"] || "",
    group: attrs["group-title"] || ""
  });
}

// ---- guide ----
const guide = fs.readFileSync(guidePath, "utf8");
const guideChannels = new Map(); // id -> display-name
const chanRe = /<channel\s+id="([^"]+)"[^>]*>([\s\S]*?)<\/channel>/g;
while ((m = chanRe.exec(guide)) !== null) {
  const id = m[1];
  const body = m[2];
  const dn = /<display-name[^>]*>([\s\S]*?)<\/display-name>/.exec(body);
  guideChannels.set(id, dn ? dn[1].trim() : id);
}

const byExact = new Map();
const byNorm = new Map();
const byName = new Map();
for (const [id, display] of guideChannels) {
  byExact.set(id.toLowerCase(), id);
  byNorm.set(norm(id), id);
  byName.set(normName(display), id);
}

const matched = new Map(); // tvg-id -> guide id
const stats = { exact: 0, normalized: 0, byName: 0, unmatched: [] };
for (const ch of channels) {
  if (!ch.id) continue;
  let guideId = byExact.get(ch.id.toLowerCase());
  if (guideId) {
    stats.exact++;
  } else {
    guideId = byNorm.get(norm(ch.id));
    if (guideId) {
      stats.normalized++;
    } else {
      guideId = byName.get(normName(ch.name));
      if (guideId) stats.byName++;
    }
  }
  if (guideId) matched.set(ch.id, guideId);
  else if (stats.unmatched.length < 40) stats.unmatched.push(ch.id + " | " + ch.name);
}

// ---- programas ----
const programmes = [];
const progRe = /<programme\s+([^>]*)>([\s\S]*?)<\/programme>/g;
const attrRe = /([A-Za-z0-9_:]+)="([^"]*)"/g;
while ((m = progRe.exec(guide)) !== null) {
  const attrs = {};
  let a;
  while ((a = attrRe.exec(m[1])) !== null) attrs[a[1]] = a[2];
  const guideId = attrs.channel;
  if (!guideId) continue;
  const entry = [...matched.entries()].find(([, gid]) => gid === guideId);
  if (!entry) continue;
  const body = m[2];
  const title = /<title[^>]*>([\s\S]*?)<\/title>/.exec(body);
  const desc = /<desc[^>]*>([\s\S]*?)<\/desc>/.exec(body);
  const cat = /<category[^>]*>([\s\S]*?)<\/category>/.exec(body);
  programmes.push({
    id: entry[0],
    start: attrs.start,
    stop: attrs.stop,
    title: title ? title[1].trim() : "",
    desc: desc ? desc[1].trim() : "",
    cat: cat ? cat[1].trim() : ""
  });
}

const esc = (s) => (s || "").replace(/&/g, "&").replace(/</g, "<").replace(/>/g, ">");

const xml =
  '<?xml version="1.0" encoding="UTF-8"?>\n' +
  '<tv generator-info-name="kzIPTV epg-pipeline">\n' +
  [...matched.keys()]
    .map((id) => '  <channel id="' + esc(id) + '"><display-name>' + esc(id) + '</display-name></channel>')
    .join("\n") +
  "\n" +
  programmes
    .map(
      (p) =>
        '  <programme start="' +
        esc(p.start) +
        '" stop="' +
        esc(p.stop) +
        '" channel="' +
        esc(p.id) +
        '"><title lang="es">' +
        esc(p.title) +
        "</title>" +
        (p.desc ? '<desc lang="es">' + esc(p.desc) + "</desc>" : "") +
        (p.cat ? '<category lang="es">' + esc(p.cat) + "</category>" : "") +
        "</programme>"
    )
    .join("\n") +
  "\n</tv>\n";

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, zlib.gzipSync(Buffer.from(xml, "utf8"), { level: 9 }));

const report = {
  playlistChannels: channels.length,
  guideChannels: guideChannels.size,
  matchedChannels: matched.size,
  matchedExact: stats.exact,
  matchedNormalized: stats.normalized,
  matchedByName: stats.byName,
  programs: programmes.length,
  unmatchedSample: stats.unmatched,
  generatedAt: new Date().toISOString()
};
fs.writeFileSync(path.join(path.dirname(outPath), "report.json"), JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
