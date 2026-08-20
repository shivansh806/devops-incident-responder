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

It is safe by luck as much as design, and worth knowing which:

| If Vite forwards `Origin` as | Spring sees | Listed? |
|---|---|---|
| untouched — `http://localhost:5173` | the browser's origin | yes |
| rewritten to the target — `http://localhost:8080` | the app's own origin | yes |

**Both possible behaviours are already covered**, because `WebSocketConfig` lists the app's own
origin as well as the dev server's — which it does for an unrelated reason (a non-empty list
replaces the same-origin default, so leaving 8080 off would lock out a browser console on the
app itself). A decision made in week 3 for one reason turns out to cover this one too. That is
luck, and it is recorded as luck: **which of the two rows is actually happening has not been
determined**, only that the handshake succeeds either way. If the allowlist is ever trimmed,
this is what breaks.

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

## Status: builds clean, not run against the backend

`npm run build` passes. **Nothing here has been exercised against a running system** — not the
proxy, not the preflight-free `POST`, not the merge under a real duplicate, not the
`type: "incident"` null-resolution branch, which needs a rate-limited Resolver to appear at
all.

The one thing step 1 verified live is the direct handshake from 5173 without the proxy. The
proxied handshake is a different path and is unverified. Listed plainly because this file's
own conventions demand it, and because the proxy claim above is the one that would fail first.
