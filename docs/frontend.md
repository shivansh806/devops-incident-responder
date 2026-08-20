# The dashboard

Week 4. The first part of this system built for a person rather than for a process, and the
first one that can put work *into* the pipeline as well as read out of it.

- Stream hook and merge rules: `frontend/src/useIncidentStream.js`
- REST client: `frontend/src/api.js`
- Dev proxy and port pinning: `frontend/vite.config.js`
- Components: `frontend/src/components/`

## Running it

Three things, in this order.

```bash
docker compose up -d --wait
```

Then the backend in IntelliJ, as always. Then:

```bash
cd frontend && npm run dev
```

Open http://localhost:5173. Nothing needs a profile; the `simulator` profile still works and
its events land on the dashboard like any other.

## The cross-origin decision

Step 1 established that **CORS does not govern a WebSocket handshake** — the browser sends
`Origin` and never checks the response for `Access-Control-Allow-Origin`, so only the server
can refuse a cross-origin socket. That made step 1 free: week 3's `setAllowedOrigins` already
listed port 5173.

**Step 2 is where it stops being free.** The submit form does `fetch('/api/analyze')`, and an
XHR *is* governed by CORS. From `http://localhost:5173` to `http://localhost:8080` that is a
cross-origin request with a `Content-Type: application/json` body, which is not a simple
request, which means a `OPTIONS` preflight the backend currently answers with a 403.

Two ways to fix it, and they are not variations on each other.

### Rejected: `addCorsMappings` on the backend

A `WebMvcConfigurer` allowing `http://localhost:5173`. Keeps the browser talking straight to
Spring, so the wire in dev is the wire in production.

Rejected on three counts, in increasing order of weight:

- **It creates a second origin list.** The WebSocket already has one, in `WebSocketConfig`,
  and CORS mappings are a separate mechanism that would need its own. Two lists that mean the
  same thing and drift independently is the failure this project keeps naming — it is the
  same argument that put the pipeline in `IncidentService` so two entry points could not
  diagnose differently. The lists *could* be made to share one property, which blunts this
  but does not remove it: they would still be two enforcement points with two defaults.
- **It ships a permanent production surface to solve a development-only problem.** The
  localhost entries would outlive the reason for them, and dead origin config is exactly the
  kind of thing that gets widened to `*` later by someone who cannot tell what it is for.
  This project already distinguishes a measurement guard from a feature flag for the same
  reason (`incident.cache.enabled`).
- **It gets materially worse when auth arrives.** "No authentication" is already recorded as
  a week 4 concern. The moment a cookie or an `Authorization` header is involved, CORS needs
  `allowCredentials`, which may not be combined with a wildcard origin, and preflight caching
  becomes a thing to reason about. None of that is hard; all of it is work that buys nothing.

### Chosen: the Vite dev proxy

`/api` and `/ws` are both proxied to `http://localhost:8080`. The browser makes every call to
its own origin, so **there is no cross-origin request to permit** — no preflight, no CORS
config, no second list, and no backend change in this step either.

The argument that decides it is not convenience. It is that **relative URLs are what
production actually wants.** Week 4's endpoint is a Docker image serving the built bundle and
the API from one origin, where `/api/analyze` already resolves correctly with no configuration
at all. Absolute URLs would mean threading `VITE_API_URL` through the build and accepting a
class of bug where a shipped bundle points at `localhost:8080`. The proxy makes the production
topology the default and development the special case — handled by one config block that never
ships.

### What the proxy does *not* do

**It does not remove the WebSocket allowed-origins entry**, and believing otherwise is the
trap. The proxy forwards the browser's `Origin` header on the upgrade, so Spring still sees an
origin and still checks it. The proxy buys a relative URL, not permission.

This is now load-bearing rather than trivia: the socket runs through the proxy, so if the
allowlist were wrong the dashboard would not connect at all.

This was first written up as "safe by luck as much as design", on the grounds that Vite might
forward the origin untouched or rewrite it to the target, with both values listed either way.
**That is now determined, and the answer removes the margin rather than confirming it.**

**Vite forwards `Origin` untouched.** Its `rewriteOriginHeader` fires only when the
`rewriteWsOrigin` option is set — a separate, explicitly named option this project does not
set — so Spring sees `http://localhost:5173` on the upgrade:

```js
// node_modules/vite/dist/node/chunks/node.js
const rewriteOriginHeader = (proxyReq, options, config) => {
  if (options.rewriteWsOrigin) { … proxyReq.setHeader("origin", changedOrigin); }
};
```

Read out of the bundled implementation, **not observed on the wire**. The live run confirms the
handshake succeeds but cannot discriminate between the two candidates, because both are
allowed — which is exactly why reading the source was the only thing that could settle it.

**So `http://localhost:8080` in the allowlist is not a safety net for the dashboard.** It is
there for the unrelated week 3 reason that a non-empty list replaces the same-origin default,
and it covers a browser console on the app's own origin, not this. **The 5173 entry alone is
what the dashboard's connection rests on.** Trim it and the dashboard stops connecting, with
nothing behind it.

### When to revisit

If the dashboard is ever served from a genuinely different origin than the API — a CDN, a
separate hostname — the proxy stops applying and real CORS config is needed. The advantage of
deferring is that it would then be written against the actual origins rather than against
`localhost` placeholders. `VITE_WS_URL` is the switch point for the socket half.

## What the dashboard has to get right

Three rules from the contract, all of them things a naive client gets wrong.

### Sort by `analyzedAt`, never by arrival

