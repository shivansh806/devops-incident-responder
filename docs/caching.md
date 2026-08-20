# Analyzer response caching

A Redis cache in front of the Analyzer call. It exists to answer one question: **can the same
diagnosis be served twice without paying for it twice?**

The motivation is quota, not latency. One Analyzer call is **5,121 measured tokens** against a
free tier of 100,000 a day, four full baseline runs have exhausted that budget before, and a
single incident event's two calls (~9,800) exceed the 8,000 tokens-per-minute ceiling on their
own.

- Key: `src/main/java/com/shivansh/incidentresponder/cache/AnalysisCacheKey.java`
- Cache: `src/main/java/com/shivansh/incidentresponder/cache/AnalysisCache.java`
- Call site: `AnalyzerService.analyze`

## What is cached, and what is not

**The Analyzer is cached. The Resolver is not.**

The Analyzer is a pure function of the log text and the prompt, so the same input has the same
right answer. The Resolver's input includes retrieved precedent, which changes as the corpus
grows — a hit there would be answering a question that is no longer the one being asked, and
the corpus grows on every single call through `analyzeAndRecord`.

Only a **normalised** `LogAnalysis` is stored, never raw model output. Whatever comes out of the
cache is what a later caller receives, so it has to have been through the same defences —
enum coercion, timestamp parsing, confidence clamping — as an uncached answer.

## The key is the whole design

Get the key wrong and the failure is the worst kind available: a stale answer served
confidently, indistinguishable from a fresh one. So it is built in one place and tested.

```
analysis:v1:<fingerprint>:<input>
```

**fingerprint** covers *how* the answer is produced, **input** covers *what* was asked.

| In the fingerprint | Why |
|---|---|
| Model id | **Groq retired `llama-3.3-70b-versatile` mid-project.** Without this, every entry cached before the swap would have been served afterwards as if it described the current model. This is not hypothetical — it happened, which is why it is first. |
| Reasoning effort | A hidden input that changes the output. If it does not change the key, pinning it in configuration is theatre. |
| Both prompts | Read off `@SystemMessage` and `@UserMessage` reflectively, not off the `SYSTEM_PROMPT` constant — the constant is only half of it. |
| Output schema | LangChain4j derives the JSON format instructions from `AnalyzerOutput`, so adding a component changes the prompt without anyone editing prompt text. |
| Enum vocabularies | `ErrorType` and `Severity` constants are sent to the model as the permitted set. This project expects to grow `ErrorType` from evidence; that has to invalidate the cache. |

| In the input | Why |
|---|---|
| The log text | Obviously. |
| The service name, normalised | Absent and present are **different questions** — absent asks the model to infer the origin, present asserts it and overrides the model. They must not share an entry. Normalised first, so `" payment "` and `"payment"` do share one. |

**Everything is derived, nothing is remembered.** A constant that must be bumped by hand is a
constant that will not be bumped by hand. Deriving means a prompt edit, a new `ErrorType`, or a
new field on `AnalyzerOutput` all invalidate the cache without anyone deciding to.

The one hand-maintained part is `KEY_FORMAT_VERSION`, and its job is narrow: stop an old-format
key being read as a new-format one. It is not for prompt or model changes.

### Why the fingerprint has to be sorted

`getDeclaredMethods()` has no defined order. An unsorted derivation would produce a different
fingerprint on different JVM runs — missing the cache every single time while looking like it
worked, with no error anywhere. `AnalysisCacheKeyTest` pins stability across repeated
derivation for exactly this reason.

## Why there is a TTL

The key covers everything observable. What it cannot cover is **Groq changing the weights
behind `gpt-oss-120b` without renaming it** — nothing here would move, and the cache would keep
serving answers from a model that no longer exists in that form.

The TTL bounds how long that can go unnoticed. It is the only reason for one; correctness
otherwise comes from the key. Default 7 days.

## Failing open

Redis being unreachable must never fail a diagnosis. A read fault is a miss, a write fault is a
shrug, an unparseable stored value is a miss. The same trade `ResolverService` makes for
retrieval: degraded beats broken.

