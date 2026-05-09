import { useEffect, useState, useCallback, useRef } from "react";

const KNOBS = [
  { key: "wbRed",   ep: "wb-red",   label: "Red gain",   accent: "#ff453a" },
  { key: "wbGreen", ep: "wb-green", label: "Green gain", accent: "#30d158" },
  { key: "wbBlue",  ep: "wb-blue",  label: "Blue gain",  accent: "#0a84ff" },
];

const PICTURE_ITEMS = [
  { key: "brightness", ep: "brightness", label: "Brightness", accent: "#f5f5f7" },
  { key: "contrast",   ep: "contrast",   label: "Contrast",   accent: "#f5f5f7" },
  { key: "saturation", ep: "saturation", label: "Saturation", accent: "#f5f5f7" },
  { key: "sharpness",  ep: "sharpness",  label: "Sharpness",  accent: "#f5f5f7" },
  { key: "hue",        ep: "hue",        label: "Hue",        accent: "#f5f5f7" },
];

const PICTURE_MODES = { 1: "Movie", 9: "Football", 6: "Office", 4: "Game", 3: "User" };

function api(path, opts = {}) {
  return fetch(path, {
    method: opts.method || "GET",
    headers: opts.body ? { "Content-Type": "application/json" } : {},
    body: opts.body ? JSON.stringify(opts.body) : undefined,
    signal: opts.signal,
  }).then((r) => r.json());
}

function labeled(map, n) {
  if (n == null) return "-";
  return map[n] ? `${map[n]} (${n})` : String(n);
}

function prettyInput(s) {
  if (!s) return "-";
  if (s === "E_INPUT_SOURCE_STORAGE") return "Apps";
  if (s.startsWith("E_INPUT_SOURCE_HDMI")) return `HDMI ${s.replace("E_INPUT_SOURCE_HDMI", "")}`;
  return s;
}

function sourceTitle(picture, inputs) {
  if (!picture) return "";
  const src = picture.currentInputSource;
  let label;
  if (src === "E_INPUT_SOURCE_STORAGE") label = "Android TV";
  else if (typeof src === "string" && src.startsWith("E_INPUT_SOURCE_HDMI")) {
    const port = Number(src.replace("E_INPUT_SOURCE_HDMI", ""));
    label = inputLabelForPort(inputs, port, `HDMI ${port}`);
  } else label = src || "";
  const mode = PICTURE_MODES[picture.pictureMode];
  return mode ? `${label} (${mode})` : label;
}

function PinIcon() {
  return (
    <svg className="pin" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 17v5" />
      <path d="M9 10.76V6h-1a2 2 0 0 1 0-4h8a2 2 0 0 1 0 4h-1v4.76a2 2 0 0 0 1.11 1.79l1.78.9A2 2 0 0 1 19 15.24V16a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-.76a2 2 0 0 1 1.11-1.79l1.78-.9A2 2 0 0 0 9 10.76Z" />
    </svg>
  );
}

function inputLabelForPort(inputs, port, fallback) {
  if (!Array.isArray(inputs)) return fallback;
  const match = inputs.find((i) => i.hdmiPortId === port && (i.displayName || i.label));
  return (match && (match.displayName || match.label)) || fallback;
}

function epToField(ep) {
  switch (ep) {
    case "wb-red": return "wbRed";
    case "wb-green": return "wbGreen";
    case "wb-blue": return "wbBlue";
    case "color-temp": return "colorTemp";
    case "picture-mode": return "pictureMode";
    case "game-mode": return "gameMode";
    case "brightness": return "brightness";
    case "contrast": return "contrast";
    case "saturation": return "saturation";
    case "sharpness": return "sharpness";
    case "hue": return "hue";
    default: return null;
  }
}

function rgbDot(v) {
  const r = clamp(v?.wbRed), g = clamp(v?.wbGreen), b = clamp(v?.wbBlue);
  return `rgb(${rrr(r)}, ${rrr(g)}, ${rrr(b)})`;
}
function clamp(n) { if (typeof n !== "number") return 50; return Math.max(0, Math.min(100, n)); }
function rrr(n) { return Math.round(80 + (n / 100) * 175); }

