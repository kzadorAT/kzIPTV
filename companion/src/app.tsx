import { useEffect, useRef, useState } from "preact/hooks";

type VariantDto = {
  id: string;
  name: string;
  label: string;
  quality: string;
  region: string;
  host: string;
  status: string;
  measured: string;
  active: boolean;
};

type ChannelDto = {
  id: string;
  name: string;
  logo: string;
  number: number;
  groups: string[];
  favorite: boolean;
  kind: string;
  tvgId: string;
  now: string;
  next: string;
  variants: VariantDto[];
  activeVariantId: string;
};

type StateDto = {
  pairingCode: string;
  screen: string;
  serverUrl: string;
  status: string;
  playbackState: string;
  playbackError: string;
  focusedId: string | null;
  focusedName: string;
  focusedCategory: string | null;
  currentId: string | null;
  currentVariantId: string | null;
  currentVariantLabel: string;
  zapFavoritesOnly: boolean;
  channelCount: number;
  groupCount: number;
  variantCount: number;
  categories: string[];
  channels: ChannelDto[];
  numberBuffer: string;
  searchQuery: string;
  focusedSearchId: string | null;
  focusedSearchName: string;
  epgUrl: string;
  epgSource: string;
  epgUpdatedAt: number;
  epgRefreshing: boolean;
};

const KEY = {
  CH_UP: 166,
  CH_DOWN: 167,
  RED: 183,
  GREEN: 184,
  YELLOW: 185,
  BLUE: 186,
  OK: 23,
  BACK: 4,
  UP: 19,
  DOWN: 20,
  LEFT: 21,
  RIGHT: 22,
  MENU: 82
};

function token(): string {
  return localStorage.getItem("kz-token") || "";
}

async function api(path: string, body?: unknown): Promise<any> {
  const opts: RequestInit = body
    ? { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) }
    : {};
  const res = await fetch(path, opts);
  return res.json();
}

