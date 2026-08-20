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
| Kafka reachable at `kafka:29092` from the app container | **not verified** |

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

### Kafka is still open, and this run is not evidence about it

A page load, a socket and a backlog replay do not touch the broker. More to the point, **an
unreachable broker would not have prevented any of them**: Spring Kafka's listener container
retries in the background and the application context comes up regardless. So a green run here
says nothing either way.

Settling it costs about 9,800 tokens — run the `simulator` profile against the containerised app
and watch one event finish and land on the socket. The reason for expecting it to work is that
`kafka:29092` was advertised from week 3 step 1 and is not new in this change. That is a reason
to expect success, not a substitute for observing it.
