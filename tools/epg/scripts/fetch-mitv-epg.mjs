/**
 * Genera el EPG de los canales de la playlist a partir de mi.tv.
 *
 * Los tvg-id de la playlist de iptv-org (ar.m3u) salen justamente de mi.tv
 * (ej: "AmericaTV.ar@SD" <-> site_id "ar#america-2-capital"), asi que el match
 * es exacto y no hace falta adivinar por nombre.
 *
 *   node scripts/fetch-mitv-epg.mjs \
 *     --playlist fixtures/ar.m3u \
 *     --channels-index <iptv-org/epg>/sites/mi.tv/mi.tv_ar.channels.xml \
 *     --out epg/ar.xml.gz --days 2
 */
import fs from "node:fs";
import path from "node:path";
import zlib from "node:zlib";
import * as cheerio from "cheerio";

const args = {};
process.argv.slice(2).forEach((a, i, all) => { if (a.startsWith("--")) args[a.slice(2)] = all[i + 1]; });

const playlistPath = args.playlist;
const indexPath = args["channels-index"];
const outPath = args.out || "epg/ar.xml.gz";
const days = Number(args.days || 2);
const country = args.country || "ar";
const concurrency = Number(args.concurrency || 4);
const UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";
const AR_OFFSET_MS = -3 * 60 * 60 * 1000;

if (!playlistPath || !indexPath) {
  console.error("uso: node fetch-mitv-epg.mjs --playlist <m3u> --channels-index <channels.xml> --out <gz>");
  process.exit(1);
}

const stripQual = (s) => s.replace(/@[^@]+$/, "").toLowerCase();
const decodeEntities = (s) =>
  s.replace(/&/g, "&").replace(/</g, "<").replace(/>/g, ">").replace(/"/g, '"').replace(/'/g, "'");

// ---------- playlist ----------
const playlist = fs.readFileSync(playlistPath, "utf8");
const channels = [];
const re = /#EXTINF:([^\n]*)\n([^\n]+)/g;
let m;
while ((m = re.exec(playlist)) !== null) {
  const attrs = {};
  const are = /([A-Za-z0-9_-]+)="([^"]*)"/g;
  let a;
  while ((a = are.exec(m[1])) !== null) attrs[a[1]] = a[2];
  const q = m[1].lastIndexOf('"');
  const tail = q >= 0 ? m[1].slice(q + 1) : m[1];
  const c = tail.indexOf(",");
  channels.push({ id: (attrs["tvg-id"] || "").trim(), name: (c >= 0 ? tail.slice(c + 1) : tail).trim() });
}

// ---------- indice de canales de mi.tv ----------
const indexFiles = [];
for (const part of String(indexPath).split(";")) {
  const p = part.trim();
  if (!p) continue;
  if (fs.existsSync(p) && fs.statSync(p).isDirectory()) {
    for (const f of fs.readdirSync(p).sort()) {
      if (f.endsWith(".channels.xml")) indexFiles.push(path.join(p, f));
    }
  } else {
    indexFiles.push(p);
  }
}
// AR primero: si un id aparece en varios paises gana la guia argentina.
indexFiles.sort((a, b2) => (a.includes(country + "_") ? -1 : 0) - (b2.includes(country + "_") ? -1 : 0));

const index = new Map();
const put = (key, entry) => { if (!index.has(key)) index.set(key, entry); };
for (const file of indexFiles) {
  const indexXml = fs.readFileSync(file, "utf8");
  const cre = /<channel\s+([^>]*)>([\s\S]*?)<\/channel>/g;
  while ((m = cre.exec(indexXml)) !== null) {
    const attrs = {};
    const are = /([A-Za-z0-9_]+)="([^"]*)"/g;
    let a;
    while ((a = are.exec(m[1])) !== null) attrs[a[1]] = a[2];
    const xmltv = (attrs.xmltv_id || "").trim();
    const siteId = (attrs.site_id || "").trim();
    if (!xmltv || !siteId.includes("#")) continue;
    const parts = siteId.split("#");
    const entry = {
      country: parts[0],
      siteId: parts[1],
      name: decodeEntities((m[2] || "").replace(/<[^>]+>/g, "").trim())
    };
    put(xmltv.toLowerCase(), entry);
    put(stripQual(xmltv.toLowerCase()), entry);
  }
}

// ---------- match playlist <-> mi.tv ----------
const matched = [];
const unmatched = [];
for (const ch of channels) {
  if (!ch.id) continue;
  const hit = index.get(ch.id.toLowerCase()) || index.get(stripQual(ch.id.toLowerCase()));
  if (hit) matched.push({ tvgId: ch.id, name: ch.name || hit.name, siteId: hit.siteId, country: hit.country });
  else unmatched.push(ch.id + " | " + ch.name);
}