This is also why the cache is not `@Cacheable`. The key construction is the load-bearing part
and belongs in readable Java rather than SpEL, hits and misses need to be logged to be
measurable, and the self-invocation trap — `analyze(String)` delegating to
`analyze(String, String)` inside the same bean — would silently not be intercepted.

## The switch is a measurement guard, not a feature flag

`incident.cache.enabled=false`, and `src/test/resources/application.properties` sets it for
every test.

> **`AnalyzerStabilityTest` asks whether the model returns the same answer three times. With
> the cache on, runs two and three are cache hits and the test passes without measuring
> anything at all.**

That is the sharpest case, but a baseline run has the same problem more quietly: a stale hit
corrupts exactly the run-to-run comparability `docs/baseline.md` rests on. `CLAUDE.md` recorded
this risk before the cache existed; the guard is the answer to it.

When disabled, nothing reaches Redis — not even a lookup that misses. `AnalysisCacheTest` pins
that, because "disabled" quietly meaning "still reads" would leave the measurement hazard in
place.

## Measured live, 2026-08-19

`LivePipelineProbe` ran one real event twice through `IncidentService.analyzeAndRecord`, cold
cache then warm, on `downstream-timeout.log`. It exists to settle the prediction
`docs/ingestion.md` had been carrying as arithmetic.

| | call 1 — cold cache, full bucket | call 2 — same input, after a 90s refill |
|---|---|---|
| Analyzer | called the model | **CACHE HIT (free)** |
| Diagnosis | `UPSTREAM_TIMEOUT` on `inventory-service` (HIGH) | identical |
| Resolver | **429, failed** | succeeded |
| Stored resolution | **NULL — no recommendation** | present, with `decidingEvidence` |
| Wall clock | 6,239ms | 3,656ms |

**The prediction was right, and Groq stated the arithmetic itself:**

```
Rate limit reached for model `openai/gpt-oss-120b` ... on tokens per minute (TPM):
Limit 8000, Used 4715, Requested 3585. Please try again in 2.25s.
```

4,715 + 3,585 = 8,300 against a ceiling of 8,000. The Analyzer's own call is what pushes the
Resolver over, inside a single event, from a bucket that had been idle for an hour.

**Call 2 is the cache paying for itself.** Identical input, Analyzer served from Redis at zero
tokens, and the Resolver — now facing the whole 8,000 bucket alone — completed and produced a
recommendation. Nothing else differed.

### Retrying is not the fix, which is worth knowing

LangChain4j retries rate limits internally: the log shows *"A retriable exception occurred.
Remaining retries: 2 of 2"*. **It retried twice and still failed.** A token bucket needs
wall-clock seconds to refill — Groq asked for 2.25s — and the built-in policy comes back faster
than that.

So the obvious reflex, "raise the retry count", would not have worked, and the less obvious one,
"add a delay between the two agents", would cost every event several seconds forever. The cache
removes the second call's competitor entirely, which is a different and better shape of fix.

**What the cache alone does not fix — since closed separately.** A *first* diagnosis of
genuinely new logs still makes both calls back to back. That is now handled by a rate-limit
retry in `ResolverService`; see *The cold-incident case* below. The cache helps a replay, which is exactly the
demo case and exactly the re-measurement case, and does nothing for a cold incident. That is the
honest limit of it, and the reason `docs/ingestion.md` keeps the burst listed as open.

### Side observation, not a finding

Call 2's `decidingEvidence` came back stated and specific — it named a p99 figure and the
precedent it matched. `docs/resolver.md` flags as an open question whether that mechanism
survives on a reasoning model. This is **one observation on a different case** from the one that
measurement used, so it settles nothing; it is recorded only so the next person knows the field
is at least firing.

### What it left behind

Two real incidents in Atlas: `6a8584b677cbaf249348bb8f` (no resolution) and
`6a85851477cbaf249348bb90` (resolved). Both carry no embedding, so neither is indexed and
neither can turn up as retrieval precedent. Cost was roughly 14,500 tokens.

## The cold-incident case, closed 2026-08-20

The cache rescues a replay. It cannot help a **cold** incident, where both calls are paid back
to back — and a cold incident is what a real one is. That is now handled in `ResolverService`:
catch the rate limit, wait once, retry once, and otherwise degrade to null exactly as before.