`useIncidentStream` sorts on every merge and the list is newest-first. Nothing anywhere reads
arrival order.

This is not defensive coding. Per docs/websocket.md the server registers a session *before*
reading the backlog, so a live frame can overtake the replay, and `connected` is not guaranteed
to arrive first. There are two senders on one socket and WebSocket only orders frames from one.
`firstOccurrence` is not the alternative — the model reads it out of the logs, it can be null
and it can be wrong. `analyzedAt` comes off the server clock.

### Duplicates are expected, and are absorbed on `id`

The same register-before-read window means an incident finishing inside it is sent twice. That
ordering was chosen deliberately: a duplicate is a failure the client can handle, a dropped
incident is one it cannot even detect.

So the merge is keyed on `id` and replaces rather than appends. The REST caller also receives
its own incident twice — once as the HTTP response, once as a frame — and the form merges the
response immediately so the row appears the instant the call returns rather than whenever the
socket agrees.

**When both arrive, `live` wins.** The flag only ever decides how a null resolution is read,
and "the Resolver ran and failed" is the more specific claim: a live frame is direct evidence
the Resolver was asked on this call, where a history frame is merely the absence of that
evidence.

### A null `resolution` renders two different ways

The reason `IncidentFrame` has an envelope at all. `type: "history"` means the Resolver was
never asked — the seeded twenty, which carry a human write-up in `resolutionNotes`.
`type: "incident"` means it ran and produced nothing usable.

`IncidentDetail` renders the first as the human's notes and the second as a plain statement
that the Resolver failed and the diagnosis was kept anyway. One empty box for both would tell
whoever is on call that the system gave up on an incident a person fixed in February.

There is no "not yet" and the dashboard has no pending state, because `analyzeAndRecord` is
synchronous — the Resolver has finished before the push happens.

## Precedents are citations, not a second feed

`AgentResolution.similarIncidents` is a list of ids. The detail pane resolves them against the
incidents already in memory, which works because retrieval mostly returns seeded incidents and
those arrive in the connect backlog. An id that is not in memory renders as a bare id rather
than triggering a `GET` — it is a citation, and a dashboard that fires a request per precedent
per selection is a different feature.

The order they appear in is the order the Resolver listed them, and **nothing may read
anything into it**. Retrieval recalls the right cluster reliably and intra-cluster rank is
worth nothing — the three pool incidents sit within 0.03 of each other and the two whose
conclusions agree are the furthest apart. The UI numbers the *suggested actions*, which are a
sequence, and deliberately does not number the precedents, which are a set.

## Design

Plain on purpose. Light background, one accent per severity, no animation, no dark mode.

The constraint is that this gets recorded and watched at a compressed bitrate, where saturated
colour smears and thin light-on-dark text disappears. Severity badges are the only colour, and
they are muted rather than neon. The layout is master-detail so a single frame shows both the
feed and one full diagnosis — a demo that has to scroll to connect a click to its result loses
the thread.

The submit button says `Analyzing…` and a line of text says two model calls can take a minute.
No spinner: there is nothing to animate and a minute-long spinner reads as a hang.

## Cost, stated in the UI

The form carries the number: ~9,800 tokens per analysis against 100k/day, about ten a day, and
an identical log dump is served from the Redis cache for free. That is in the interface rather
than only in this file because the alternative to knowing it is discovering it as a 429 halfway
through a recording.

**Record the demo on a second run.** The cache makes the replay free, which is what step 3 of
week 3 was for.

## Status: verified live 2026-08-20

The dashboard works end to end. The proxied WebSocket handshake connects, the backlog renders
as a sorted feed, the detail panel reads, a live push arrives, and the form's
`POST /api/analyze` goes through the proxy **with no CORS configuration anywhere in the
backend** — `grep` for `addCorsMappings`, `CorsConfiguration` or `@CrossOrigin` across
`src/main` returns nothing.

That settles the proxy decision as measured rather than reasoned, and the POST is the part
that carries the weight. It sends `Content-Type: application/json`, which is **not** a simple
request, so a cross-origin call would have required an `OPTIONS` preflight that nothing here
answers. It completed normally. Had the browser seen two origins, it would have failed before
the request body was ever sent.

| Check | Status |
|---|---|
| Proxied WebSocket handshake | verified live |
| Backlog renders, sorted newest-first | verified live |
| Detail panel, successful resolution | verified live |
| Live push arriving on the socket | verified live |
| `POST /api/analyze` with no CORS config | verified live |
| Which `Origin` Vite forwards | read from source, not the wire |
| `type: "incident"` null resolution | **not verified** |
| Merge under a genuine duplicate | **not verified** |

The last two are worth keeping in view rather than rounding up to "it works".

**The `type: "incident"` null-resolution branch needs the Resolver to fail**, which needs a
rate limit to survive the 15s retry. This run exercised the branch that renders a *successful*
resolution; the failure branch shares nothing with it but a component. It is also the branch
that only appears under exactly the burst condition CLAUDE.md still has open on the revisit
list — so it will most likely first be seen during a recording, which is the argument for
having written it carefully rather than discovering it then.

**The duplicate merge has not met a real duplicate.** The register-before-backlog window is
narrow and nothing forced it. What this run did exercise is the form's own incident arriving
twice — once as the HTTP response, once as a frame — which uses the same merge but is the easy
case: both copies are identical and both are `live`. The hard case is the same id arriving as
`history` and `incident`, where the `live`-wins rule decides how a null resolution reads.
Sharing a code path is not the same as proving one.
