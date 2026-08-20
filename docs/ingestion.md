# Kafka ingestion

The second entry point into the pipeline. It exists to answer one question: **what happens to
an incident event when the model call fails?**

Everything else here is arrangement. The pipeline itself is unchanged — `IncidentService.analyzeAndRecord`
is what the REST controller has called since week 2, and the consumer calls exactly that.

- Event contract: `src/main/java/com/shivansh/incidentresponder/kafka/IncidentEvent.java`
- Parsing: `src/main/java/com/shivansh/incidentresponder/kafka/IncidentEventParser.java`
- Failure policy: `src/main/java/com/shivansh/incidentresponder/kafka/KafkaConsumerConfig.java`
- Listener: `src/main/java/com/shivansh/incidentresponder/kafka/IncidentEventConsumer.java`
- Simulator: `src/main/java/com/shivansh/incidentresponder/simulator/`

## Running it

The broker comes from `docker-compose.yml`; see `docs/infra.md`.

```
docker compose up -d --wait
```

Then in IntelliJ, with `simulator` in **Active profiles**, start the app. The simulator emits
onto `incident-events` and the consumer picks the events up.

To fill the topic without paying for it, add `incident.kafka.consumer-enabled=false`. Producing
is free; the consumer is what costs money.

## What it costs

**Every event is two Groq calls — about 9,800 tokens** on `openai/gpt-oss-120b`. An Analyzer
call measured at **5,121** (input 4,109, output 1,012) plus a Resolver call of roughly 4,700.

| | on `gpt-oss-120b` (current) | on `llama-3.3-70b` (retired) |
|---|---|---|
| Tokens per event | **~9,800** | ~6,000 |
| Groq free tier, per day | 100,000 (unconfirmed for this model) | 100,000 |
| **Events available per day** | **about 10** | about 16 |
| Groq free tier, per minute | **8,000** | 12,000 |
| **Minimum sustainable interval** | **~75s** | ~35s |

So the defaults are `count: 3` and `interval: 90s`. A full eight-scenario run is ~78,000 —
most of the daily budget, not half of it.

Two limits, and they fail differently. The per-minute one is what the interval is for; going
faster earns a 429 with plenty of daily budget left. The per-day one no interval can fix.

### The interval cannot fix the within-event burst

**A single event's two calls together exceed the whole per-minute bucket.** 5,121 + 4,700 is
~9,800 against a ceiling of 8,000, and the two calls run back to back inside
`IncidentService.analyzeAndRecord` with nothing between them. The bucket refills at ~133
tokens a second, so however long the pipeline has been idle, the Analyzer call leaves roughly
2,900 and the Resolver needs ~4,700 — about 14 seconds short.

The **Resolver** is the call that 429s, and `ResolverService` absorbs its own failures by
design. So the symptom is not an error: it is **incidents stored with a diagnosis and no
recommendation**, on every event, with nothing in the pipeline reporting a problem. On the old
model 6,000 fitted inside 12,000 and this could not happen.

**Confirmed live on 2026-08-19**, and it is no longer a prediction. `LivePipelineProbe` ran one
event from a bucket idle for an hour; Groq stated the arithmetic in its own refusal:

```
Rate limit reached ... on tokens per minute (TPM): Limit 8000, Used 4715, Requested 3585.
Please try again in 2.25s.
```

The incident stored with `resolution: NULL` — the silent symptom described above, exactly as
written. **LangChain4j retried twice on its own and still failed**, so "raise the retry count"
is not the fix: a token bucket needs wall-clock seconds to refill and the built-in policy
returns faster than that.

The same probe's second call, on identical input, hit the cache: the Analyzer cost nothing, the
Resolver had the whole bucket, and a recommendation was produced. Numbers in `docs/caching.md`.

**Closed on 2026-08-20 by a rate-limit-aware retry in `ResolverService`**, not by a delay. A
cold incident now keeps its resolution — measured, see `docs/caching.md`. The retry waits only
when the call is actually refused, so it costs nothing on a call that fits and nothing at all
after a cache hit.

A fixed delay between the two agents was the obvious alternative and was rejected: both entry
points share `IncidentService.analyzeAndRecord`, so it would have slowed `POST /api/analyze`
too, and it would have been paid on every event including the ones that never needed it.

This is the same arithmetic that paces `AnalyzerBaselineTest` at 65 seconds, and the same
argument for the Redis cache in the next step - now a much sharper one, given the burst
problem above. **The demo is worth recording on the second
run, once responses are cached** — a replay then costs nothing and runs at whatever speed
reads well on video.

## The offset contract

Offsets are committed by the container. Spring Boot disables Kafka's auto-commit, and
`ack-mode: RECORD` commits after each record rather than after each poll batch.

- **Listener returns** → offset committed → never redelivered.
- **Listener throws** → `DefaultErrorHandler` seeks back to that offset and redelivers **on the
  same consumer thread**. Retry time is blocking time.
- **Retries exhausted** → the recoverer runs, and *then* the offset is committed.

That last step is why there is a dead-letter topic. Giving up means committing past the event,
so without `DeadLetterPublishingRecoverer` giving up is silent data loss. Failed events land on
`incident-events.DLT` with the exception in headers.

