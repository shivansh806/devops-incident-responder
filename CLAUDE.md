# Project Context

Read project-brief.md for the full spec before making decisions.

## Tech Stack (do not deviate)
- Java 21, Spring Boot 3.x, Maven
- LangChain4j for LLM integration (Groq / llama-3.3-70b-versatile)
- MongoDB, Redis, Kafka — later weeks only
- React + Vite + Tailwind frontend — Week 4 only

## Rules for this project
- Explain architectural decisions BEFORE writing code
- Build one small feature at a time. Never scaffold the whole project.
- Do not add Kubernetes, Spring Cloud, or extra frameworks without asking me
- Keep commits small and descriptive
- Do not start/stop the Spring Boot app yourself. I run it in IntelliJ.
- I am learning — when you use a new concept, explain it in 2-3 lines

## Current Status
**Week 1: CLOSED.** Analyzer Agent complete and tuned. Final baseline 7/8,
OTHER rate 0/8, confidence spread 0.80–0.95, verbatim clean. Full run log,
every fix and its measured effect, and the closure reasoning are in
docs/baseline.md — read that file before questioning any Week 1 decision.

The one open failure is cache-miss-spike severity (MEDIUM vs expected LOW),
a deliberate known limitation. Do not tune it until the set has more than
one LOW sample.

**Week 2, step 1 done** — MongoDB persistence.
- Incident document (storage) kept separate from LogAnalysis (API message)
- POST /api/analyze persists and returns the stored incident
- GET /api/incidents/{id} reads it back

**Week 2, step 2 done** — 20 resolved past incidents seeded.
- Data in `src/main/resources/seed/incidents.json`, loaded by `IncidentSeeder`
  under the `seed` profile. Run once with `seed` in IntelliJ's Active profiles.
- Idempotent by fixed ids (`INC-2041`…`INC-2507`), not by a marker field.
  `insert()` on the missing ids only — never `save()`, which upserts and would
  wipe the embedding array the next step adds.
- Covers 11 errorTypes, 16 services, all four severities, Feb–Aug 2026.
- Zero LLM calls; `IncidentSeederTest` runs offline.

**Week 2 COMPLETE** — MongoDB, seeded incidents, vector retrieval, Resolver.
- Persistence: Incident (storage) separate from LogAnalysis (API message)
- 20 seeded incidents with deliberate contrast pairs
- Local MiniLM embeddings + Atlas vector search. Recall into the right
  cluster is reliable; intra-cluster rank is NOT — see docs/retrieval.md
- Resolver Agent with decidingEvidence as the first output field.
  Measured pre- and post-fix — see docs/resolver.md before changing
  anything about the Resolver prompt or output shape

Residual: run 8's failure was a correctly stated discriminator attached to
the wrong precedent. Measure future interventions against misattribution,
not blending.

**Week 3, step 1 done** — local infrastructure. Nothing wired to the app yet.
- `docker-compose.yml` at the repo root: Kafka 4.0 in KRaft mode (no ZooKeeper)
  and Redis 7.4. One command — `docker compose up -d --wait`. The `--wait` is
  load-bearing: plain `up -d` returns about ten seconds before Kafka can serve
  a client, and the app then fails to connect in a way that reads as a config
  error.
- Kafka's host listener is on 9092 and Redis on 6379 **because those are Spring
  Boot's defaults** — step 2 needs no connection config at all. Both published
  on `127.0.0.1` only, not every interface.
- Mongo is deliberately not in compose. It stays on Atlas; the vector index has
  no plain community-Mongo equivalent.
- Verified from the host, not via `docker exec` — a container-side check is
  exactly what hides an advertised-address bug. Details in docs/infra.md.

**Week 3, step 2 done** — Kafka ingestion.
- `IncidentEventConsumer` reads `incident-events` and calls the unchanged
  `IncidentService.analyzeAndRecord`. No pipeline logic in the consumer: two
  entry points must not be able to diagnose differently.
- Failure policy is one place, `KafkaConsumerConfig`. Malformed → never retried,
  straight to the DLT. Analyzer failure → one retry after 60s. **Resolver failure
  never reaches it** — `ResolverService` already absorbs its own.
- `max-poll-records: 1` and `max.poll.interval.ms: 600000` are correctness
  requirements, not tuning. Read docs/ingestion.md before changing either.
- Simulator under the `simulator` profile, 8 scenario templates, round-robin over
  a shuffle so variety is guaranteed rather than likely.
