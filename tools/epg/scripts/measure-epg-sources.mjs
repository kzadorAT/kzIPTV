/**
 * Mide que fuente de EPG (de las que publica iptv-org/epg) cubre mejor la playlist.
 *   node tools/epg/scripts/measure-epg-sources.mjs --playlist <m3u> --epg-repo <iptv-org/epg clone>
 */
import fs from "node:fs";
import path from "node:path";

const args = {};
process.argv.slice(2).forEach((a, i, all) => { if (a.startsWith("--")) args[a.slice(2)] = all[i + 1]; });

const playlist = fs.readFileSync(args.playlist, "utf8");
const repo = args["epg-repo"] || args.epgRepo;

const m3u = [];
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
  m3u.push({ id: attrs["tvg-id"] || "", name: (c >= 0 ? tail.slice(c + 1) : tail).trim() });
}

const norm = (s) => (s || "").toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[^a-z0-9]/g, "");
const stripQual = (s) => (s || "").replace(/@(SD|HD)$/i, "");

const sitesDir = path.join(repo, "sites");
const perSite = new Map();
const allXmltvIds = new Map();   // xmltv_id -> site
const allNames = new Map();      // nombre normalizado -> [site, site_id]

for (const site of fs.readdirSync(sitesDir)) {
  const dir = path.join(sitesDir, site);
  if (!fs.statSync(dir).isDirectory()) continue;
  const files = fs.readdirSync(dir).filter((f) => f.endsWith(".channels.xml"));
  for (const f of files) {
    const xml = fs.readFileSync(path.join(dir, f), "utf8");
    const cre = /<channel\s+([^>]*)>([\s\S]*?)<\/channel>/g;
    let c;
    while ((c = cre.exec(xml)) !== null) {
      const attrs = {};
      const are = /([A-Za-z0-9_]+)="([^"]*)"/g;
      let a;
      while ((a = are.exec(c[1])) !== null) attrs[a[1]] = a[2];
      const name = (c[2] || "").replace(/<[^>]+>/g, "").trim();
      const xmltv = (attrs.xmltv_id || "").trim();
      const siteId = (attrs.site_id || "").trim();
      const rec = { site, siteId, xmltv, name };
      perSite.set(site, (perSite.get(site) || 0) + 1);
      if (xmltv) {
        allXmltvIds.set(xmltv.toLowerCase(), rec);
        allXmltvIds.set(stripQual(xmltv).toLowerCase(), rec);
      }
      const n = norm(name);
      if (n && !allNames.has(n)) allNames.set(n, rec);
      const n2 = norm(name.replace(/\s*\([^)]*\)\s*$/, ""));
      if (n2 && !allNames.has(n2)) allNames.set(n2, rec);
    }
  }
}

const exact = [], byStrip = [], byName = [], missing = [];
for (const ch of m3u) {
  if (!ch.id) continue;
  const lower = ch.id.toLowerCase();
  if (allXmltvIds.has(lower)) { exact.push(ch); continue; }
  const stripped = stripQual(lower);
  if (allXmltvIds.has(stripped)) { byStrip.push(ch); continue; }
  const n = norm(ch.name);
  const n2 = norm(ch.name.replace(/\s*\([^)]*\)\s*$/, ""));
  if (allNames.has(n) || allNames.has(n2)) { byName.push(ch); continue; }
  missing.push(ch);
}

const siteCounts = {};
for (const ch of [...exact, ...byStrip]) {
  const rec = allXmltvIds.get(ch.id.toLowerCase()) || allXmltvIds.get(stripQual(ch.id.toLowerCase()));
  const s = rec ? rec.site : "?";
  siteCounts[s] = (siteCounts[s] || 0) + 1;
}

console.log(JSON.stringify({
  playlistChannels: m3u.length,
  sitesScanned: perSite.size,
  guideChannelsWithXmltvId: allXmltvIds.size,
  matchExactId: exact.length,
  matchIdWithoutQualifier: byStrip.length,
  matchByName: byName.length,
  unmatched: missing.length,
  topSitesByIdMatch: Object.entries(siteCounts).sort((x, y) => y[1] - x[1]).slice(0, 12),
  unmatchedSample: missing.slice(0, 25).map((c) => c.id + " | " + c.name)
}, null, 2));
