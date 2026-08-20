# The live incident stream

The last piece of the event pipeline, and the first one built for a reader rather than for the
system. Kafka gave incidents a second way *in*; this gives them a way *out* that nobody has to
poll for.

It exists to answer two questions that a bare "push the incident" would have skipped:

1. **What does a client see when it connects mid-stream?**
2. **What does a null `resolution` mean when nobody asked for it?**

Both answers turned out to be the same answer, which is why the frame has an envelope.

- Endpoint registration: `src/main/java/com/shivansh/incidentresponder/websocket/WebSocketConfig.java`
- Connection lifecycle and replay: `.../websocket/IncidentWebSocketHandler.java`
- Fan-out: `.../websocket/IncidentBroadcaster.java`
- Wire format: `.../websocket/IncidentFrame.java`

## Running it

Nothing new to start. The endpoint comes up with the app.

```
ws://localhost:8080/ws/incidents
```

A command-line client is the easiest way to watch it, and it needs no origin configuration
because it sends no `Origin` header:

```bash
websocat ws://localhost:8080/ws/incidents
```

To see a frame without spending quota, `POST /api/analyze` with a log dump that is already in
the Redis cache — the Analyzer costs nothing on a hit and the push is identical either way. For
the Kafka path, run with the `simulator` profile as `docs/ingestion.md` describes; every event
that finishes lands on the socket.

## The wire format

One JSON object per frame. Three types.

```json
{ "type": "connected", "backlog": 10 }
{ "type": "history",  "incident": { … } }
{ "type": "incident", "incident": { … } }
```

`incident` is an `IncidentResponse` — **byte-identical to what `POST /api/analyze` and
`GET /api/incidents/{id}` return**, not a push-shaped variant of it. That identity is asserted
rather than assumed: `IncidentStreamTest` opens a real socket, pushes an incident, fetches the
same document over REST and compares the two JSON trees. Neither side could establish it alone,
the same argument `SimulatedEventWireFormatTest` makes about the producer and the consumer.

It is guaranteed by construction as well as by test. `IncidentBroadcaster` is **injected with the
application's `ObjectMapper`** rather than building its own — deliberately the opposite of what
`IncidentEventParser` and `AnalysisCache` do, because those two need semantics that *differ* from
the REST path and this one needs output that is *identical*. The test proved the point the hard
way: its first version built a mapper with `Jackson2ObjectMapperBuilder.json().build()` and
failed, because `WRITE_DATES_AS_TIMESTAMPS` is disabled by Spring **Boot**, not by the builder.
The stand-in wrote `analyzedAt` as `1.7871921E9` where the application writes
`2026-08-20T02:15:00Z`. The production code was right and the replica was wrong — which is
exactly the drift sharing one mapper prevents, reproduced by accident in the test written to
guard against it.

### Raw WebSocket, not STOMP

`spring-boot-starter-websocket` ships both. STOMP was declined.

It is a second wire protocol layered on the socket — frames, destinations, subscription
lifecycle — and adopting it would mean week 4 ships `@stomp/stompjs` and learns that protocol
**on top of** the JSON contract. What it is actually for is routing: many destinations, per-user
queues, server-side subscription filtering. This endpoint is one broadcast of one type to every
dashboard. There is nothing to route.

So the client is `new WebSocket(url)` with no dependency at all. SockJS is declined for the same
reason plus one more: it emulates WebSocket over XHR polling for browsers that lack it, and every
browser week 4 targets has had it for a decade.

## Question 1: what a connecting client sees

**A backlog of the ten most recent incidents, replayed as `history` frames.**

The deciding fact is the quota, not preference. At **roughly ten events a day** — the cost note
in CLAUDE.md, unchanged — the gap between two incidents is measured in hours. A live-only stream
leaves the dashboard blank for most of a working day, and **a blank dashboard cannot be told
apart from a broken one**. That is the whole trade: replay costs a Mongo query per connection and
some duplicate-handling in the client; live-only costs the ability to tell "connected, nothing has
happened" from "the socket died an hour ago".

The `connected` frame covers the residue. Even with `backlog: 0`, or on an empty database, a
client is told the channel works.

### On a fresh database the backlog is entirely seeded history

Worth knowing before it looks like a bug. The newest seeded `analyzedAt` is **2026-08-09** and
the seed runs back to February, so ten slots fill with `INC-` documents long before any live
incident competes for them. They are real resolved incidents and showing them is honest — but
every one of them has a null `resolution`, and that is what makes question 2 unavoidable.

### Registered first, read second

Between reading the backlog and joining the broadcast there is a window, and an incident
finishing inside it goes one of two ways:

| Order | An incident finishing in the window |
|---|---|
| Read the backlog, then register | **In neither.** Lost. |
| **Register, then read the backlog** | **In both.** Sent twice. |

The second is chosen, and it is the one line in `IncidentWebSocketHandler` worth a test —
`registersTheSessionBeforeReadingTheBacklog` pins it.