- **Every event costs ~6,000 Groq tokens, so ~16 events per day.** Defaults are
  count 3 / interval 40s for that reason.

**2026-08-19 — the model changed under us.** Groq retired
`llama-3.3-70b-versatile`; it 404s. Now on **`openai/gpt-oss-120b`** with
`reasoning-effort: medium` pinned.
- Chosen by measurement, not spec sheet — `ModelCandidateProbe`. It was the only
  sound option left: `qwen/qwen3.6-27b` writes `<think>` inline in `content` and
  cannot drive the prompt-based JSON path, `groq/compound` is an agentic system
  with web search, `allam-2-7b` has a 4k context the Analyzer prompt overflows.
- **docs/baseline.md and docs/resolver.md are now history, not baselines.** Both
  carry a banner saying so. Nothing in them has been re-measured. A change under
  the new model is *not* a regression against those figures — a different model
  is not an intervention.
- **TPM dropped 12,000 → 8,000 and a call got dearer** (5,121 measured, was
  ~2,500–3,400). Pacing was wrong everywhere and is fixed: all three llm
  harnesses 25s → **65s**, simulator 40s → **90s**. Two calls no longer fit in a
  60-second window at all, so this is a different regime, not a tweak.
- **`AnalyzerStabilityTest` passes 3/3 on the new model** — `CACHE_UNAVAILABLE`
  every run, no 429s. The enum's guarantee survives the swap.
- **But the same run showed `affectedService` moving `profile-service` →
  `redis-cache`, 3 of 3.** docs/baseline.md had recorded that specific risk as
  "cleared, not merely untested" — the clearance was a property of llama. The
  origin-vs-reporter prompt rule does not transfer. Note the shape of this: the
  gate went green while a field it does not assert changed underneath.

**Week 3, step 3 done** — Redis caching of Analyzer responses.
- Key is `analysis:v1:<fingerprint>:<input>`, and the fingerprint covers **model id**,
  reasoning effort, both prompts, the output schema and the enum vocabularies — all
  derived reflectively, so a prompt edit or a new `ErrorType` invalidates entries
  without anyone remembering to bump a constant. See docs/caching.md.
- The Resolver is deliberately **not** cached: its input includes retrieved precedent,
  which changes on every call through `analyzeAndRecord`.
- `incident.cache.enabled=false` is a **measurement guard, not a feature flag**, and
  is off for every test. With it on, `AnalyzerStabilityTest` would pass on cache hits
  without measuring anything.
- Fails open in both directions — a dead Redis is a miss, never a failed diagnosis.

**Week 3, step 3b** — rate-limit retry in `ResolverService`, closing the burst.
- Waits **only when refused**, so a call that fits and any cache hit pay nothing.
- 15s, derived: bucket 8,000 refilling at 133/s, deficit = analyzer + resolver −
  8000. Measured deficits 133–308; every one matched Groq's own stated wait, which
  is what makes the arithmetic trustworthy rather than fitted.
- **LangChain4j's own retry covers ~166 tokens of deficit and no more** (`maxRetries=2,
  delayMillis=500, backoffExp=1.5` ≈ 1.25s) and is **not configurable** — no retry
  knob on `OpenAiChatModel`'s builder in 1.18.1 nor on the starter. Two runs landed
  either side of that threshold, which is how the line was established.

**Week 3, step 4 done — WebSocket. WEEK 3 COMPLETE.**
- `ws://localhost:8080/ws/incidents`. **Raw WebSocket, not STOMP** — one broadcast of one type
  to every dashboard has nothing to route, and STOMP would make week 4 learn a second wire
  protocol and ship a client library on top of the JSON. Client is `new WebSocket(url)`.
- **The broadcast is in `IncidentService.analyzeAndRecord`, not in the consumer.** Step 4 asked
  for Kafka incidents to reach the dashboard; putting it in `IncidentEventConsumer` would have
  done that literally while making a `POST /api/analyze` incident invisible to the same
  dashboard. One place, both entry points. The consumer still does exactly three things.
- **On connect a client is replayed the 10 most recent incidents**, because at ~10 events/day a
  live-only stream is blank for hours and a blank dashboard is indistinguishable from a broken
  one. On a fresh database that backlog is **entirely seeded incidents** — newest seeded
  `analyzedAt` is 2026-08-09.
