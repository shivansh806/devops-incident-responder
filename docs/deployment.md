# One image, one origin

Week 4, step 3. The whole stack from one command, with the dashboard bundle and the API served
by the same process — the topology the Vite dev proxy has been standing in for since step 2.

- Build: `Dockerfile`
- Stack: `docker-compose.yml`, the `app` service
- Build context exclusions: `.dockerignore`

## Running it

**Nothing about the development loop changed.** This is still Kafka and Redis only, with the
app in IntelliJ:

```bash
docker compose up -d --wait
```

The whole stack, on one origin, is behind a profile:

```bash
docker compose --profile app up -d --wait --build
```

Then http://localhost:8080. No Vite, no second port, no proxy.

The profile is the point. Without it the `app` service would take port 8080 every time Kafka
came up and collide with the IntelliJ instance, and CLAUDE.md is explicit that the app is run
by hand there. A default `up` that quietly broke that would be a bad trade for a shorter demo
command.

Mongo stays on Atlas, reached with `MONGODB_URI` from `.env`. It is deliberately not in
compose: the vector index has no plain community-Mongo equivalent, which is the same reason
docs/infra.md gives for leaving it out in week 3.

## The multi-stage build

Three stages, because the thing that builds an artifact is not the thing that runs it.

| Stage | Base | Produces |
|---|---|---|
| `frontend` | `node:22-alpine` | `dist/` — the hashed bundle |
| `backend` | `maven:3.9-eclipse-temurin-21` | the fat jar, bundle inside it |
| `runtime` | `eclipse-temurin:21-jre` | the image that ships |

Node, Maven, `node_modules` and `~/.m2` all stay behind — on the order of a gigabyte of
build-time-only weight that the runtime has no use for.

### Manifests before sources, in both build stages

`package.json` and `package-lock.json` are copied and installed *before* `frontend/`, and
`pom.xml` is resolved *before* `src/`. Docker caches per instruction, so this ordering means a
one-line change in `App.jsx` re-runs the bundle build but **not** `npm ci`, and a change in a
Java file does not re-resolve every Spring dependency.

Copy the sources first and every build is a cold build. Resolving this project's dependencies
is the slowest step in it — the embedding jars alone are ~200MB.

`npm ci` rather than `npm install`: it installs exactly what the lockfile says and fails if the
two disagree, which is the difference between a reproducible image and "whatever resolved
today".

### The build does not need a working local toolchain

`mvn` is not on this machine's PATH and `JAVA_HOME` is unset. Irrelevant here — Maven runs
inside the build stage. The image builds on a machine that cannot build the project locally,
which is most of the argument for containerising a build in the first place.

### Tests are skipped in the image

`-DskipTests`. The image build is not this project's test gate; `mvn test` is, and all of it
runs offline. Running the suite on every image build would double the build to re-prove what a
green run already proved.

Note that `-DskipTests` still **compiles** test sources, so a test that does not compile fails
the image build. `-Dmaven.test.skip=true` would not, and is not used for that reason.

### Size

The image is large and that is expected rather than a fault of the staging. The local embedding
model is ~200MB of jars — 83MB model, 93MB onnxruntime, 19MB DJL tokenizer — which CLAUDE.md's
cost note said to budget for here rather than be surprised by. The `-q` quantized model is the
lever if it ever matters.

### Runtime hardening

Non-root (`uid 10001`). The container holds an API key in its environment and the incident
corpus in its memory.

`curl` is installed for one reason: the healthcheck. Without one, `--wait` returns as soon as
the JVM process exists, which is well before Spring can answer a request — the same trap
docs/infra.md records for Kafka, where a plain `up -d` returned about ten seconds early and
read as a config error. `/api/hello` already existed and is exactly the right shape for it.

## How the bundle gets served

**Spring Boot serves `classpath:/static/**` with no configuration at all.** The build copies
`dist/` into `src/main/resources/static/` before packaging, so `/` returns `index.html` and
`/assets/index-*.js` returns the bundle. No controller, no `WebMvcConfigurer`, no nginx.

That is what makes one origin **structural rather than arranged**: the same process that
answers `/api/analyze` and `/ws/incidents` hands out the page that calls them. There is no
second server whose configuration could drift from the first.

No path collides. `/api/**` and `/ws/incidents` are mapped handlers and static resources are
the fallback; `HelloController` sits at `/api/hello` and nothing maps `/`.

### Why not nginx in front

It would be a second container and a second proxy configuration that has to replicate what the
Vite proxy does — including the WebSocket upgrade block, `proxy_set_header Upgrade` and
`Connection`, which is its own well-known way to lose an afternoon. It would also make "one
origin" a property of the reverse proxy rather than of the application.

### The limitation this leaves

**There is no SPA fallback.** A deep link to a client-side route would 404, because Spring would
go looking for a file of that name. The dashboard has no router, so this cannot bite today — but
adding one means adding a forwarding controller in the same change, not afterwards.

## The allowlist in this topology

**It needs no entries, and the container sets it empty.**