### The defaults are dangerous here, with numbers

Two of Spring Kafka's defaults are actively wrong for a pipeline whose unit of work is two LLM
calls. Both settings below are correctness requirements, not tuning.

**`max.poll.records` defaults to 500.** One poll would hand the listener 500 events. At ~125s
worst case per event — two calls against a 60s `langchain4j.timeout` — that is roughly 17 hours
of work inside a poll interval of minutes. The broker evicts the consumer mid-batch, rebalances,
and redelivers the whole batch **with nothing committed**. Every LLM call already made was
billed and its result is thrown away. Set to **1**.

**`DefaultErrorHandler` defaults to 10 attempts.** One event hitting both timeouts ten times is
about 21 minutes, against a 5-minute default poll interval. A single bad event is enough to
trigger the same eviction. Set to **1 retry**, with `max.poll.interval.ms` raised to **600000**.

Worst case with those three settings: 125s + 60s backoff + 125s = **310s**, inside the 600s
interval with margin.

### One retry, not ten

Retrying an LLM call spends quota, which makes retry count a cost decision rather than a
robustness one.

`AnalyzerBaselineTest` already settled the policy empirically: retry once after ~65s, then
report the failure and move on rather than aborting. The reasoning transfers exactly. A
**per-minute** 429 clears in about a minute, so one delayed retry recovers it. A **per-day**
429 will refuse every attempt no matter how many are allowed, so further attempts buy nothing
and cost minutes of blocked partition.

### What is retried and what is not

| Failure | Surfaces as | Behaviour |
|---|---|---|
| Not JSON, not an object, no `logs` | `MalformedEventException` | **Never retried** → DLT on attempt 1 |
| Empty or oversized log dump | `IllegalArgumentException` | **Never retried** → DLT on attempt 1 |
| Analyzer failed — 429, timeout, bad parse | `AnalysisFailedException` | One retry after 60s, then DLT |
| Mongo save failed | propagates | One retry after 60s, then DLT |
| Resolver rate limited | — | **Retried once after 15s inside `ResolverService`** |
| **Resolver failed otherwise** | — | **Never reaches the error handler** |

The last two rows are the useful ones. `ResolverService` handles its own rate limits — one wait,
one retry — and absorbs every other failure by returning null, so the incident is stored with a
diagnosis and no recommendation, the week 1 shape. **The only LLM failure that can reach Kafka's
retry machinery is the Analyzer's.** That is existing behaviour the consumer inherits, and it
halves the retry surface.

A message that cannot be processed is classified as terminal on purpose. Bad JSON parses
identically on every attempt; retrying it twice costs a minute of blocked partition and changes
nothing.

### `auto-offset-reset` stays at `latest`

Against the advice of every tutorial. It only applies when a group has no committed offset, and
`earliest` there means replaying the whole topic the first time the app starts — at ~9,800
tokens an event, potentially the entire daily budget before the first log line is read.

An existing group resumes from its committed offsets under either setting, which is the case
that actually matters. `latest` gives: new group skips the backlog, existing group resumes.

## The ObjectMapper decision, re-measured

CLAUDE.md carried a note that the Kafka consumer would need its own ObjectMapper without
`JacksonConfig`'s unknown-property WARN handler, because it "would flood logs at event rate".
The conclusion survives; the reasoning did not.

Measured with `KafkaObjectMapperProbe` (offline, reports rather than asserts) on a payload with
three unknown fields:

| Mapper | Outcome | WARN lines |
|---|---|---|
| Spring Boot's auto-configured one (REST path) | parsed | 3 |
| Spring Kafka `JsonDeserializer`, default construction | parsed | **0** |
| `JsonDeserializer` handed the Spring ObjectMapper | parsed | 3 |
| **A plain `new ObjectMapper()`** | **FAILED — `UnrecognizedPropertyException`** | 0 |

**Row 4 is the finding.** `FAIL_ON_UNKNOWN_PROPERTIES` defaults to *on* in Jackson, and is off
in this application only because Spring Boot turns it off. So "give the consumer its own
ObjectMapper", implemented literally, produces a **strict** mapper that rejects every
forward-compatible event — the exact opposite of the intent. Leniency is configured, not
inherited. `IncidentEventParser.eventObjectMapper` disables it explicitly.

**Row 2 says the premise was also off.** Spring Kafka's `JsonDeserializer` builds its own mapper
via `JacksonUtils.enhancedObjectMapper()` and never sees the `Jackson2ObjectMapperBuilderCustomizer`,
so the handler is not inherited by default anyway. Row 3 shows the risk is real but conditional:
it appears only if someone wires the application mapper in, which is a common copy-paste. A
privately constructed mapper cannot be wired wrongly, which is why it is a field and not a bean.

**On "would flood".** Three unknown fields is three lines per event. At the interval the Groq
budget forces, that is about 4.5 lines per minute — not a flood, and the claim was never
measured. The argument that survives is semantic: the same WARN means opposite things on the
two paths. On REST an unknown property is probably a typo, which is what the handler is for. On
Kafka it is the designed behaviour, and logging it at WARN devalues the signal the REST path
needs.