- **The session registers before the backlog is read**, so a duplicate is possible and a gap is
  not. Consequence: nothing on this stream is ordered. Clients sort by `analyzedAt`, never by
  arrival.
- 194 tests pass offline, up from 147. Read docs/websocket.md before changing the frame shape,
  the connect sequence or the origin list.

**Next:** Week 4 — React + Vite + Tailwind frontend.

## Design decisions (do not undo without asking)
- Structured output uses LangChain4j's prompt-based JSON path, not native
  `json_schema` — Groq's schema support is model-dependent. Do not declare
  `RESPONSE_FORMAT_JSON_SCHEMA` without re-testing.
- `serviceName` is optional. When present it overrides the model's
  `affectedService`; when absent the model infers it. Don't make it mandatory —
  a multi-service dump is exactly the case where the caller doesn't know
  the origin.
- Jackson `fail-on-unknown-properties` stays **false** (Week 3 Kafka events
  will carry extra fields). `JacksonConfig` logs a WARN so drops stay visible.
- Incident (storage) and LogAnalysis (API message) are separate types.
    Storage needs the embedding array; the API must never return it.
- Seeded incidents keep human ids (`INC-2103`); real ones get Mongo's 24-char
  hex ObjectId. That difference is the only thing distinguishing seeded history
  from live data — don't add a `source` field for it, and don't renumber.
- Embedding text is `errorType` (spelled as lowercase words) + `affectedService`
  + `keyEvidence`, and **both** the stored side and the query side are built by
  `IncidentEmbeddingText`. One recipe, two overloads — never build the query
  text anywhere else. The bound is what the query side can supply: a new
  incident is a LogAnalysis, so `resolutionNotes` is unusable however
  informative it looks (it would compare postmortem prose against raw symptoms).
  `severity`, `confidence` and `firstOccurrence` are excluded as noise.
  errorType is spelled `connection pool exhausted`, not `ConnectionPoolExhausted`
  — the tokeniser lowercases before splitting, so PascalCase arrives as one
  unspaced string and shatters. Don't "simplify" it back to `jsonValue()`.
- **Nothing downstream may trust intra-cluster rank order.** Retrieval recalls
  the right cluster reliably; the ordering inside it is worth nothing. The three
  pool incidents sit within 0.03 of each other, and the two whose *conclusions*
  agree (INC-2103 / INC-2464, both "don't grow the pool") are the furthest apart
  of the three. Symptom similarity does not track conclusion similarity, and it
  cannot — what separates the causes lives in `resolutionNotes`, which the query
  side does not have. So: don't take "the top match" as the answer, don't weight
  by rank, don't drop a candidate for placing third. Treat a tight cluster as an
  unordered set and let the Resolver discriminate. Tuning will not fix this.
- Embeddings run **locally**, in-process (LangChain4j ONNX, all-MiniLM-L6-v2,
  384 dims). Groq serves no embeddings endpoint at all — verified against their
  models page — so this was never a quota question. Local keeps tests offline
  and adds no fourth credential. It also makes retrieval quality deterministic
  and free to re-measure, unlike the Analyzer baseline.

- **`reasoning-effort` is pinned, not defaulted.** gpt-oss is a reasoning model and
  the effort level is a hidden input Groq could change under us — which would read
  as model drift in a baseline run, the one thing this project's measurement
  discipline cannot absorb. `medium` is what the default resolves to today
  (measured), so pinning changes nothing now and stops it changing later. `low`
  was measured and saves only **7%** (4,757 vs 5,121) because input dominates at
  4,109 tokens and is fixed by the prompt — the lever does not work, so there is
  no reason to take the quality risk. `high` is disqualified: 1,577 completion
  tokens on a *toy* prompt.
- **A Kafka listener's published port and advertised port must be the same number.**
  The app runs on the host, in IntelliJ, not in a container. A client uses
  `bootstrap.servers` once to fetch metadata and then *reconnects to whatever
  address the broker advertised*, so the broker decides where the client goes
  next. Publishing `9094:9092` while advertising `localhost:9092` means the app
  reads metadata from this broker and then produces into whatever else owns 9092.
  The failure is quiet in the worst way: the first connection succeeds and the
  produce hangs, which reads as a broker fault rather than a naming one. If the
  host port ever changes, change `KAFKA_LISTENERS`, `KAFKA_ADVERTISED_LISTENERS`
  and the `ports:` entry together. Read docs/infra.md before touching that block.
