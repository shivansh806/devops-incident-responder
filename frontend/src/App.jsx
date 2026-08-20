import { useEffect, useRef, useState } from 'react';

// Vite exposes env vars to browser code only when they are prefixed VITE_. Anything
// without that prefix stays on the build machine, which is what stops a stray secret
// in .env from being compiled into a public bundle.
const WS_URL = import.meta.env.VITE_WS_URL ?? 'ws://localhost:8080/ws/incidents';

const STATUS_STYLES = {
  connecting: 'bg-amber-400/15 text-amber-300 ring-amber-400/30',
  open: 'bg-emerald-400/15 text-emerald-300 ring-emerald-400/30',
  closed: 'bg-slate-400/15 text-slate-300 ring-slate-400/30',
  error: 'bg-rose-400/15 text-rose-300 ring-rose-400/30',
};

export default function App() {
  const [status, setStatus] = useState('connecting');
  const [detail, setDetail] = useState('');
  const [frames, setFrames] = useState([]);

  // A ref is a mutable box that survives re-renders without causing one. Used here as a
  // frame counter, because deriving the number from state inside a callback would read a
  // stale value captured when the callback was created.
  const seq = useRef(0);

  useEffect(() => {
    // React StrictMode mounts every component twice in development, on purpose, to surface
    // effects that do not clean up after themselves. So this runs twice and opens two
    // sockets - the cleanup below closes the first. Without it you get two live
    // connections and every frame twice, which looks exactly like a server bug.
    let cancelled = false;
    const ws = new WebSocket(WS_URL);

    ws.onopen = () => {
      if (cancelled) return;
      setStatus('open');
      setDetail(WS_URL);
    };

    ws.onmessage = (event) => {
      if (cancelled) return;
      seq.current += 1;
      setFrames((prev) => [
        ...prev,
        { n: seq.current, at: new Date().toLocaleTimeString(), raw: event.data },
      ]);
    };

    // The browser deliberately withholds the reason a WebSocket failed - a refused
    // handshake and an unreachable host are the same empty event here. The close code
    // that follows is the only clue, so it is shown rather than swallowed.
    ws.onerror = () => {
      if (cancelled) return;
      setStatus('error');
      setDetail('handshake or transport failed - see the browser console and the app log');
    };

    ws.onclose = (event) => {
      if (cancelled) return;
      setStatus('closed');
      setDetail(`code ${event.code}${event.reason ? ` - ${event.reason}` : ''}`);
    };

    return () => {
      cancelled = true;
      ws.close();
    };
  }, []);

  return (
    <div className="min-h-screen bg-slate-950 text-slate-200 font-mono text-sm">
      <header className="sticky top-0 border-b border-slate-800 bg-slate-950/90 backdrop-blur px-6 py-4">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <h1 className="text-slate-100 font-semibold">incident stream &mdash; raw frames</h1>
          <span
            className={`rounded-full px-2.5 py-0.5 text-xs ring-1 ring-inset ${STATUS_STYLES[status]}`}
          >
            {status}
          </span>
          <span className="text-xs text-slate-500">{detail}</span>
          <span className="ml-auto text-xs text-slate-500">
            {frames.length} frame{frames.length === 1 ? '' : 's'}
          </span>
        </div>
      </header>

      <main className="px-6 py-5">
        {frames.length === 0 ? (
          <p className="text-slate-500">
            No frames yet. A healthy connection sends a <code>connected</code> frame
            immediately, followed by up to ten <code>history</code> frames.
          </p>
        ) : (
          <ol className="space-y-2">
            {frames.map((frame) => (
              <li
                key={frame.n}
                className="rounded border border-slate-800 bg-slate-900/50 overflow-hidden"
              >
                <div className="flex gap-3 border-b border-slate-800 px-3 py-1.5 text-xs text-slate-500">
                  <span>#{frame.n}</span>
                  <span>{frame.at}</span>
                </div>
                {/* Printed verbatim. This page deliberately does not parse the frame: it is
                    here to show what is actually on the wire, including anything unexpected. */}
                <pre className="px-3 py-2 whitespace-pre-wrap break-all text-slate-300">
                  {frame.raw}
                </pre>
              </li>
            ))}
          </ol>
        )}
      </main>
    </div>
  );
}