// Android KeyEvent codes used by the virtual remote.
// SETTINGS = 2101 is Xgimi's KEYCODE_XGIMI_MISCKEY — the actual gear button on the physical
// remote. Standard 176 (KEYCODE_SETTINGS) does nothing on this device. The bridge has a
// special-case for 2101 that routes via shell uid (am startservice misckey).
const KEY = {
  DPAD_UP: 19, DPAD_DOWN: 20, DPAD_LEFT: 21, DPAD_RIGHT: 22, DPAD_CENTER: 23,
  BACK: 4, HOME: 3, MENU: 82,
  VOLUME_UP: 24, VOLUME_DOWN: 25, VOLUME_MUTE: 164,
  SETTINGS: 2101, ASSIST: 219, FOCUS: 2099,
};

export default function App() {
  const [picture, setPicture] = useState(null);
  const [inputs, setInputs] = useState([]);
  const [state, setState] = useState(null);
  const [presets, setPresets] = useState([]);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState(null);
  const [adbAuthorized, setAdbAuthorized] = useState(true); // optimistic — banner only flashes if check fails
  const [authorizing, setAuthorizing] = useState(false);
  const [connected, setConnected] = useState(true);
  const connectedRef = useRef(connected);
  connectedRef.current = connected;
  const [sourceOpen, setSourceOpen] = useState(false);
  const [dialog, setDialog] = useState(null);
  const dialogResolverRef = useRef(null);
  /**
   * Live-only request governance. Two layers:
   *
   *   1. {@link liveAbortRef}: a shared AbortController that the offline-transition effect
   *      flips to abort everything in flight. Lets us *try* to cancel a parked iOS
   *      request — sometimes it actually goes through, but we don't depend on it.
   *
   *   2. {@link inFlightRef} + {@link pendingKeyRef}: a single-flight semaphore for keypresses.
   *      iOS doesn't fail fetch() fast on a dropped radio — it parks the request in the OS
   *      network queue and *replays* the whole queue when the link returns, flooding the
   *      bridge with stale taps. AbortController in JS land doesn't reach into that OS
   *      queue. So we cap the stack: at most ONE keypress can be in flight at a time;
   *      while one is pending, additional taps just overwrite a single "next" slot. The
   *      caller's hold loop can fire as fast as it wants — the network sees one request,
   *      then one more, never a queued backlog.
   */
  const liveAbortRef = useRef(new AbortController());
  const inFlightRef = useRef(false);
  const pendingKeyRef = useRef(null);
  /**
   * Circuit breaker. AbortController in JS only rejects the Promise — on iOS Safari it
   * doesn't always cancel the underlying request that's already been handed to the OS
   * network queue. So if the projector goes offline and we keep firing keyevents, every
   * timeout cycle adds one more parked request to iOS's queue, and they all replay when
   * the radio comes back. Trip this flag the moment ONE keyevent fails — refuse to fire
   * any more keyevents until the polling refresh confirms the bridge is reachable again.
   */
  const circuitOpenRef = useRef(false);
  const [pollFast, setPollFast] = useState(false);

  /**
   * Helper for one-shot control calls (source switch, power) that should only run when
   * the bridge is known-reachable. Skips when offline, gives the request a tight per-call
   * deadline, and silently swallows aborts. Use {@link sendKeyDispatch} for keypresses
   * since those need the single-flight semaphore.
   */
  function liveCall(path, opts = {}, deadlineMs = 1500) {
    if (!connectedRef.current) return Promise.resolve(null);
    const perCall = new AbortController();
    const timer = setTimeout(() => perCall.abort(), deadlineMs);
    const onShared = () => perCall.abort();
    liveAbortRef.current.signal.addEventListener("abort", onShared, { once: true });
    return api(path, { ...opts, signal: perCall.signal })
      .catch((e) => {
        if (e?.name === "AbortError") return null;
        throw e;
      })
      .finally(() => {
        clearTimeout(timer);
        liveAbortRef.current.signal.removeEventListener("abort", onShared);
      });
  }

  /**
   * Single-flight keyevent dispatcher with a circuit-breaker around iOS's parked-fetch
   * queue. First call fires; concurrent calls just overwrite a single "pending" slot. The
   * settled call's loop picks up whatever's pending and fires that once. If any fetch
   * fails (timeout/abort/error), the circuit opens — no more keyevents leave the device
   * until the polling refresh confirms the bridge is reachable again, at which point
   * pollFast also kicks in to bring the recovery latency from 5 s down to ~1 s.
   */
  async function sendKeyDispatch(keyCode) {
    if (!connectedRef.current || circuitOpenRef.current) return;
    if (inFlightRef.current) {
      // Hot path during a held press: replace whatever's pending with the most recent tap.
      pendingKeyRef.current = keyCode;
      return;
    }
    inFlightRef.current = true;
    let current = keyCode;
    try {
      while (current != null) {
        let ok = false;
        try {
          const r = await liveCall(`/api/adb/keyevent/${current}`, { method: "POST" }, 1500);
          ok = r != null;  // liveCall returns null on abort
        } catch (e) {
          setErr(String(e));
        }
        if (!ok) {
          // Trip the breaker. Drop pending; stop firing until polling closes the circuit.
          circuitOpenRef.current = true;
          pendingKeyRef.current = null;
          setPollFast(true);
          return;
        }
        if (!connectedRef.current) {
          pendingKeyRef.current = null;
          return;
        }
        current = pendingKeyRef.current;
        pendingKeyRef.current = null;
      }
    } finally {
      inFlightRef.current = false;
    }
  }

  function openDialog(spec) {
    return new Promise((resolve) => {
      dialogResolverRef.current = resolve;
      setDialog(spec);
    });
  }
  function closeDialog(value) {
    const resolve = dialogResolverRef.current;
    dialogResolverRef.current = null;
    setDialog(null);
    if (resolve) resolve(value);
  }
  const askPrompt = (spec) => openDialog({ kind: "prompt", ...spec });
  const askConfirm = (spec) => openDialog({ kind: "confirm", ...spec });

  // Polling refresh excludes /api/picture: the AIDL getter for picture items returns the
  // slot-0 default rather than what we last wrote, so polling would clobber the slider state.
  // Picture state is updated optimistically from successful writes / preset applies / input switches.
  const refresh = useCallback(async () => {
    try {
      const [s, ps, ins, adb] = await Promise.all([
        api("/api/state"),
        api("/api/presets"),
        api("/api/inputs").catch(() => []),
        api("/api/adb/status").catch(() => null),
      ]);
      setState(s); setPresets(ps); setInputs(ins || []);
      if (adb) setAdbAuthorized(!!adb.authorized);
      setConnected(true);
      // Successful round-trip means the bridge is reachable — close the keyevent circuit
      // breaker and step polling back down to the leisurely cadence.
      circuitOpenRef.current = false;
      setPollFast(false);
    } catch (e) {
      // Bridge unreachable — likely the projector is asleep / off-network. Don't surface
      // the raw network error as a red banner every 5 s; the disconnected indicator says
      // it more cleanly.
      setConnected(false);
    }
  }, []);

  async function authorizeAdb() {
    setAuthorizing(true);
    try {
      await api("/api/adb/authorize", { method: "POST" });
      setAdbAuthorized(true);
    } catch (e) {
      setErr(String(e));
    } finally {
      setAuthorizing(false);
    }
  }

  useEffect(() => {
    refresh();
    api("/api/picture").then(setPicture).catch((e) => setErr(String(e)));
    // Tight polling (1 s) when the keyevent circuit is open so we close it within ~1 s
    // of reachability returning instead of waiting up to 5 s. Drops back to 5 s during
    // healthy operation.
    const t = setInterval(refresh, pollFast ? 1000 : 5000);
    return () => clearInterval(t);
  }, [refresh, pollFast]);

  // Errors from a failed action (sendKey, switchInput, etc.) usually surface during a
  // brief outage. Clear them automatically the next time the bridge comes back online so
  // the user doesn't have to dismiss a stale red card by hand.
  useEffect(() => {
    if (connected) setErr(null);
  }, [connected]);

  // The moment we detect the bridge is unreachable, abort every parked live request so
  // they can't replay against the bridge when the radio comes back, drop any held-key
  // tap that was queued in the single-flight slot, trip the keyevent circuit so no new
  // taps fire, and switch polling to the fast 1 s cadence so we close the circuit ~1 s
  // after the link returns rather than waiting up to 5 s.
  useEffect(() => {
    if (!connected) {
      liveAbortRef.current.abort();
      liveAbortRef.current = new AbortController();
      pendingKeyRef.current = null;
      circuitOpenRef.current = true;
      setPollFast(true);
    }
  }, [connected]);

  // While the user is mid-authorize, poll the status endpoint every 1s so we flip the UI
  // off "Waiting…" within ~1s of them tapping Allow on the TV — instead of waiting for the
  // long-running /api/adb/authorize POST to return.
  useEffect(() => {
    if (!authorizing) return;
    let cancelled = false;
    const t = setInterval(async () => {
      try {
        const r = await api("/api/adb/status");
        if (!cancelled && r && r.authorized) {
          setAdbAuthorized(true);
          setAuthorizing(false);
        }
      } catch (_) {}
    }, 1000);
    return () => { cancelled = true; clearInterval(t); };
  }, [authorizing]);

  async function setKnob(ep, value) {
    setBusy(true);
    try {
      await api(`/api/picture/${ep}`, { method: "POST", body: { value } });
      // Optimistic: trust what we wrote, skip the lying getter.
      const fieldKey = epToField(ep);
      if (fieldKey) setPicture((prev) => prev ? { ...prev, [fieldKey]: value } : prev);
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function reset() {
    setBusy(true);
    try {
      await api("/api/picture/reset", { method: "POST", body: { mode: picture?.pictureMode ?? 0, force: false } });
      setPicture(await api("/api/picture"));
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function saveCurrent() {
    const name = await askPrompt({ title: "Save preset", placeholder: "Preset name", okLabel: "Save" });
    if (!name || !name.trim()) return;
    setBusy(true);
    try {
      await api("/api/presets", { method: "POST", body: { name: name.trim() } });
      setPresets(await api("/api/presets"));
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function applyPreset(id) {
    const preset = presets.find((x) => x.id === id);
    setBusy(true);
    try {
      await api(`/api/presets/${id}/apply`, { method: "POST" });
      // Optimistic update from preset values (getter is unreliable for picture items).
      if (preset?.values) setPicture((prev) => prev ? { ...prev, ...preset.values } : prev);
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function syncPreset(id) {
    setBusy(true);
    try {
      await api(`/api/presets/${id}/sync`, { method: "POST" });
      setPresets(await api("/api/presets"));
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function deletePreset(id, name) {
    const ok = await askConfirm({ title: "Delete preset", message: `Delete "${name}"?`, okLabel: "Delete", danger: true });
    if (!ok) return;
    setBusy(true);
    try {
      await api(`/api/presets/${id}`, { method: "DELETE" });
      setPresets(await api("/api/presets"));
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  async function switchInput(to) {
    setSourceOpen(false);
    if (!connected) return;
    setBusy(true);
    try {
      const r = await liveCall("/api/input", { method: "POST", body: { to } }, 3000);
      if (r === null) { setBusy(false); return; }
      // The AIDL `currentInputSource` lags (HDMI switch updates it; the Apps path just fires a
      // launcher intent and xgimiservice doesn't notice). Optimistically reflect the user's pick.
      const aidlSource = to === "STORAGE" ? "E_INPUT_SOURCE_STORAGE"
                       : to === "HDMI1"   ? "E_INPUT_SOURCE_HDMI1"
                       : to === "HDMI2"   ? "E_INPUT_SOURCE_HDMI2"
                       : to === "HDMI3"   ? "E_INPUT_SOURCE_HDMI3"
                       : null;
      // Then re-fetch picture (per-source picture-mode/colorTemp may differ); merge so the
      // optimistic source isn't clobbered if AIDL hasn't caught up yet.
      try {
        const p = await api("/api/picture");
        setPicture(aidlSource ? { ...p, currentInputSource: aidlSource } : p);
      } catch (_) {
        if (aidlSource) setPicture((prev) => prev ? { ...prev, currentInputSource: aidlSource } : prev);
      }
    } catch (e) { setErr(String(e)); }
    finally { setBusy(false); }
  }

  // KEYCODE_XGIMI_POWER. Going through the keyevent path (rather than the POWER_WINDOW
  // intent that opens the power menu) means Xgimi's WindowManager intercept toggles display
  // standby exactly the way the physical remote's power button does — single press off when
  // on, single press wakes when off.
  async function pressPower() {
    try { await liveCall(`/api/adb/keyevent/2100`, { method: "POST" }, 3000); }
    catch (e) { setErr(String(e)); }
  }

  async function sendKey(keyCode) {
    sendKeyDispatch(keyCode);
  }

  const onStorage = picture?.currentInputSource === "E_INPUT_SOURCE_STORAGE";

  return (
    <div className="screen">
      <header className="hdr">
        <div className="hdr-row">
          <div>
            <h1>Xgimi Remote</h1>
            <p className={"subtitle" + (connected ? "" : " subtitle-offline")}>
              {connected
                ? (picture ? sourceTitle(picture, inputs) : (state?.ready ? "Connected" : "Connecting..."))
                : "Disconnected — projector unreachable"}
              {connected && busy && " · sending"}
            </p>
          </div>
          <div className="hdr-actions">
            <button className="hdr-btn hdr-src" onClick={() => setSourceOpen(true)} aria-label="Choose source" disabled={busy}>
              <SourceIcon />
            </button>
            <button className="hdr-btn hdr-power" onClick={pressPower} aria-label="Power" disabled={busy}>
              <PowerIcon />
            </button>
          </div>
        </div>
      </header>

      {err && (
        <section className="card">
          <h2>Error</h2>
          <pre className="err">{err}</pre>
          <button className="btn" onClick={() => setErr(null)}>Dismiss</button>
        </section>
      )}

      {adbAuthorized ? (
        <RemoteCard sendKey={sendKey} />
      ) : (
        <section className="card adb-card">
          <h2>Remote needs access</h2>
          <p className="muted" style={{ marginBottom: 14 }}>
            Tap <b>Authorize</b>. A one-time prompt appears on the projector — accept it with the
            physical remote.
          </p>
          <button className="btn adb-btn" onClick={authorizeAdb} disabled={authorizing}>
            {authorizing ? "Waiting for Allow on TV…" : "Authorize"}
          </button>
        </section>
      )}

      <section className="card display-settings">
        <h2>Display settings</h2>

        <details className="sub-card">
          <summary>
            <h3>Presets</h3>
            <span className="sub-actions">
              <button className="icon-btn icon-accent" title="Save current as new preset" onClick={(e) => { e.preventDefault(); saveCurrent(); }} disabled={busy || !picture}>+</button>
            </span>
          </summary>
          {presets.length === 0 ? (
            <p className="muted">No presets yet. Tune sliders below, then save.</p>
          ) : (
            <ul className="presets">
              {presets.map((p) => (
                <li key={p.id} className="preset">
                  <div className="preset-row">
                    <button className="preset-name" onClick={() => applyPreset(p.id)} disabled={busy}>
                      <span className="dot" style={{ background: rgbDot(p.values) }} />
                      <span>{p.name}</span>
                    </button>
                    <div className="preset-actions">
                      {p.builtin ? (
                        <span className="badge" title="Built-in (read-only)"><PinIcon /></span>
                      ) : (
                        <>
                          <button className="icon-btn" title="Update from current values" onClick={() => syncPreset(p.id)} disabled={busy}>↻</button>
                          <button className="icon-btn icon-danger" title="Delete" onClick={() => deletePreset(p.id, p.name)} disabled={busy}>×</button>
                        </>
                      )}
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </details>

        <details className="sub-card">
          <summary>
            <h3>White balance</h3>
            <span className="sub-actions">
              <button className="icon-btn" title="Reset picture mode" onClick={(e) => { e.preventDefault(); reset(); }} disabled={busy || onStorage}>↻</button>
            </span>
          </summary>
          {!picture && <p className="muted">loading…</p>}
          {picture && onStorage && <p className="muted">Not applicable on Apps. Switch to HDMI to adjust.</p>}
          {picture && !onStorage && KNOBS.map((k) => (
            <Slider key={k.key} label={k.label} accent={k.accent}
              value={picture[k.key] ?? 50}
              onChange={(v) => setKnob(k.ep, v)} />
          ))}
        </details>

        <details className="sub-card">
          <summary><h3>Picture</h3></summary>
          {!picture && <p className="muted">loading…</p>}
          {picture && onStorage && <p className="muted">Not applicable on Apps. Switch to HDMI to adjust.</p>}
          {picture && !onStorage && PICTURE_ITEMS.map((k) => (
            <Slider key={k.key} label={k.label} accent={k.accent}
              value={picture[k.key] ?? 50}
              onChange={(v) => setKnob(k.ep, v)} />
          ))}
        </details>
      </section>

      {sourceOpen && (
        <SourceSheet
          inputs={inputs}
          picture={picture}
          busy={busy}
          onPick={switchInput}
          onClose={() => setSourceOpen(false)}
        />
      )}

      {dialog && <Dialog spec={dialog} onClose={closeDialog} />}
    </div>
  );
}

function SourceSheet({ inputs, picture, busy, onPick, onClose }) {
  const current = picture?.currentInputSource;
  function activeFor(src) { return current === src ? " input-btn-active" : ""; }
  return (
    <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="source-sheet" role="dialog" aria-modal="true" aria-label="Choose source">
        <h3 className="modal-title">Source</h3>
        <div className="input-rows">
          <button className={"input-btn input-btn-row" + activeFor("E_INPUT_SOURCE_STORAGE")} onClick={() => onPick("STORAGE")} disabled={busy}>
            <span>Apps</span><small>Android TV</small>
          </button>
          {[1, 2].map((port) => {
            const cec = inputLabelForPort(inputs, port, "");
            return (
              <button key={port} className={"input-btn input-btn-row" + activeFor(`E_INPUT_SOURCE_HDMI${port}`)} onClick={() => onPick(`HDMI${port}`)} disabled={busy}>
                <span>{cec || `HDMI ${port}`}</span>
                {cec && <small>HDMI {port}</small>}
              </button>
            );
          })}
        </div>
        <div className="modal-actions">
          <button className="modal-btn" onClick={onClose}>Close</button>
        </div>
      </div>
    </div>
  );
}

function CloseIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <line x1="6" y1="6" x2="18" y2="18" />
      <line x1="18" y1="6" x2="6" y2="18" />
    </svg>
  );
}

function SourceIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="5" width="18" height="12" rx="2" />
      <path d="M8 21h8" />
      <path d="M12 17v4" />
      <path d="M9 11l3-3 3 3" />
      <path d="M12 8v6" />
    </svg>
  );
}

function PowerIcon() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M18.36 6.64a9 9 0 1 1-12.72 0" />
      <line x1="12" y1="2" x2="12" y2="12" />
    </svg>
  );
}

/**
 * Hook returning pointer handlers for a key that fires once on press, then repeats while held.
 * Mimics the physical remote's auto-repeat: 380 ms initial delay, then ~12 events/sec.
 *
 * Also exposes `pressed` state so the caller can apply a CSS class for visual feedback —
 * Mobile Safari swallows :active when pointerdown handlers call preventDefault, so we don't
 * rely on it.
 */
function useHoldable(send, opts = {}) {
  const repeat = opts.repeat !== false;
  const initialDelay = opts.initialDelay ?? 380;
  const interval = opts.interval ?? 80;
  const timerRef = useRef(null);
  const pressedRef = useRef(false);
  const [pressed, setPressed] = useState(false);

  function stop() {
    pressedRef.current = false;
    setPressed(false);
    if (timerRef.current) {
      clearTimeout(timerRef.current);
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }

  function onPointerDown(e) {
    if (pressedRef.current) return;
    pressedRef.current = true;
    setPressed(true);
    e.preventDefault();
    e.currentTarget.setPointerCapture?.(e.pointerId);
    send();
    if (repeat) {
      timerRef.current = setTimeout(() => {
        timerRef.current = setInterval(send, interval);
      }, initialDelay);
    }
  }
  // We MUST remove the timer on any pointer leave / cancel / up — otherwise dragging the
  // finger off the button keeps firing.
  return {
    pressed,
    handlers: {
      onPointerDown,
      onPointerUp: stop,
      onPointerCancel: stop,
      onPointerLeave: stop,
    },
  };
}

function HoldButton({ keyCode, sendKey, repeat = true, className = "rc-btn", children, ariaLabel }) {
  const { pressed, handlers } = useHoldable(() => sendKey(keyCode), { repeat });
  return (
    <button className={className + (pressed ? " is-pressed" : "")} {...handlers} aria-label={ariaLabel}>
      {children}
    </button>
  );
}

function RemoteCard({ sendKey }) {
  return (
    <section className="card rc-card">
      <h2>Remote</h2>
      <div className="rc-shell">
        {/* Top row: settings + assistant + autofocus */}
        <div className="rc-top-row">
          <HoldButton keyCode={KEY.SETTINGS} sendKey={sendKey} repeat={false} className="rc-btn rc-btn-small" ariaLabel="Settings">
            <SettingsIcon />
          </HoldButton>
          <HoldButton keyCode={KEY.ASSIST} sendKey={sendKey} repeat={false} className="rc-btn rc-btn-small rc-btn-assist" ariaLabel="Assistant">
            <AssistantDots />
          </HoldButton>
          <HoldButton keyCode={KEY.FOCUS} sendKey={sendKey} repeat={false} className="rc-btn rc-btn-small" ariaLabel="Auto-focus">
            <FocusIcon />
          </HoldButton>
        </div>

        {/* D-pad ring — bare circle, no chevrons. Each wedge shows a "pressed" fill on tap. */}
        <div className="rc-dpad">
          <HoldButton keyCode={KEY.DPAD_UP}     sendKey={sendKey} className="rc-dpad-up"     ariaLabel="Up" />
          <HoldButton keyCode={KEY.DPAD_LEFT}   sendKey={sendKey} className="rc-dpad-left"   ariaLabel="Left" />
          <HoldButton keyCode={KEY.DPAD_CENTER} sendKey={sendKey} repeat={false} className="rc-dpad-center" ariaLabel="OK">OK</HoldButton>
          <HoldButton keyCode={KEY.DPAD_RIGHT}  sendKey={sendKey} className="rc-dpad-right"  ariaLabel="Right" />
          <HoldButton keyCode={KEY.DPAD_DOWN}   sendKey={sendKey} className="rc-dpad-down"   ariaLabel="Down" />
        </div>

        {/* Back / Menu / Home */}
        <div className="rc-mid-row">
          <HoldButton keyCode={KEY.BACK} sendKey={sendKey} repeat={false} className="rc-btn rc-btn-small" ariaLabel="Back"><BackIcon /></HoldButton>
          <HoldButton keyCode={KEY.MENU} sendKey={sendKey} repeat={false} className="rc-btn rc-btn-small" ariaLabel="Menu"><MenuIcon /></HoldButton>
          <HoldButton keyCode={KEY.HOME} sendKey={sendKey} repeat={false} className="rc-btn rc-btn-small" ariaLabel="Home"><HomeIcon /></HoldButton>
        </div>

        {/* Volume rocker + mute */}
        <div className="rc-vol-row">
          <HoldButton keyCode={KEY.VOLUME_DOWN} sendKey={sendKey} className="rc-btn rc-btn-vol" ariaLabel="Volume down"><MinusIcon /></HoldButton>
          <HoldButton keyCode={KEY.VOLUME_MUTE} sendKey={sendKey} repeat={false} className="rc-btn rc-btn-small" ariaLabel="Mute"><MuteIcon /></HoldButton>
          <HoldButton keyCode={KEY.VOLUME_UP}   sendKey={sendKey} className="rc-btn rc-btn-vol" ariaLabel="Volume up"><PlusIcon /></HoldButton>
        </div>
      </div>
    </section>
  );
}

function SettingsIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </svg>
  );
}
function FocusIcon() {
  // Viewfinder: four L-shaped corner brackets with a center crosshair, matching the
  // autofocus glyph on the physical Xgimi remote.
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 8 V4 H8" />
      <path d="M16 4 H20 V8" />
      <path d="M20 16 V20 H16" />
      <path d="M8 20 H4 V16" />
      <line x1="12" y1="10" x2="12" y2="14" />
      <line x1="10" y1="12" x2="14" y2="12" />
    </svg>
  );
}

function AssistantDots() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="6" cy="12" r="3" fill="#0a84ff" />
      <circle cx="18" cy="12" r="3" fill="#30d158" />
      <circle cx="12" cy="6" r="3" fill="#ff453a" />
      <circle cx="12" cy="18" r="3" fill="#ffd60a" />
    </svg>
  );
}
function BackIcon() {
  return <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 14L4 9l5-5"/><path d="M4 9h11a5 5 0 0 1 0 10h-3"/></svg>;
}
function MenuIcon() {
  return <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round"><line x1="4" y1="7" x2="20" y2="7"/><line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="17" x2="20" y2="17"/></svg>;
}
function HomeIcon() {
  return <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 11l9-8 9 8"/><path d="M5 10v10h14V10"/></svg>;
}
function MuteIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M11 5L6 9H2v6h4l5 4V5z" />
      <line x1="23" y1="9" x2="17" y2="15" />
      <line x1="17" y1="9" x2="23" y2="15" />
    </svg>
  );
}

function MinusIcon() {
  return <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><line x1="6" y1="12" x2="18" y2="12"/></svg>;
}
function PlusIcon() {
  return <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round"><line x1="6" y1="12" x2="18" y2="12"/><line x1="12" y1="6" x2="12" y2="18"/></svg>;
}


function Dialog({ spec, onClose }) {
  const [value, setValue] = useState(spec.defaultValue ?? "");
  const inputRef = useRef(null);
  useEffect(() => {
    if (spec.kind === "prompt") {
      const t = setTimeout(() => inputRef.current?.focus(), 30);
      return () => clearTimeout(t);
    }
  }, [spec.kind]);

  function submit() {
    if (spec.kind === "prompt") onClose(value);
    else onClose(true);
  }
  function cancel() { onClose(spec.kind === "prompt" ? null : false); }

  return (
    <div className="modal-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) cancel(); }}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={spec.title}>
        {spec.title && <h3 className="modal-title">{spec.title}</h3>}
        {spec.message && <p className="modal-message">{spec.message}</p>}
        {spec.kind === "prompt" && (
          <input
            ref={inputRef}
            className="modal-input"
            type="text"
            value={value}
            placeholder={spec.placeholder || ""}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") submit();
              if (e.key === "Escape") cancel();
            }}
          />
        )}
        <div className="modal-actions">
          <button className="modal-btn" onClick={cancel}>Cancel</button>
          <button className={`modal-btn modal-btn-primary${spec.danger ? " modal-btn-danger" : ""}`} onClick={submit}>
            {spec.okLabel || "OK"}
          </button>
        </div>
      </div>
    </div>
  );
}

function Slider({ label, accent, value, onChange }) {
  const [local, setLocal] = useState(value);
  useEffect(() => setLocal(value), [value]);

  return (
    <div className="slider">
      <div className="slider-row">
        <label>{label}</label>
        <span className="slider-value" style={{ color: accent }}>{local}</span>
      </div>
      <input type="range" min="0" max="100"
        value={local}
        onChange={(e) => setLocal(Number(e.target.value))}
        onMouseUp={(e) => onChange(Number(e.target.value))}
        onTouchEnd={(e) => onChange(Number(e.target.value))}
        style={{ accentColor: accent }} />
    </div>
  );
}