The browser loads `http://localhost:8080` and opens a socket back to `http://localhost:8080`.
Origin equals target. Spring's rule when **no** origins are configured is same-origin only,
which is both correct here and **stricter** than any list that could be written.

Three things about that which are easy to get backwards.

**Listing `http://localhost:8080` explicitly is worse than empty, not equivalent.** It breaks
the moment the app is reached as `127.0.0.1:8080` or over a LAN address. The same-origin default
follows whatever host actually served the page; a literal string cannot.

**Leaving the dev ports in would ship the exact failure `addCorsMappings` was rejected for.**
docs/frontend.md turned that option down partly because it leaves localhost entries in
production config that outlive their reason and get widened to `*` by someone who cannot tell
what they are for. Rejecting the option and then shipping its failure mode would be a poor
trade.

**Empty has to survive the trip through an environment variable**, and this is where it could
have failed silently. `INCIDENT_WEBSOCKET_ALLOWED_ORIGINS=` converting to `[""]` rather than
`[]` would be a **non-empty** allowlist containing an origin no browser ever sends — so Spring
would not fall back to same-origin, it would compare every incoming `Origin` against `""` and
refuse all of them, **including the same-origin connection this whole topology depends on**. The
dashboard would load and then fail to open its socket, which reads as a broken backend.

Rather than depend on the conversion, `WebSocketConfig.effectiveOrigins()` filters blanks, so
the empty case is reached by construction. `WebSocketConfigTest` pins it — including the
negative, that a blank origin is never emitted.

**Development is unchanged.** The `@Value` default still carries `5173`, so `npm run dev` and
the Vite proxy work exactly as they did. The two topologies now differ in one environment
variable.

## What week 3 had already provisioned

The containerised app talks to `kafka:29092` and `redis:6379`, and neither needed a change to
the broker configuration.

`KAFKA_ADVERTISED_LISTENERS` has carried `PLAINTEXT://kafka:29092` since week 3 step 1, with the
comment "containers on this network (week 4)". That was not decoration. A Kafka client uses
`bootstrap.servers` once to fetch metadata and then reconnects to **whatever address the broker
advertised**, so the internal address had to be correct before anything used it — it could not
be bolted on now. docs/infra.md has the full argument, including why the published and
advertised ports must be the same number.

`depends_on` uses `condition: service_healthy` rather than the default `service_started`. Both
dependencies have real healthchecks and Spring Boot fails its Kafka connection at startup, so
waiting for a process to exist rather than to answer would reproduce the same race.

## Secrets

`.env` is the first line of `.dockerignore`, and that is load-bearing. Compose injects
`GROQ_API_KEY` and `MONGODB_URI` as container environment at **run** time via `env_file`.
Copying `.env` into an image **layer** would leave it readable by anyone who can pull the image,
and deleting it in a later layer would not remove it from the earlier one.

On the host the app reads `.env` through `spring-dotenv`. In the container there is no file to
read and the same values arrive as ordinary environment variables, which the existing
`${MONGODB_URI:...}` placeholders already resolve. No application change was needed for that.

## Status: verified live 2026-08-20

`docker compose --profile app up -d --wait --build`, then http://localhost:8080. The page
loads, the socket connects, and the backlog replays.

| Check | Status |
|---|---|
| `docker compose config` parses | verified |
| Default `up` resolves to kafka + redis only | verified |
| `--profile app` resolves to all three | verified |
| `INCIDENT_WEBSOCKET_ALLOWED_ORIGINS` renders as an explicit `""` | verified |
| Image builds through all three stages | verified — 358MB, jar 265MB |
| Bundle lands where Spring looks for it | verified — see below |
| Container serves the bundle | **verified live** |
| Same-origin WebSocket with an empty allowlist | **verified live** |
| Atlas reachable from inside the container | **verified live** |
| Kafka reachable at `kafka:29092` from the app container | **verified live** |
| Atlas vector search from the app container | **verified live** |

### The empty allowlist, and why it took two things to close

Spring falls back to same-origin exactly as reasoned, so the container needs no origin entries
at all.

Worth noting how that was established, because neither half was sufficient.
`WebSocketConfigTest` proves `effectiveOrigins()` hands Spring an **empty array** rather than a
one-element array holding `""`. This run proves what Spring **does** when handed an empty array.
The test could have passed while Spring rejected the empty case — which is the exact failure
being guarded against, a dashboard that loads and then cannot open its socket. Only the pair
rules it out. Same shape as `IncidentStreamTest`'s argument in docs/websocket.md: the join is
the thing neither side could assert alone.

### The bundle

Inside the packaged jar:

```
BOOT-INF/classes/static/index.html
BOOT-INF/classes/static/assets/index-DgBOFf0I.css
BOOT-INF/classes/static/assets/index-Brd646Mh.js
```

`BOOT-INF/classes/` **is** the classpath root of a Boot fat jar, so that is where
`classpath:/static/**` resolves. The asset hashes match the local `npm run build`, confirming
the Node stage built this source rather than something stale, and `index.html` references
`/assets/…` **root-absolute** — correct because the bundle is served from the application root.
A Vite `base` other than `/` would break here and is the first thing to check if a future build
404s on its own assets.