export function App() {
  const [state, setState] = useState<StateDto | null>(null);
  const [tab, setTab] = useState<"setup" | "remote" | "channels">("setup");
  const [code, setCode] = useState<string>(window.location.hash.replace("#", ""));
  const [paired, setPaired] = useState<boolean>(!!token());
  const [url, setUrl] = useState<string>("https://iptv-org.github.io/iptv/countries/ar.m3u");
  const [message, setMessage] = useState<string>("");
  const [search, setSearch] = useState<string>("");
  const [qualityFor, setQualityFor] = useState<ChannelDto | null>(null);
  const [epgUrl, setEpgUrl] = useState<string>("");
  const [epgTouched, setEpgTouched] = useState<boolean>(false);
  const socket = useRef<WebSocket | null>(null);

  // estado de la TV (polling simple y robusto)
  useEffect(() => {
    let alive = true;
    const load = async () => {
      try {
        const data = await api("/api/state");
        if (alive) setState(data);
      } catch (e) {
        if (alive) setMessage("Sin conexion con la TV");
      }
    };
    load();
    const timer = setInterval(load, 2500);
    return () => {
      alive = false;
      clearInterval(timer);
    };
  }, []);

  // la URL de la guia la tiene la TV: se muestra sola, pero sin pisar lo que
  // el usuario esta escribiendo
  useEffect(() => {
    if (!epgTouched && state?.epgUrl) setEpgUrl(state.epgUrl);
  }, [state?.epgUrl, epgTouched]);

  // websocket para comandos
  useEffect(() => {
    if (!token()) return;
    const proto = window.location.protocol === "https:" ? "wss" : "ws";
    let retry: number | undefined;
    let ws: WebSocket | null = null;
    const connect = () => {
      if (!token()) return;
      ws = new WebSocket(proto + "://" + window.location.host + "/ws?token=" + encodeURIComponent(token()));
      socket.current = ws;
      ws.onclose = () => {
        socket.current = null;
        retry = window.setTimeout(connect, 3000);
      };
    };
    connect();
    return () => {
      if (retry) window.clearTimeout(retry);
      if (ws) ws.close();
      socket.current = null;
    };
  }, [paired]);

  const send = (payload: Record<string, unknown>) => {
    const body = JSON.stringify({ ...payload, token: token() });
    if (socket.current && socket.current.readyState === WebSocket.OPEN) {
      socket.current.send(body);
    } else {
      // fallback por HTTP cuando el WebSocket no esta disponible
      fetch("/api/command", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body
      }).catch(() => setMessage("Sin conexion con la TV"));
    }
  };

  const key = (code: number) => send({ type: "key", code });

  const pair = async () => {
    const res = await api("/api/pair", { code: code.trim() });
    if (res.ok) {
      localStorage.setItem("kz-token", res.token);
      setPaired(true);
      setMessage("Emparejado con la TV");
      setTab("remote");
    } else {
      setMessage(res.message || "Codigo incorrecto");
    }
  };

  const sendSource = async () => {
    setMessage("Enviando a la TV...");
    const res = await api("/api/source", { url: url.trim(), token: token() });
    setMessage(res.ok ? "Listo: " + res.count + " canales" : "Error: " + res.message);
  };

  const sendFile = async (file: File) => {
    const text = await file.text();
    setMessage("Subiendo " + file.name + "...");
    const res = await api("/api/source", { name: file.name, content: text, token: token() });
    setMessage(res.ok ? "Listo: " + res.count + " canales" : "Error: " + res.message);
  };

  const sendEpg = async () => {
    setMessage("Actualizando la guia EPG...");
    const res = await api("/api/epg", { url: epgUrl.trim(), token: token() });
    setMessage(res.ok ? "Guia EPG: " + (res.message || "actualizando") : "Error: " + (res.message || "rechazada"));
  };

  const toggleFavorite = async (channelId: string, enabled: boolean) => {
    await api("/api/favorite", { channelId, enabled, token: token() });
  };

  const channels = (state?.channels || []).filter((c) => {
    if (!search) return true;
    const q = search.toLowerCase();
    return c.name.toLowerCase().includes(q) || c.groups.join(" ").toLowerCase().includes(q);
  });

  const current = (state?.channels || []).find((c) => c.id === state?.currentId);

  return (
    <div class="app">
      <header>
        <div class="brand">
          <span class="logo">kz</span>
          <div>
            <h1>kzIPTV</h1>
            <p>
              {state ? state.groupCount + " canales" : "conectando..."}
              {state && state.variantCount > state.groupCount ? " · " + state.variantCount + " feeds" : ""}
            </p>
          </div>
        </div>
        <span class={"pill " + (paired ? "ok" : "warn")}>{paired ? "Emparejado" : "Sin emparejar"}</span>
      </header>

      {current ? (
        <div class="nowplaying">
          <img src={current.logo} alt="" />
          <div>
            <strong>{current.number}. {current.name}</strong>
            <span>
              {state?.currentVariantLabel || "—"}
              {current.now ? " · " + current.now : ""}
            </span>
            <span class="screenhint">
              TV en: {state?.screen || "—"}
              {state && state.videoWidth > 0 ? " · " + state.videoWidth + "x" + state.videoHeight : ""}
            </span>
            {state && state.focusedName ? (
              <span class="screenhint focus">
                {state.screen === "GRID"
                  ? "En la grilla: " + state.focusedName
                  : "Foco: " + state.focusedName}
              </span>
            ) : null}
            {state && state.numberBuffer ? (
              <span class="screenhint number">Canal {state.numberBuffer}</span>
            ) : null}
            {state && state.screen === "SEARCH" ? (
              <span class="screenhint focus">
                Buscando: {state.searchQuery || "(vacio)"}
                {state.focusedSearchName ? " \u2192 " + state.focusedSearchName : ""}
              </span>
            ) : null}
          </div>
          <span class={"state " + (state?.playbackState || "")}>{state?.playbackState}</span>
        </div>
      ) : null}

      <nav class="tabs">
        <button class={tab === "setup" ? "active" : ""} onClick={() => setTab("setup")}>Configurar</button>
        <button class={tab === "remote" ? "active" : ""} onClick={() => setTab("remote")}>Control</button>
        <button class={tab === "channels" ? "active" : ""} onClick={() => setTab("channels")}>Canales</button>
      </nav>

      {tab === "setup" && (
        <section class="panel">
          <h2>Configurar la TV</h2>
          <label>Codigo de la TV</label>
          <div class="row">
            <input value={code} onInput={(e) => setCode((e.target as HTMLInputElement).value)} placeholder="123456" inputMode="numeric" />
            <button class="primary" onClick={pair}>Emparejar</button>
          </div>
          <label>URL de la playlist m3u</label>
          <div class="row">
            <input value={url} onInput={(e) => setUrl((e.target as HTMLInputElement).value)} />
            <button class="primary" onClick={sendSource}>Enviar</button>
          </div>
          <label>URL de la guia EPG (XMLTV, puede ir .gz)</label>
          <div class="row">
            <input
              value={epgUrl}
              onInput={(e) => {
                setEpgTouched(true);
                setEpgUrl((e.target as HTMLInputElement).value);
              }}
              placeholder="https://raw.githubusercontent.com/.../epg/ar.xml.gz"
            />
            <button class="primary" onClick={sendEpg}>Actualizar</button>
          </div>
          <label>...o subi un archivo m3u</label>
          <input type="file" accept=".m3u,.m3u8" onChange={(e) => {
            const file = (e.target as HTMLInputElement).files?.[0];
            if (file) sendFile(file);
          }} />
          <p class="msg">{message}</p>
        </section>
      )}

      {tab === "remote" && (
        <section class="panel">
          <h2>Control remoto</h2>
          <div class="dpad">
            <button class="up" onClick={() => key(KEY.UP)}>▲</button>
            <button class="left" onClick={() => key(KEY.LEFT)}>◀</button>
            <button class="ok" onClick={() => key(KEY.OK)}>OK</button>
            <button class="right" onClick={() => key(KEY.RIGHT)}>▶</button>
            <button class="down" onClick={() => key(KEY.DOWN)}>▼</button>
          </div>
          <div class="grid2">
            <button onClick={() => key(KEY.CH_DOWN)}>Canal −</button>
            <button onClick={() => key(KEY.CH_UP)}>Canal +</button>
            <button onClick={() => key(KEY.YELLOW)}>★ Favorito</button>
            <button onClick={() => key(KEY.RED)}>Zap favoritos</button>
            <button onClick={() => send({ type: "quality" })}>Calidad</button>
            <button onClick={() => send({ type: "epg" })}>Guia EPG</button>
            <button onClick={() => send({ type: "mosaic" })}>Comparar canales</button>
            <button onClick={() => key(KEY.BLUE)}>Emparejar / QR</button>
            <button onClick={() => key(KEY.BACK)}>Volver</button>
            <button onClick={() => key(KEY.MENU)}>Menu / QR</button>
            <button onClick={() => key(KEY.GREEN)}>Buscar</button>
          </div>
          <label>Buscar en la TV</label>
          <div class="row">
            <input value={search} onInput={(e) => {
              const v = (e.target as HTMLInputElement).value;
              setSearch(v);
              send({ type: "text", text: v });
            }} placeholder="nombre del canal" />
            <button onClick={() => send({ type: "search", text: search })}>Buscar en la TV</button>
          </div>
          <p class="hint">
            Escribe aca y la TV muestra los resultados en pantalla. Tambien podes buscar
            con las teclas 0-9 para ir directo a un numero de canal.
          </p>
          <p class="msg">{state ? state.status : ""}</p>
          {state && state.playbackError ? (
            <p class="msg err">Error de reproducción: {state.playbackError}</p>
          ) : null}
        </section>
      )}

      {tab === "channels" && (
        <section class="panel">
          <h2>Canales ({channels.length})</h2>
          <input value={search} onInput={(e) => setSearch((e.target as HTMLInputElement).value)} placeholder="Filtrar" />
          <ul class="channels">
            {channels.slice(0, 300).map((c) => (
              <li key={c.id} class={c.id === state?.currentId ? "current" : ""} onClick={() => {
                if (c.variants.length > 1) setQualityFor(c);
                else send({ type: "play", channelId: c.id });
              }}>
                <img src={c.logo} alt="" loading="lazy" />
                <div class="info">
                  <strong>{c.number}. {c.name}</strong>
                  <span>
                    {c.now ? c.now : c.groups.join(" · ")}
                    {c.variants.length > 1 ? " · " + c.variants.length + " calidades" : ""}
                  </span>
                </div>
                {c.variants.length > 1 ? <span class="qbadge">⚙</span> : null}
                <button class="star" onClick={(e) => { e.stopPropagation(); toggleFavorite(c.id, !c.favorite); }}>
                  {c.favorite ? "★" : "☆"}
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}

      {qualityFor ? (
        <div class="modal" onClick={() => setQualityFor(null)}>
          <div class="sheet" onClick={(e) => e.stopPropagation()}>
            <h2>{qualityFor.name}</h2>
            <p class="msg">Elegí la calidad (feed) de este canal</p>
            <ul class="variants">
              {qualityFor.variants.map((v) => (
                <li key={v.id} class={v.active ? "active" : ""} onClick={() => {
                  send({ type: "variant", channelId: qualityFor.id, variantId: v.id });
                  setQualityFor(null);
                }}>
                  <strong>{v.quality ? v.quality.toUpperCase() : "AUTO"}</strong>
                  <div class="info">
                    <strong>{v.name}</strong>
                    <span>{v.host}{v.region ? " · " + v.region : ""}{v.measured ? " · real " + v.measured : ""}</span>
                  </div>
                  <span class={"vstatus " + v.status}>{v.status === "dead" ? "muerto" : v.status === "ok" ? "ok" : ""}</span>
                </li>
              ))}
            </ul>
            <button onClick={() => setQualityFor(null)}>Cerrar</button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