- **The Kafka consumer builds its own ObjectMapper, and leniency is configured
  rather than inherited.** Measured, not assumed — `KafkaObjectMapperProbe` and
  docs/ingestion.md. A bare `new ObjectMapper()` **rejects** this project's own
  events: `FAIL_ON_UNKNOWN_PROPERTIES` defaults to *on* in Jackson and is off in
  this app only because Spring Boot turns it off. So the old note's "give it its
  own mapper", taken literally, would have broken the forward compatibility it was
  protecting. It is a private field rather than a bean because the measured risk is
  someone autowiring the application mapper into a `JsonDeserializer`, which does
  inherit the WARN handler. Unmodelled fields stay visible via one INFO line per
  distinct field-name *set*, not per event.
- **Absence of `service` on an event is not `"unknown"`.** Absent means "infer the
  origin"; a value asserts it and overrides the model. Two simulator scenarios
  withhold it on purpose because their logs name the victim. If absence ever maps
  to a string, those two silently stop testing anything — `IncidentEventConsumerTest`
  pins it.
- **`Incident.resolution` and `Incident.resolutionNotes` must never merge.** The notes are
  what a human recorded after actually fixing an incident, and they are the only thing
  retrieval ever shows the agent. `resolution` is a machine proposal that may be wrong.
  Writing one into the other feeds the Resolver its own guesses back as precedent on every
  future call, and the corpus drifts towards whatever the model already believed. Nothing
  writes `resolutionNotes` on the live path.
- **Retrieval limit is 3, at `ResolverService.SIMILAR_INCIDENT_LIMIT`.** Measured, not
  guessed: all three contrasting pool resolutions arrive in the first three slots. Slot 4
  buys INC-2378, the cross-type match — real, but `findSimilar` has no relevance floor, so
  on a novel incident slot 4 is an irrelevant precedent formatted identically to a good one.
  No score threshold anywhere; one query over 21 documents is not a calibration. Raising it
  is one line and a free re-measurement.
- **The Resolver's two failures degrade differently.** Retrieval failure → resolve with no
  precedent (the prompt has that branch). Agent failure → null resolution, incident still
  stored. Neither loses a diagnosis the LLM was already billed for. Storage failure still
  fails the whole call, unchanged.
- **A null `resolution` means different things on the two frame types, and that is the whole
  reason `IncidentFrame` has an envelope.** `type: "incident"` → the Resolver ran and failed.
  `type: "history"` → it was never asked (the seeded twenty). On REST the ambiguity is
  documented and tolerable because the caller named the id; on a push stream nobody asked for
  anything, and one empty box for both says the system gave up on an incident a human fixed in
  February. **There is no "not yet"** — `analyzeAndRecord` is synchronous, so the Resolver has
  finished before the push happens. A `resolutionStatus` field on `IncidentResponse` was
  rejected: it changes the REST contract for a push-only problem and would carry exactly one
  possible value on the live path today. Add a fourth frame type only if the Resolver ever
  becomes asynchronous.
- **`IncidentBroadcaster` is injected with the application's ObjectMapper — deliberately the
  opposite of `IncidentEventParser` and `AnalysisCache`.** Those two need semantics that differ
  from the REST path; this one needs output that is *identical*, because "the frontend learns
  one object" is only true if the frame payload is the same bytes as the REST body. Measured
  the hard way: the test's first stand-in used `Jackson2ObjectMapperBuilder.json().build()` and
  wrote `analyzedAt` as `1.7871921E9`, because `WRITE_DATES_AS_TIMESTAMPS` is disabled by
  Spring **Boot**, not by the builder. The production code was right and the replica was wrong.
- **Every WebSocket session is wrapped in `ConcurrentWebSocketSessionDecorator`, and the
  broadcaster keys its map on session id.** Two reasons, both load-bearing. `sendMessage` is
  not thread-safe and this endpoint has two writers (the connection thread replaying, the Kafka
  thread broadcasting). And the broadcast runs inline inside `analyzeAndRecord`, which
  `POST /api/analyze` shares — so without the decorator's 5s/512KB limits one slow dashboard
  adds its latency to every REST request. The id-keyed map is because Spring hands
  `afterConnectionClosed` the *raw* session, which does not equal the decorator that was
  registered; a `Set` would leak a session per closed tab.