### Atlas

The backlog replaying is the evidence: the connect path runs a Mongo query against the seeded
corpus. So `MONGODB_URI` arriving as ordinary container environment — rather than through
spring-dotenv reading a `.env` file that is not in the image — works.

### Kafka, settled

**Verified 2026-08-20.** One event through the overlay below: all four log lines in order,
~10 seconds end to end, resolved against INC-2491 and INC-2179, stored. `kafka:29092` resolves
and serves from inside the app container.

Worth noting what the *earlier* run could not have told us. A page load, a socket and a backlog
replay never touch the broker — and **an unreachable broker would not have prevented any of
them**, because Spring Kafka's listener container retries in the background and the application
context comes up regardless. A green dashboard and a dead broker look identical from outside.
That is why this needed its own event rather than being read off the first run.

Three things the event established beyond the address:

**Atlas vector search works from the container, which the backlog had not shown.** Precedents
came back as INC-2491 and INC-2179. The connect backlog is a `find` with a sort; retrieval is a
`$vectorSearch` aggregation against the Atlas index. Different operation, different failure
modes — the first succeeding says nothing about the second.

**An absent `service` field means "infer the origin", now seen end to end.** The scenario
withheld it and the model inferred the service rather than receiving one.
`IncidentEventConsumerTest` pins that offline; this is the first time it has run through Kafka
in the container.

**No rate limit on this event, and that is one observation rather than a finding.** ~10 seconds
end to end means no 429: a refusal costs a 15s backoff before the retry, so anything under 15s
rules one out. The plausible reading is that the bucket was idle and the Analyzer's own latency
refilled enough to fit the Resolver — 8,000 less ~5,121, plus ~133/s across the Analyzer call,
lands within a few hundred tokens of the ~3,600 the Resolver wants. **That arithmetic fits; it
was not measured.** The margin it describes is thin enough that the same event could go the
other way, so a future refusal here is expected behaviour and not a regression. The burst item
in CLAUDE.md stays open.

## Running the simulator against the container

Still useful after the fact — this is how the run above was done, and how to produce a live
event for the demo. One event, ~9,800 Groq tokens:

```bash
docker compose -f docker-compose.yml -f docker-compose.simulator.yml --profile app up -d --wait
```

```bash
docker compose logs -f app
```

**One event, not two.** The open question is whether `kafka:29092` resolves and serves from
inside the app container, and a single event crosses it twice — the simulator produces over it
and the consumer fetches back over it. A second event would re-test the 120s pacing, which is a
different open question.

Afterwards, return to the normal stack. The ordinary command recreates the container without
any of the overlay:

```bash
docker compose --profile app up -d --wait
```

### What proves it

Four lines, in this order. The third is the one that settles the question.

| Line | What it proves |
|---|---|
| `Simulator starting: 1 event(s) to 'incident-events'…` | the profile is active; proves nothing about Kafka yet |
| `Simulated event 1 of 1: <SCENARIO> on <service>…` | **produce** succeeded over `kafka:29092` |
| `Consuming incident event <id> from incident-events-0@<offset>…` | **consume** succeeded — the round trip is real |
| `Incident event <id> stored as <mongoId> (<ErrorType> on <service>)` | the full pipeline ran and persisted |

Then on the dashboard at http://localhost:8080, a new incident appears at the **top** of the
feed as a `type: "incident"` frame — live, not `history`, so it renders a real resolution rather
than a human's `resolutionNotes`.

### The failure to watch for is a hang, not an exception

If `kafka:29092` were wrong the symptom would **not** be a stack trace. docs/infra.md records
this shape: a client uses `bootstrap.servers` once to fetch metadata and then reconnects to
whatever the broker advertised, so a mismatch lets the first connection succeed and then leaves
the produce hanging.

So: `Simulator starting` followed by silence, with no `Simulated event 1 of 1` line, is the
advertised-address failure. `Simulated event 1 of 1` followed by silence, with no `Consuming`
line, is a consume-side problem instead. Neither reads as an error, which is exactly why the
absence of a line matters more here than the presence of one.

**The measured run took ~10 seconds end to end**, so that is the shape to expect on an idle
bucket. Allow considerably longer before calling it a stall, though: the gap between
`Consuming` and `stored as` is two Groq calls and, if the Resolver is refused, a 15s backoff
plus a second attempt. A run that takes 30-40s is a rate-limited one behaving correctly, not a
hang.

### One event costs full price, every time

The Redis cache does not help here, and this is worth knowing before planning the recording
around it. `IncidentLogRenderer` anchors every render to `Instant.now()` and fills the hostname,
`{id}` and `{#40-120}` from a `RandomGenerator`, so the same scenario rendered twice is
different log text and therefore a different cache key. The simulator also shuffles scenario
order, so a replay is not even the same scenario.

Pasting an identical dump into the dashboard form **is** a cache hit and **is** free. Simulated
events are not. The daily 100,000 has to cover this verification run and the recording.
