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

**Next:** Week 3, step 2 — Kafka ingestion, then Redis caching, then WebSocket.

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
- **The prompt's worked example uses an invented failure class**, checked against all 20
  seeded incidents. Don't swap it for a realistic one. A scheduled-job duplicate-execution
  example was rejected because INC-2118 already resolves to making a job idempotent against
  double-counting; health-check and DLQ examples were rejected because their disagreement is
  *raise the limit vs don't*, which is structurally the pool trio's own argument. Any
  replacement must clear both tests: no lexical overlap, and no capacity knob.

## Cost note
Groq free tier: 100k tokens/day. Four full 8-sample baseline runs exhausted it.
This is the concrete justification for Week 3's Redis caching layer.

Embeddings cost nothing against that budget — they run locally, not on Groq.
They cost **size** instead: ~200MB of jars (83MB model + 93MB onnxruntime +
19MB DJL tokenizer), which lands in the Week 4 Docker image. Budget for it
there rather than being surprised by it; the `-q` quantized model is the lever
if it matters.

## Known decisions to revisit
- Week 3: Kafka consumer needs its own ObjectMapper without the
  unknown-property WARN handler (would flood logs at event rate)
- Week 2: errorType is a closed enum with no free-text companion field.
  keyEvidence carries the specifics and the Resolver produces the
  human-readable root cause, so a separate label field was deliberately
  deferred rather than ride an API change along with the enum. Revisit
  if the Resolver turns out to need one.
- Watch the OTHER rate on the 8-sample baseline. A high rate means the
  vocabulary is too small — grow it from that evidence, not by guessing.
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