### Keeping the drops visible anyway

Silently discarding fields is how a producer's rename goes unnoticed for a week. So each
distinct **set** of unmodelled field names is reported once, at INFO:

```
Incident events carry 6 field(s) this application does not model, ignoring:
alertRuleId, cluster, environment, podName, region, schemaVersion.
This is expected - reported once per distinct shape, not once per event
```

Cost is O(distinct event shapes) rather than O(events), and it says the more useful thing. Names
are sorted, so field order in the payload is not a new shape. Capped at 50 shapes — reaching the
cap is itself warned about once, because an unstable payload shape is a different problem from
an added field.

## The simulator

Eight scenarios, one template file each under `src/main/resources/simulator/`.

**Variety is guaranteed, not likely.** Scenarios are shuffled once and then taken in order, so N
events are N different failure classes up to the scenario count. Drawing at random would repeat
— with eight scenarios and eight draws a repeat is near-certain — and a demo that shows the same
failure twice while claiming variety is worse than one that shows three.

**No expected answer appears anywhere in a template.** Same rule as the baseline samples: what
is in the log goes into the prompt, so a hint would make the demo a performance. The enum
constant names describe what was simulated, not what the Analyzer should answer.

### Why templates are rendered rather than fixed

Two events of one failure class must not be the same text. Identical logs embed to an identical
vector, and retrieval would start handing the Resolver the simulator's own previous output as
precedent — a corpus quietly collapsing onto itself. So services, hosts, request ids, timestamps
and every metric number vary per event.

The token set is in `IncidentLogRenderer`. Two distinctions in it are load-bearing:

- `{#40-120}` is fresh at every occurrence — a GC pause differs line to line.
- `{=export:1000-9999}` is **stable within one event** — the job id in "started", "loaded" and
  "failed" is one job, and three different numbers would read as three unrelated jobs.
- `{t+7|+05:30}` renders at that offset instead of UTC. This exists for one template:
  `disk-space-exhausted.log`, whose trap is that `firstOccurrence` needs converting. That is
  baseline sample 4, deliberately carried over.

The pattern matches only those shapes, which is what lets `expired-certificate.log` be
JSON-structured without its own braces being eaten.

### Two scenarios withhold `service`

`UPSTREAM_TIMEOUT` and `EXPIRED_CERTIFICATE` emit no `service` field, because their logs are
tagged with the **victim** rather than the origin. Setting it would assert the wrong answer and
override the model — and origin-versus-reporter is exactly what those templates exercise. A
producer that is not sure should say nothing.

Note the asymmetry this rests on: absence means "infer it", and `"unknown"` would mean the
producer is claiming ignorance as a fact. `IncidentEventConsumerTest` pins absence to a null
reaching the Analyzer, because if that ever became `"unknown"` those two scenarios would
silently stop testing anything.

## Known limitations

Deliberately left, not overlooked.

**Delivery is at-least-once, and nothing deduplicates.** A crash between the LLM call and the
offset commit re-runs the event: a duplicate incident in Mongo and a second bill for the same
diagnosis. `eventId` is carried and logged, so a duplicate is identifiable after the fact, but
nothing acts on it. A unique index on `eventId` would fix the storage half; the next step's
Redis cache addresses the expensive half for free, which is why this is recorded rather than
built now.

**The DLT has no consumer and nothing alerts on it.** Failed events are kept rather than lost,
which was the point, but noticing them is currently a manual `kafka-console-consumer` on
`incident-events.DLT`.

**Retry timing is arithmetic, not measurement.** The 310s worst case is computed from the
configured timeouts; no run has actually taken a listener through both retries with a real 429.
The numbers are sound but they are a budget, not an observation.

**The simulator's realism is unmeasured.** The templates read like production logs to the person
who wrote them, which is the same limitation `docs/retrieval.md` records about its corpus. What
the Analyzer makes of them has not been scored against expectations the way the eight baseline
samples were — and deliberately so, because that would cost a full day's quota and duplicate
what the baseline already measures.

## Status: built and verified offline 2026-08-16

147 tests pass with `mvn test`, all offline — no broker, no Groq call, no quota.

| Check | How |
|---|---|
| Unknown fields ignored, reported once per shape | `IncidentEventParserTest` |
| Malformed messages classified as terminal | `IncidentEventParserTest`, `IncidentEventConsumerTest` |
| Declared service authoritative, absence → null | `IncidentEventConsumerTest` |
| Every template renders with no token left behind | `IncidentSimulatorTemplateTest` |
| Two events of one scenario differ | `IncidentSimulatorTemplateTest` |
| **Producer and consumer agree on the wire format** | `SimulatedEventWireFormatTest` |

The last one is the only check neither side could make alone. Both are individually correct and
could still disagree about whether `detectedAt` is an ISO string or epoch seconds — a
disagreement that would surface as a dead-lettered event during a demo rather than in a build.

Against the live broker, one simulated event was produced with the console producer and read
back byte-identical. **The full path — app consuming from Kafka and calling Groq — has not been
run**, because that costs quota and the app is started by hand. Nothing above claims otherwise.