Every frame carries the incident's `id`, so a dashboard keyed on id absorbs a duplicate by
replacing a row. A dropped incident is invisible to the client and unrecoverable: nothing tells
it something is missing and nothing lets it ask. Given a choice between a failure the client can
handle and one it cannot even detect, take the first.

The REST caller receives its own incident twice for the same reason and it costs nothing extra —
once as the HTTP response, once as a frame. Idempotency on id was already required.

### Nothing on this stream is ordered

The consequence. A live `incident` frame can overtake the replay, so **arrival order is not
chronological and `connected` is not guaranteed to arrive first**.

> **Clients sort by `analyzedAt`, never by arrival.**

This would have been true anyway — WebSocket orders frames from one sender and there are two
here. The replay is sent oldest-first so a client appending naively still ends with the newest at
the bottom, but nothing may *rely* on that.

`analyzedAt` and not `firstOccurrence`: the latter is read out of the logs by the model, can be
null and can be wrong. `analyzedAt` comes off the server clock and is the only field in the
document trustworthy enough to order by.

## Question 2: what a null `resolution` means

**It pushes as-is — `IncidentResponse` is untouched — and the frame type says which kind of null
it is.**

`resolution` is null in two situations that look identical on the wire and mean opposite things:

- **The Resolver ran and failed** — a 429 that survived the 15s retry, a timeout, an unusable
  reply, a blank root cause. `ResolverService` absorbs all of these and returns null so the
  diagnosis is not lost with them.
- **The Resolver was never asked** — the twenty seeded incidents, loaded straight into Mongo,
  carrying a human write-up in `resolutionNotes` instead.

On `GET /api/incidents/{id}` that ambiguity is tolerable and `IncidentResponse` documents it as
such: the caller named the id, so they know what they asked for. **On a push stream nobody asked
for anything.** A dashboard rendering both as one empty box tells whoever is on call that the
system tried and gave up on an incident a human fixed in February.

The two cases separate perfectly by **which sender the frame came from**, so that is what the
type says:

| `type` | a null `resolution` means |
|---|---|
| `history` | **never asked** — show `resolutionNotes` instead |
| `incident` | **tried and failed** — the Resolver was called and produced nothing usable |

### There is no "not yet"

The question asked whether the client needs to distinguish "no resolution yet" from "resolution
failed". **There is no "yet" on this path.** `IncidentService.analyzeAndRecord` is synchronous:
the Resolver has finished, one way or the other, before the incident is stored, and the push
happens after storage. A live frame with no resolution has already failed. The client needs no
pending state and no spinner for one.

If an asynchronous Resolver is ever added, *that* is when a fourth frame type earns its place —
not before.

### Why not a field on `IncidentResponse`

The obvious alternative was a `resolutionStatus` enum on the response itself. Rejected on three
counts:

- It changes the **REST** contract to solve a **push-only** problem. The shared type is shared
  precisely so the two endpoints cannot drift.
- On the live path it would have exactly one possible value today, `FAILED`. This project's own
  record on that shape is `AgentResolution.PRECEDENTS_AGREE`: a required field with nothing to
  say is a field that gets filled in anyway.
- It duplicates what the envelope already carries.

The envelope is one field. The payload under it is unchanged, so "the frontend learns one
object" survives — it learns one object and one discriminator, and it needed the discriminator
anyway to know not to alert on backfill.

## Where the push lives, and why it is not in the consumer

Step 4 asked for finished **Kafka** incidents to reach the dashboard. The broadcast is in
`IncidentService.analyzeAndRecord` instead, one level below the consumer.

Putting it in `IncidentEventConsumer` would have satisfied the request literally while making the
two entry points behave differently — and "two entry points that both diagnose incidents must not
be able to diagnose them differently" is the sentence that put the pipeline in `IncidentService`
in the first place. A log dump submitted through `POST /api/analyze`, which is how a human uses
the week 4 dashboard, would never have appeared in it.

So the consumer still does exactly three things: parse, delegate, log. Kafka's incidents reach the
socket because *every* incident does.

## Failing open, in both directions

**A dead socket must never cost a diagnosis.** By the time the broadcast runs, two Groq calls have
been billed and the incident is in Mongo. Losing that to a browser tab someone closed would be the
worst trade in the pipeline. `IncidentBroadcaster.broadcast` catches everything — serialisation
failure, broken pipe, a client that stopped reading — and `IncidentService` calls it without a
try/catch on purpose, so the guarantee lives in one place instead of being restated at each call
site where it could be restated differently. The same arrangement `ResolverService` already has.

**A dead Mongo must never cost a connection.** A failed backlog query logs and connects with an
empty replay. A dashboard that opens blank and fills as incidents arrive is degraded; one that
cannot connect at all is broken.

The push is also strictly **after** the save. Storage failure still fails the whole call, unchanged
from week 2 — pushing first would put a row on every dashboard for an incident that then failed to
persist and cannot be opened at `GET /api/incidents/{id}`.

## Two things that bite, both handled