- **WebSocket allowed-origins REPLACES the same-origin default, it does not extend it.** Listing
  the Vite port alone silently locks out `http://localhost:8080` — the app's own origin, and the
  one a browser console is on when someone first tries the endpoint. It is in the list for that
  reason. `*` is not the shortcut it looks like: it opens this system's incident stream to any
  page on the internet.
- **The prompt's worked example uses an invented failure class**, checked against all 20
  seeded incidents. Don't swap it for a realistic one. A scheduled-job duplicate-execution
  example was rejected because INC-2118 already resolves to making a job idempotent against
  double-counting; health-check and DLQ examples were rejected because their disagreement is
  *raise the limit vs don't*, which is structurally the pool trio's own argument. Any
  replacement must clear both tests: no lexical overlap, and no capacity knob.

## Cost note
Groq free tier: 100k tokens/day. Four full 8-sample baseline runs exhausted it.
This is the concrete justification for Week 3's Redis caching layer.

**These figures are for `openai/gpt-oss-120b` and replace the llama-3.3-70b ones.**
Per-minute ceiling **8,000**, down from 12,000. One Analyzer call is **5,121 tokens**
measured (input 4,109, output 1,012); the Resolver is ~4,700.

Kafka ingestion spends the budget per event rather than per request: one event is
two calls, **~9,800 tokens**, so **~10 events a day**. The simulator defaults to 3
events at 90s intervals for that reason — the interval is set by the separate
per-minute ceiling, not the daily one. Record the demo on a *second* run, once
step 3's cache makes a replay free.

Embeddings cost nothing against that budget — they run locally, not on Groq.
They cost **size** instead: ~200MB of jars (83MB model + 93MB onnxruntime +
19MB DJL tokenizer), which lands in the Week 4 Docker image. Budget for it
there rather than being surprised by it; the `-q` quantized model is the lever
if it matters.

## Known decisions to revisit
- ~~Week 3: Kafka consumer needs its own ObjectMapper without the
  unknown-property WARN handler (would flood logs at event rate)~~
  **Settled in step 2, conclusion kept and reasoning replaced.** It does get its
  own mapper — but the "flood" was never measured and is about 4.5 lines/minute
  at the interval the quota forces, so that was not the reason. Two things the
  note had backwards, both measured in docs/ingestion.md: Spring Kafka's default
  deserialiser does not inherit the handler anyway, and a bare `new ObjectMapper()`
  **rejects** our own events outright. Left struck through rather than deleted
  because the correction is the useful part — the action was right for reasons
  that were wrong, which is the failure mode this file keeps catching.
- Week 2: errorType is a closed enum with no free-text companion field.
  keyEvidence carries the specifics and the Resolver produces the
  human-readable root cause, so a separate label field was deliberately
  deferred rather than ride an API change along with the enum. Revisit
  if the Resolver turns out to need one.
- Watch the OTHER rate on the 8-sample baseline. A high rate means the
  vocabulary is too small — grow it from that evidence, not by guessing.
- ~~**A single event's two calls exceed the whole per-minute bucket.**~~ **CLOSED
  2026-08-20.** The burst is real and stays real — Groq's own refusals, three
  samples, deficits of 133/300/308 tokens against an 8,000 ceiling — but it no
  longer costs a resolution. `ResolverService` catches the rate limit, waits 15s
  and retries once; measured live, a cold incident now keeps its recommendation.
  **A fixed delay between the agents was rejected**: both entry points share
  `analyzeAndRecord`, so it would have slowed `POST /api/analyze` too and been paid
  on every event including cache hits that never needed it. See docs/caching.md.
- **The measurement debt: baseline.md and resolver.md both need full re-runs.**
  ~41,000 tokens for one 8-sample Analyzer run (3 runs to settle a judgement field),
  ~38,000 for the 8-observation Resolver set. That is more than a day. Deliberately
  deferred until after the cache lands, and **the cache must be cold or bypassed
  during any re-measurement** or a stale hit corrupts exactly the run-to-run
  comparability those files rest on.
- **The Resolver's `decidingEvidence` argument may not survive the model change.**
  It was added because generation is autoregressive and the discriminator procedure
  "had nowhere to run". gpt-oss reasons *before* emitting content, so the procedure
  may now have somewhere to run regardless. Cheapest check is the binary one:
  `decidingEvidence` stated 8 of 8 was the mechanism-fired measure.