**Why a wait there rather than a fixed delay between the agents.** Both entry points share
`IncidentService.analyzeAndRecord`, so a fixed delay would also slow the synchronous
`POST /api/analyze`, and it would be paid on every event — including every cache hit, where the
Analyzer costs nothing and the Resolver has the whole bucket anyway. A reactive wait costs
nothing unless the call is actually refused.

### The number

The bucket is 8,000 tokens refilling at **8,000 ÷ 60 = 133 tokens/second**. The deficit inside
one event is `analyzer + resolver − 8000`, and the wait is `deficit ÷ 133`.

| Observed | Analyzer used | Resolver requested | Deficit | Groq asked for |
|---|---|---|---|---|
| `downstream-timeout.log` | 4,715 | 3,585 | 300 | 2.25s |
| `thread-deadlock.log` | 4,542 | 3,591 | 133 | 0.9975s |
| `connection-pool-exhaustion.log` | 4,673 | 3,635 | 308 | 2.31s |

Every row satisfies `deficit ÷ 133 = the wait Groq asked for`, which is what makes the model of
the limiter trustworthy rather than fitted. **15 seconds** covers a deficit of ~2,000 tokens —
an Analyzer plus Resolver totalling 10,000 against a measured 8,133–8,308.

### Why LangChain4j's own retry is not enough — and when it is

Its default policy is `maxRetries=2, delayMillis=500, backoffExp=1.5`, so it waits ~500ms then
~750ms: about **1.25 seconds**, which covers a deficit of roughly **166 tokens**. And the policy
is not configurable — `OpenAiChatModel`'s builder exposes no retry knob in 1.18.1, nor does the
Spring starter.

The two runs land either side of that line, which is the cleanest evidence available that the
threshold is real:

- **`thread-deadlock`, deficit 133** → Groq asked 0.9975s → LangChain4j's 1.25s covered it, and
  our retry never fired. The resolution survived without us.
- **`connection-pool-exhaustion`, deficit 308** → Groq asked 2.31s → LangChain4j exhausted its
  two retries and failed. Ours fired, waited 15s, and the resolution was produced.

### The measured result

```
sample           : connection-pool-exhaustion.log (5,470 chars), cache DISABLED
rate limited     : YES - a 429 was returned
lc4j retried     : YES (its own 1.25s policy)  -> failed
OUR retry fired  : YES - ResolverService waited
resolution       : PRESENT - rescued
took             : 23,711ms
decidingEvidence : "processRefund acquired a connection in 4871ms (slow-acquire threshold
                    1000ms), which matches INC-2103's case and not INC-2331's."
```

23.7s is the predicted shape: ~3.5s Analyzer, ~2s of refused attempts, the 15s wait, ~3s for the
Resolver that succeeds.

### A measurement bug worth recording

The first run of this probe reported **"rate limited: no"** on a run whose log plainly contained
a 429. The probe watched only this application's logger, and LangChain4j reports its own retries
under `dev.langchain4j.internal.RetryUtils`. It also happened to pick a small sample, so the
deficit was inside LangChain4j's own 1.25s and the resolution survived without our code running
at all.

Both together would have marked the fix verified on a run where it never executed. The probe now
attaches to the root logger and distinguishes *a 429 happened* from *our retry ran*, and it
defaults to the largest sample so the deficit is big enough to need us.

### What is still not covered

The arithmetic assumes calls are **serialised**. Kafka guarantees that — `max-poll-records: 1`,
one consumer — but two concurrent `POST /api/analyze` requests would interleave and nothing
serialises them. The retry degrades gracefully there rather than correctly: it will wait and
retry, which usually works, but the deficit could exceed what 15 seconds buys back.

## Status

Offline coverage in `AnalysisCacheKeyTest` (11 cases) and `AnalysisCacheTest` (7 cases): key
stability, every input that must change it, field-boundary collisions, round-tripping an
`Instant` without degrading it to epoch seconds, failing open on a dead Redis, and the disabled
path touching nothing.

Live behaviour measured once, above. The cache hit, the 429 and its absence on the cached call
are all observed rather than argued.
