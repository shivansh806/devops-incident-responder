import { useCallback, useEffect, useRef, useState } from 'react';

// Same-origin, so it goes through the Vite proxy in dev and works unchanged when the
// built bundle is served by Spring itself. VITE_WS_URL overrides it for a split deploy.
const WS_URL =
  import.meta.env.VITE_WS_URL ??
  `${window.location.protocol === 'https:' ? 'wss:' : 'ws:'}//${window.location.host}/ws/incidents`;

/**
 * Frame types, from docs/websocket.md. `history` and `incident` carry the same payload
 * and mean opposite things when `resolution` is null, so the type is kept on the record
 * rather than discarded once the incident is unwrapped.
 */
const HISTORY = 'history';
const INCIDENT = 'incident';
const CONNECTED = 'connected';

/** Newest first. Never arrival order - see sortByAnalyzedAt below. */
function byAnalyzedAtDesc(a, b) {
  return Date.parse(b.analyzedAt ?? 0) - Date.parse(a.analyzedAt ?? 0);
}

/**
 * Merge one incident into the list, keyed on id.
 *
 * Two things this has to get right, both from docs/websocket.md:
 *
 * 1. The server registers a session BEFORE reading the backlog, so an incident finishing
 *    in that window is sent twice - once live, once in the replay. Duplicates are
 *    expected and are absorbed by replacing the row with the same id.
 * 2. When the same incident arrives both ways, `live` wins. The flag only ever decides
 *    how a null resolution is read, and "the Resolver ran and failed" is the more
 *    specific claim: a live frame is direct evidence the Resolver was asked on this
 *    call, where a history frame is merely the absence of that evidence.
 */
function upsertInto(list, incident, source) {
  const existing = list.find((i) => i.id === incident.id);
  const merged = {
    ...incident,
    source: existing?.source === 'live' || source === 'live' ? 'live' : 'history',
  };
  const rest = existing ? list.filter((i) => i.id !== incident.id) : list;
  return [...rest, merged].sort(byAnalyzedAtDesc);
}

export function useIncidentStream() {
  const [status, setStatus] = useState('connecting');
  const [detail, setDetail] = useState('');
  const [incidents, setIncidents] = useState([]);
  const [backlogCount, setBacklogCount] = useState(null);

  const socketRef = useRef(null);

  /**
   * Exposed so the submit form can merge its own HTTP response immediately rather than
   * waiting for the socket to say the same thing. The REST caller receives its incident
   * twice by design and idempotency on id was already required, so this costs nothing.
   */
  const upsert = useCallback((incident, source = 'live') => {
    setIncidents((prev) => upsertInto(prev, incident, source));
  }, []);

  useEffect(() => {
    // StrictMode mounts effects twice in development to expose ones that do not clean up.
    // Without the cleanup below that is two live sockets and every incident twice - which
    // the id-keyed merge would hide, making a real duplication bug invisible later.
    let cancelled = false;
    const ws = new WebSocket(WS_URL);
    socketRef.current = ws;

    ws.onopen = () => {
      if (cancelled) return;
      setStatus('open');
      setDetail(WS_URL);
    };

    ws.onmessage = (event) => {
      if (cancelled) return;
      let frame;
      try {
        frame = JSON.parse(event.data);
      } catch {
        // A frame this client cannot parse is a contract problem worth seeing, not
        // worth crashing the dashboard over.
        console.warn('Unparseable frame dropped', event.data);
        return;
      }

      if (frame.type === CONNECTED) {
        setBacklogCount(frame.backlog ?? 0);
        return;
      }
      if (frame.type === HISTORY || frame.type === INCIDENT) {
        if (!frame.incident?.id) return;
        setIncidents((prev) =>
          upsertInto(prev, frame.incident, frame.type === INCIDENT ? 'live' : 'history'),
        );
      }
      // Any other type is ignored rather than rendered. A fourth type would mean the
      // backend gained a state this build does not know how to read.
    };

    // The browser withholds why a WebSocket failed - a refused handshake and an
    // unreachable host produce the same empty event. The close code is the only clue.
    ws.onerror = () => {
      if (cancelled) return;
      setStatus('error');
      setDetail('connection failed');
    };

    ws.onclose = (event) => {
      if (cancelled) return;
      setStatus('closed');
      setDetail(`code ${event.code}${event.reason ? ` — ${event.reason}` : ''}`);
    };

    return () => {
      cancelled = true;
      ws.close();
    };
  }, []);

  return { status, detail, incidents, backlogCount, upsert };
}