**`WebSocketSession.sendMessage` is not thread-safe**, and this endpoint has two writers by
design: the connection thread sending the replay and the Kafka consumer thread broadcasting.
Concurrent sends interleave and corrupt frames. Every session is wrapped in a
`ConcurrentWebSocketSessionDecorator`, which serialises them behind a queue.

That wrapper also solves a problem the raw session would have pushed onto the pipeline. The
broadcast runs inline inside `analyzeAndRecord`, which is shared with `POST /api/analyze` — so on
a raw session **one slow dashboard would add its own latency to every REST request**. The
decorator's limits cap it: 5s per send, 512KB buffered, then the session is terminated. At a few
kilobytes a frame that buffer is over a hundred frames deep, which at this event rate is more than
a day. A client that far behind is not slow, it is gone.

**The decorator does not equal its delegate.** Spring hands `afterConnectionClosed` the *raw*
session, not the wrapper that was registered, so a `Set<WebSocketSession>` would never remove
anything and every closed tab would leak a session until the next broadcast noticed.
`IncidentBroadcaster` keys its map on `WebSocketSession.getId()` instead, which both objects
report identically. `deregistersADecoratedSessionGivenTheRawOneSpringHandsBackOnClose` pins it.

## Origins are not CORS

The handshake is an HTTP request but is **not** subject to CORS — the browser will not refuse a
cross-origin connection, so the server has to. Spring's default is same-origin, which would have
refused week 4's Vite dev server on 5173 with a handshake failure that reads like a wrong URL.

Two things about the setting that are easy to get wrong:

- **A non-empty list replaces the same-origin default rather than extending it.** Listing only
  the Vite port silently locks out `http://localhost:8080`, this application's own origin and the
  one a browser console is on when someone first tries the endpoint by hand. It is listed for
  that reason.
- **`*` is not the shortcut it looks like.** It lets any page on the internet open a stream of
  this system's incident data, and it is the kind of default that survives into production
  because nothing ever appears to be wrong with it.

`incident.websocket.allowed-origins` overrides the list when the frontend moves.

## The stream is one-way

Text sent by a client is logged at DEBUG and dropped. A client with something to say uses
`POST /api/analyze`, which is a request with a response and an error contract; a second command
channel here would be a second API with neither.

## Known limitations

Deliberately left, not overlooked.

**Every connection replays the same backlog from scratch.** There is no cursor and no "everything
since incident X", so a client that reconnects after an hour gets the most recent ten and no way
to discover what it missed beyond them. At ten events a day ten slots is over a day of history,
so the gap is theoretical today — but it is a real gap, and a `since` parameter on the handshake
is where it would be closed.

**Nothing survives a restart.** Sessions are in-memory in one process. A restart drops every
dashboard, and they reconnect into a fresh backlog. Fine for one instance; a second instance
would need a shared fan-out, and Redis is already here for it.

**The backlog query is unindexed.** `findRecent` sorts on `analyzedAt` with no index behind it.
At 21 documents that is a collection scan of nothing. It stays correct and stops being free at a
few thousand.

**Backpressure is measured but not observed.** The 5s/512KB limits are reasoned from frame size
and event rate; no run has actually driven a client into either. Like the retry timing in
`docs/ingestion.md`, the numbers are a budget rather than an observation.

**No authentication.** Anyone who can reach the port can read every incident this system has
diagnosed. True of the REST endpoints too, and it is a week 4 concern, but the socket makes it
continuous rather than per-request.

## Status: built and verified offline 2026-08-20

**194 tests pass with `mvn test`**, all offline — no broker, no Groq call, no Mongo, no quota.
Was 147 at the end of step 2.

| Check | How |
|---|---|
| Session registered **before** the backlog is read | `IncidentWebSocketHandlerTest` |
| Replay tagged `history`, sent oldest-first, sized by config | `IncidentWebSocketHandlerTest` |
| Backlog sorted on `analyzedAt`, `backlog: 0` never touches Mongo | `IncidentWebSocketHandlerTest` |
| An unreachable Mongo still yields a live connection | `IncidentWebSocketHandlerTest` |
| A failed send drops that client and reaches the others | `IncidentBroadcasterTest` |
| A dead socket cannot fail a diagnosis | `IncidentBroadcasterTest` |
| A decorated session deregisters from the raw one | `IncidentBroadcasterTest` |
| The embedding never reaches the wire | `IncidentBroadcasterTest`, `IncidentStreamTest` |
| A null resolution is a present null, not an absent field | `IncidentBroadcasterTest` |
| The push happens after the save, and carries the stored id | `IncidentServiceTest` |
| **A frame's payload is identical to the REST body** | `IncidentStreamTest` |

The last one is the check neither side could make alone, and it runs over a real handshake with
a real WebSocket client against a real embedded Tomcat — which is also what proves
`WebSocketConfig` actually puts the handler on the path it claims.

**The full path — a Kafka event finishing and landing on a connected dashboard — has not been
run**, because it costs ~9,800 tokens and the app is started by hand. Every segment of it is
covered above; the join is not. Nothing here claims otherwise.
