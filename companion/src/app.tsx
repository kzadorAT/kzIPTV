import { useEffect, useRef, useState } from "preact/hooks";

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
};

type StateDto = {
  pairingCode: string;
  serverUrl: string;
  status: string;
  currentId: string | null;
  zapFavoritesOnly: boolean;
  channelCount: number;
  categories: string[];
  channels: ChannelDto[];
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

  // websocket para comandos
  useEffect(() => {
    if (!token()) return;
    const proto = window.location.protocol === "https:" ? "wss" : "ws";
    const ws = new WebSocket(proto + "://" + window.location.host + "/ws?token=" + encodeURIComponent(token()));
    socket.current = ws;
    ws.onclose = () => {
      socket.current = null;
    };
    return () => {
      ws.close();
      socket.current = null;
    };
  }, [paired]);

  const send = (payload: Record<string, unknown>) => {
    const body = JSON.stringify(payload);
    if (socket.current && socket.current.readyState === WebSocket.OPEN) {
      socket.current.send(body);
    } else {
      fetch("/api/command", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body
      });
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
    const res = await api("/api/source", { url: url.trim() });
    setMessage(res.ok ? "Listo: " + res.count + " canales" : "Error: " + res.message);
  };

  const sendFile = async (file: File) => {
    const text = await file.text();
    setMessage("Subiendo " + file.name + "...");
    const res = await api("/api/source", { name: file.name, content: text });
    setMessage(res.ok ? "Listo: " + res.count + " canales" : "Error: " + res.message);
  };

  const toggleFavorite = async (channelId: string, enabled: boolean) => {
    await api("/api/favorite", { channelId, enabled });
  };

  const channels = (state?.channels || []).filter((c) => {
    if (!search) return true;
    const q = search.toLowerCase();
    return c.name.toLowerCase().includes(q) || c.groups.join(" ").toLowerCase().includes(q);
  });

  return (
    <div class="app">
      <header>
        <div class="brand">
          <span class="logo">kz</span>
          <div>
            <h1>kzIPTV</h1>
            <p>{state ? state.channelCount + " canales" : "conectando..."}</p>
          </div>
        </div>
        <span class={"pill " + (paired ? "ok" : "warn")}>{paired ? "Emparejado" : "Sin emparejar"}</span>
      </header>

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
            <button onClick={() => key(KEY.BLUE)}>Emparejar / QR</button>
            <button onClick={() => key(KEY.BACK)}>Volver</button>
          </div>
          <label>Buscar en la TV</label>
          <div class="row">
            <input value={search} onInput={(e) => {
              const v = (e.target as HTMLInputElement).value;
              setSearch(v);
              send({ type: "text", text: v });
            }} placeholder="nombre del canal" />
          </div>
          <p class="msg">{state ? state.status : ""}</p>
        </section>
      )}

      {tab === "channels" && (
        <section class="panel">
          <h2>Canales ({channels.length})</h2>
          <input value={search} onInput={(e) => setSearch((e.target as HTMLInputElement).value)} placeholder="Filtrar" />
          <ul class="channels">
            {channels.slice(0, 300).map((c) => (
              <li key={c.id} class={c.id === state?.currentId ? "current" : ""} onClick={() => send({ type: "play", channelId: c.id })}>
                <img src={c.logo} alt="" loading="lazy" />
                <div class="info">
                  <strong>{c.number}. {c.name}</strong>
                  <span>{c.now ? c.now : c.groups.join(" · ")}</span>
                </div>
                <button class="star" onClick={(e) => { e.stopPropagation(); toggleFavorite(c.id, !c.favorite); }}>
                  {c.favorite ? "★" : "☆"}
                </button>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