- **Week 3: ingestion is at-least-once and nothing deduplicates.** A crash between
  the LLM call and the offset commit re-runs the event: duplicate Mongo document,
  second bill for the same diagnosis. `eventId` is carried and logged so a duplicate
  is identifiable, but nothing acts on it. A unique index on `eventId` fixes the
  storage half; the Redis cache fixes the expensive half for free, which is why this
  waits for step 3 rather than being built now.
- **Week 3: the incident stream replays from scratch on every connect.** No cursor, no
  "everything since X", so a client that reconnects after an hour gets the most recent ten and
  cannot discover what it missed beyond them. At ~10 events/day ten slots is over a day, so the
  gap is theoretical now; a `since` parameter on the handshake is where it closes.
- **Week 3: WebSocket sessions are in-memory in one process and nothing authenticates.** A
  restart drops every dashboard (they reconnect into a fresh backlog), a second instance would
  need a shared fan-out, and anyone who can reach the port reads every incident this system has
  diagnosed. The last one is true of the REST endpoints too — the socket only makes it
  continuous. Week 4 concerns, recorded now.
- **Week 3: the DLT has no consumer and nothing alerts on it.** Failed events are
  kept rather than lost, which was the point, but noticing them is a manual
  `kafka-console-consumer` on `incident-events.DLT`.
- **Week 3: a persistent Redis cache can serve a stale response after a prompt
  edit.** AOF is on and the volume survives `docker compose down`, because the
  100k/day Groq budget is the scarcer resource — see the cost note. The risk it
  buys is real though: a stale hit during a baseline run corrupts exactly the
  run-to-run comparability docs/baseline.md rests on. The guard belongs in the
  cache key, which must include something that changes when the prompt or the
  model changes. That is step 2's job, not compose's. Until then
  `docker compose down -v` is the clean slate.
- **Week 2: no embedding-model marker on Incident**, by the same reasoning that
  kept a `source` field off it. The trap this leaves, on record before it bites:
  a *dimension* change (384 → 1536) fails loudly, because the Atlas index has
  `numDimensions` baked in and rejects the query. A *same-dimension* swap does
  not — all-MiniLM-L6-v2 and bge-small-en-v1.5 are both 384, so the old vectors
  stay queryable and simply rank as noise. Nothing detects it. If the model is
  ever swapped, drop the index and re-backfill all 20 documents **in the same
  change**, or add the marker at that point.
- **Week 2: `affectedService` — measured, kept.** Ran the pool trio with and
  without it: top-3 membership and order identical in all four queries, the one
  difference being an exact tie breaking. It pulls same-service pairs together
  (+0.035…+0.091) and pushes cross-service apart (−0.018…−0.031), but never
  enough to change a ranking. Still open underneath: every same-service pair in
  the corpus is *unrelated failures sharing a service*, so the case the field
  exists for has no sample yet. Numbers in docs/retrieval.md.

## Prompt engineering learnings
- Examples override rules. One worked example without timezones taught the
    model to skip UTC conversion. Two examples forced it to infer the rule.
- Prompts are probabilistic, types are guarantees. severity (enum) never
  drifted in 6 runs; errorType (String) drifted twice with identical input.
  If a value must be stable, encode it in the type, not the prompt.
- An enum pins the vocabulary, not the choice. The type stops "which spelling";
  only a run answers "which constant". Where two constants are both defensible
  the choice is a judgement and moves on its own — downstream-timeout moved and
  then moved back with the prompt untouched. Where only one is defensible it
  holds (stability 3/3). Stability is a property of the sample too, not just
  the type.
- **A judgement field needs three runs before a change in it is a regression.**
  out-of-memory read 03:40:55, 03:40:55, 03:41:12 with nothing changed between
  them. Two agreeing runs got it written up as a stable regression with a
  candidate commit named; the third killed it. AnalyzerStabilityTest already
  required 3/3 and explicitly refused to count 2/3 as a pass — the baseline
  simply never held itself to the same standard. Three runs is the whole daily
  quota, so until the Week 3 cache lands, state the run count next to any
  finding and treat one or two observations as a hypothesis.
- Do not act on a single observation. Both queued Step 4 fixes were built on
  one-or-two-run findings and both evaporated. Trimming the availability
  vocabulary would have been credited with a revert that was going to happen
  anyway — a fix that "works" for the wrong reason is worse than no fix,
  because it gets believed.
- Fencing ("this rule governs X and NOTHING ELSE") has never actually been
  shown to fail. The one bleed it supposedly failed to stop turned out to be
  variance. It also has not been shown to work. Untested either way.