// ---------- fechas (hora argentina) ----------
const arDate = (offsetDays) => new Date(Date.now() + AR_OFFSET_MS + offsetDays * 86400000).toISOString().slice(0, 10);

async function fetchPrograms(job) {
  const programs = [];
  for (let d = 0; d < days; d++) {
    const date = arDate(d);
    const url = "https://mi.tv/" + job.country + "/async/channel/" + job.siteId + "/" + date + "/0";
    try {
      const res = await fetch(url, { headers: { "User-Agent": UA, Accept: "text/html" } });
      if (!res.ok) continue;
      const html = await res.text();
      const $ = cheerio.load(html);
      $("ul.broadcasts > li").each((i, el) => {
        const $item = $(el);
        const time = $item.find("a > div.content > span.time").text().trim();
        const tm = /^(\d{1,2}):(\d{2})$/.exec(time);
        if (!tm) return;
        const hh = Number(tm[1]);
        if (hh > 23) return;
        const title = decodeEntities($item.find("a > div.content > h2").text().trim());
        if (!title) return;
        programs.push({
          date,
          hhmm: String(hh).padStart(2, "0") + ":" + tm[2],
          title,
          category: decodeEntities($item.find("a > div.content > span.sub-title").text().trim()),
          desc: decodeEntities($item.find("a > div.content > p.synopsis").text().trim())
        });
      });
    } catch (e) {
      // canal sin datos o red: se sigue
    }
  }
  programs.sort((a, b) => (a.date + a.hhmm).localeCompare(b.date + b.hhmm));
  return programs;
}

// ---------- worker pool ----------
const results = [];
let cursor = 0;
async function worker() {
  while (cursor < matched.length) {
    const job = matched[cursor++];
    const programs = await fetchPrograms(job);
    results.push({ job, programs });
    process.stdout.write(".");
  }
}
await Promise.all(Array.from({ length: Math.min(concurrency, matched.length || 1) }, () => worker()));
process.stdout.write("\n");

// ---------- XMLTV ----------
const esc = (s) => String(s || "").replace(/&/g, "&").replace(/</g, "<").replace(/>/g, ">");
const stamp = (date, hhmm) => date.replace(/-/g, "") + hhmm.replace(":", "") + "00 -0300";
const plusHour = (date, hhmm) => {
  const mins = Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5)) + 60;
  const d = new Date(Date.parse(date + "T00:00:00-03:00") + (mins - 1440) * 60000);
  return { date: new Date(d.getTime() + AR_OFFSET_MS).toISOString().slice(0, 10), hhmm: String(d.getUTCHours()).padStart(2, "0") + ":" + String(d.getUTCMinutes()).padStart(2, "0") };
};

const lines = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="kzIPTV mitv-epg">'];
let programCount = 0;
const withGuide = [];
for (const r of results) {
  if (!r.programs.length) continue;
  withGuide.push(r.job.tvgId);
  lines.push('  <channel id="' + esc(r.job.tvgId) + '"><display-name>' + esc(r.job.name) + "</display-name></channel>");
  for (let i = 0; i < r.programs.length; i++) {
    const p = r.programs[i];
    const next = r.programs[i + 1];
    const stop = next ? next : plusHour(p.date, p.hhmm);
    lines.push(
      '  <programme start="' + stamp(p.date, p.hhmm) +
      '" stop="' + stamp(stop.date, stop.hhmm) +
      '" channel="' + esc(r.job.tvgId) + '">' +
      '<title lang="es">' + esc(p.title) + "</title>" +
      (p.desc ? '<desc lang="es">' + esc(p.desc) + "</desc>" : "") +
      (p.category ? '<category lang="es">' + esc(p.category) + "</category>" : "") +
      "</programme>"
    );
    programCount++;
  }
}
lines.push("</tv>", "");

fs.mkdirSync(path.dirname(outPath), { recursive: true });
fs.writeFileSync(outPath, zlib.gzipSync(Buffer.from(lines.join("\n"), "utf8"), { level: 9 }));

const report = {
  generatedAt: new Date().toISOString(),
  source: "https://mi.tv/" + country,
  playlistChannels: channels.length,
  matchedByTvgId: matched.length,
  channelsWithGuide: withGuide.length,
  programs: programCount,
  unmatchedCount: unmatched.length,
  unmatchedSample: unmatched.slice(0, 30),
  out: outPath
};
fs.writeFileSync(path.join(path.dirname(outPath), "report.json"), JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
