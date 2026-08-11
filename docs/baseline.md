# Analyzer baseline harness

Eight sample log files and a runner that sends all of them through the Analyzer Agent and
prints the results side by side. It exists to answer one question: **where does the Analyzer
break?**

Before this, the agent had only ever been tested against one failure type (connection pool
exhaustion), which it handled well. That told us nothing about the other failure types it
would meet in production.

- Samples: `src/test/resources/logs/`
- Runner: `src/test/java/com/shivansh/incidentresponder/agent/AnalyzerBaselineTest.java`

## How to run it

**From IntelliJ** — right-click `AnalyzerBaselineTest` and Run. This is the intended path.

**From the command line:**

```bash
mvn test -Dsurefire.excludedGroups= -Dtest=AnalyzerBaselineTest
```

The test is tagged `llm` and surefire excludes that tag by default, so `mvn test` never runs
it — the normal build stays fast, free and offline. The exclusion is a property
(`surefire.excludedGroups`, defaulting to `llm` in `pom.xml`) so it can be cleared explicitly.
Running a single test from the IDE bypasses surefire's filter entirely.

The API key is read from `.env`, the same as when the app runs. `@SpringBootTest` does not
apply `DotenvApplicationInitializer` on its own, so the test declares it via
`@ContextConfiguration`; without that line `GROQ_API_KEY` is empty and every call 401s. If no
key resolves, the test skips rather than failing.

## What it costs

This makes **eight real, billable Groq calls**. Roughly:

| | |
|---|---|
| Tokens per call | ~2,500–3,400 (the log dump dominates) |
| Tokens per full run | ~27,000 |
| Groq free tier, per minute | 12,000 TPM |
| Groq free tier, per day | 100,000 TPD |
| **Full runs available per day** | **about three** |

Two different limits, and they need different handling. The runner paces **25 seconds between
samples** so no 60-second window ever holds more than three calls (~10,200 tokens, inside the
TPM ceiling). It also retries once after a 65-second backoff if a call is rate limited.

Neither helps against the **daily** cap. When TPD is exhausted the remaining samples fail with
a 429 and are reported as `CALL FAILED` — the run continues rather than aborting, so you still
get a partial matrix. Budget your runs: three prompt changes measured separately is already
the daily allowance.

This cost is the concrete justification for the Redis caching layer in week 3.

## The eight samples

Each is 25–35 lines of realistic service output — mixed INFO/WARN/ERROR, metrics noise, health
checks, real stack traces — built around one specific thing that should be hard.

| # | Sample | Expected | Trap it carries |
|---|---|---|---|
| 1 | `connection-pool-exhaustion.log` | `payment-service`, CRITICAL/HIGH | Baseline. The case already known to work; it should pass cleanly, and a failure here means something broke elsewhere. |
| 2 | `out-of-memory.log` | `order-service`, CRITICAL | The failure starts with GC thrashing at `03:41:12`, five minutes before the `OutOfMemoryError` at `03:46:30`. Tests earliest-symptom versus loudest-line. |
| 3 | `downstream-timeout.log` | `inventory-service`, CRITICAL/HIGH | Every line is tagged `[checkout-service]`, but checkout is the victim — inventory returns the 504s. Tests the prompt's own "name the dependency that failed first" rule. |
| 4 | `disk-full.log` | `media-service`, CRITICAL | Timestamps are `+05:30`, not `Z`, so `firstOccurrence` needs converting to UTC. Severity is the second trap: payloads are discarded unrecoverably, so this is data loss — CRITICAL, not HIGH. |
| 5 | `auth-failure-spike.log` | `auth-service`, CRITICAL | Three at once. JSON-structured logs, all tagged `api-gateway` — but the gateway is the victim. The cause (an expired signing certificate) appears in 2 of 28 lines, buried in a 401 flood. And `errorType` must name the cert expiry: `AuthenticationFailure` is the symptom and scores as a miss. |
| 6 | `thread-deadlock.log` | `pricing-service`, CRITICAL/HIGH | The JVM deadlock dump is a multi-line block with no timestamp of its own. Multi-line evidence is where models start paraphrasing. |
| 7 | `slow-query-degradation.log` | `catalog-service`, **MEDIUM** | Severity floor. p99 is up 10x and 2.1% of calls hit the caller's timeout, but every retry succeeds and the error rate is 0.00%. Anything above MEDIUM means severity is tracking how alarming the logs read, not blast radius. |
| 8 | `cache-miss-spike.log` | `profile-service`, **LOW** | The other severity floor, plus a trap the prompt already names: there is an ERROR line (Redis connection lost) at the top. Everything keeps serving, p99 stays under SLO, zero requests fail, hit rate recovers to 93.8%. |

Samples 7 and 8 were added after the first six all came back CRITICAL. With six genuinely
severe incidents, "the model always says CRITICAL" and "the model correctly said CRITICAL six
times" are indistinguishable — there was no way to tell whether severity carried any signal.

**There are no expected answers inside the `.log` files.** Anything in the file goes into the
prompt, so an `# expected: OutOfMemoryError` comment would hand the model the answer and make
the baseline a lie. Expectations live in the runner.

## How scoring works

Four fields are checked per sample, plus a verbatim check on every evidence line.

- **errorType** — exact match against a single `ErrorType` constant. This used to be a set of
  accepted spellings compared after lowercasing and stripping punctuation, because free text
  drifted between runs; the closed enum retired that whole problem, along with the recurring
  argument about whether a given spelling was near enough. See *errorType is a closed enum*
  below.
- **affectedService** — a set too, because in a sample built to test something else the origin
  can be genuinely arguable and shouldn't manufacture a failure.
- **severity** — a set where more than one call is defensible; a single value where the sample
  exists to pin severity down (7 and 8).
- **firstOccurrence** — compared to **second** precision. The log lines carry milliseconds and
  the model copies them through, so `02:12:15.400Z` is the right answer to an expectation
  written as `02:12:15Z`. Matching on the exact `Instant` scored four correct answers as
  failures in the first run.
- **keyEvidence** — each line is looked up in the source log, with whitespace runs collapsed
  first (indentation in a stack trace is formatting, not paraphrasing). Lines that aren't
  found are flagged; the matrix shows `verbatim/total`.

All eight run in **inference mode** (`serviceName = null`). Pass-through mode is deterministic
by construction — `AnalyzerService` copies the caller's name in regardless of what the model
says — and is already covered by `AnalyzerServiceTest`. Inference is where the agent can be
wrong.

## errorType is a closed enum

`errorType` was a free-text `String` until the baseline caught it drifting: the same sample
returned `RedisConnectionLoss` on one run and `RedisConnectionFailure` on the next. Same
model, same temperature, same prompt, same input. Both answers are reasonable, and that is
precisely the problem — week 2 matches past incidents on this field and week 3 hashes it into
a Redis cache key, so an identifier that moves breaks both regardless of which spelling is
"right".

The deciding evidence was already in our own runs. `severity` has been a Java enum from the
start and returned the identical value for every sample across every run. `errorType` was
prose and drifted twice under identical conditions. That is a controlled comparison: the
difference is not the model, it is that one field's allowed values are part of the type and
the other's were a request written in prose. Prompts shift behaviour; they do not constrain
it. If a value has to be stable, it belongs in the type.

`ErrorType` now holds 14 failure categories plus `NotALogFile` and `Other`. LangChain4j
derives the format instructions from the return type, so those constants are sent to the
model as the permitted set — the same machinery that already kept `severity` honest.

### The naming-convention mismatch, and why the lenient parser is load-bearing

Two conventions are unavoidably in play at once:

- LangChain4j builds the schema from `getEnumConstants()` and `name()`, so the model is shown
  `CONNECTION_POOL_EXHAUSTED`.
- The API contract predates the enum and serialises PascalCase. The brief's example JSON and
  `AnalyzeControllerTest` both assume `ConnectionPoolExhausted`, and an unrelated wire-format
  change had no business riding along with this one.

`@JsonValue` pins the wire format, but on its own it would make Jackson *expect* the PascalCase
form on the way in — while the schema is advertising the `name()` form. That mismatch would
fail to parse on every single call.

The `@JsonCreator` factory is what stops the collision: it lowercases the model's answer and
strips non-alphanumerics before matching, so `CONNECTION_POOL_EXHAUSTED`,
`ConnectionPoolExhausted`, `connection-pool-exhausted` and `connection pool exhausted` all
resolve to one constant. So the leniency is not defensive politeness towards a sloppy model —
it is the thing that makes the two conventions coexist.

**Its boundary matters as much as its existence.** It ignores case and punctuation only. It
will never map one failure class onto a different one: `RedisConnectionFailure` does *not*
become `CACHE_UNAVAILABLE`, it becomes `OTHER` and is logged with the raw value. That is
deserialisation robustness, not a synonym table — a synonym table would be unbounded
maintenance, and worse, it would hide the fact that a constant is missing.

`OTHER` is therefore a measurement rather than a failure. A high `OTHER` rate across the
samples is the evidence that the vocabulary needs another constant. Grow it from that, not
from guessing.

### A deliberate omission

There is no generic `AUTHENTICATION_FAILURE` constant. Including one would hand the model an
easy symptom-level answer for `auth-failure-spike`, whose whole point is that the cause is an
expired signing certificate — and getting that sample to name the cause cost a full baseline
run to establish. Nothing in the current sample set needs a generic auth value, so it was left
out under the same "grow from evidence" rule. This is a judgement call and a reversible one:
a real incident with genuinely bad credentials and no deeper cause would justify adding it.

### Status: verified 2026-08-10, re-confirmed 2026-08-11

`AnalyzerStabilityTest` passed **3 of 3**, `CACHE_UNAVAILABLE` every time, no quota abort. An
earlier attempt had stopped at two of three, and two agreeing runs was never the check
passing; this is.

The 8-sample run behind it measured the **`OTHER` rate at 0 of 8** — every sample landed on a
real constant, and nothing had to be rescued by the lenient parser's case/punctuation
matching. On the "grow the vocabulary from evidence" rule that argues for adding no constants.
The 2026-08-11 run repeated 0 of 8 on all eight samples, so that now rests on two full runs.

The limit of that evidence: 0% across eight samples written before the enum existed rules out
the vocabulary being too small *for what we test*. It says nothing about unseen failure types
in production.

What the enum is and is not guaranteeing is worth keeping straight. It guarantees the
**vocabulary** — every answer is one of the constants, and no run has to be rescued by the
lenient parser. It does not guarantee **which** constant, even with the input and the prompt
both held fixed. `downstream-timeout` moved `UPSTREAM_TIMEOUT` → `UPSTREAM_UNAVAILABLE` on a
prompt edit and then moved back on the 2026-08-11 run, which changed nothing at all. Both are
valid constants, so the type held throughout; the choice inside the type moved on its own.

The 3x stability check is not in tension with that. It measured one sample —
`cache-miss-spike`, where `CACHE_UNAVAILABLE` is the only defensible answer — and got the same
constant three times. Where two constants are genuinely arguable the choice is a judgement,
and judgements vary run to run. Stability is a property of the sample as much as of the type.

## Known limitations

Deliberately left, not overlooked.

**`slow-query-degradation` names a symptom.** Under free text this sample returned
`DatabaseQueryPerformanceIssue` on every run, even though the logs state the cause twice
(`planner prefers Seq Scan`, `has not used index idx_variants_attributes_gin`). The
cause-over-symptom prompt rule fixed the equivalent problem in `auth-failure-spike` and did
not move this one at all, which suggests the two are different failures: the rule works when
the cause is a discrete logged event and not when it is a gradual regression.

It was left for three reasons. Widening the accepted set to admit the symptom name would have
been widening for *meaning*, which destroys what the sample tests. Adding a second prompt rule
aimed at gradual degradation risked the same bleed that the cause rule caused in
`firstOccurrence`. And the enum has since changed the mechanics of this field entirely — the
expected answer is now `SLOW_QUERY`, chosen from a closed list — so anything done before
re-measuring would be fixing a problem that may no longer exist. **Resolved by the enum** — it
returned `SLOW_QUERY` on the 2026-08-10 run, so the closed list fixed what a prompt rule could
not move, and again on 2026-08-11, which is the re-measure the affectedService change owed it.
Left here rather than deleted: the reasoning is the useful part.

**`cache-miss-spike` returns MEDIUM where the harness expects LOW.** Consistent across every
run. The expectation is deliberately strict and the model's answer is not clearly wrong: Redis
dropped, 214,883 cache entries were lost and one request was served on a cache bypass, so
"partial failure" (MEDIUM) is defensible even though nothing failed for a user, p99 stayed
under SLO and the hit rate recovered to 93.8% (LOW).

It was left because the interesting question is not this sample's verdict but the pattern
behind it: **no sample in the set has ever returned LOW.** That would be a real finding about
severity calibration — or it would be an artefact of having exactly one LOW sample, written by
the same person who wrote the expectation. One sample cannot tell those apart. The honest fix
is more low-severity samples before any prompt change, not tuning the prompt until this one
case flips.

### Nothing else is open

As of 2026-08-11 the `cache-miss-spike` severity floor above is the only failing field in the
set. Four entries that used to live here are closed, and how each one closed is worth keeping:

- **`auth-failure-spike` returned `api-gateway` for `affectedService`.** Fixed by the
  origin-vs-reporter rule, and held on the run after.
- **Confidence calibration.** Dropped rather than fixed. The field varies on its own — four
  distinct values tracking difficulty, 0.80 to 0.95 — so the "0.95 on nearly everything"
  concern was an artefact of the early test set. No anchors were added.
- **`out-of-memory` reported the wrong `firstOccurrence`.** Recorded here as a *stable
  regression* on two byte-identical runs. It was variance. The third run returned the expected
  `03:41:12Z` with nothing changed. See *how many runs settle a question* below — this is the
  entry that falsified the old rule.
- **`downstream-timeout` returned `UPSTREAM_UNAVAILABLE`.** One observation, hypothesised to
  be availability vocabulary bleeding out of the `affectedService` rule. It reverted to
  `UPSTREAM_TIMEOUT` on an unchanged run, so the hypothesis is unsupported and the wording was
  left alone. Had it been acted on after one observation, the trim would have been credited
  with a fix that was going to happen anyway.

### How many runs settle a question

**Two identical answers do not distinguish a regression from variance.** This was written into
the run log as though it did, and `out-of-memory` falsified it: `03:40:55`, `03:40:55`,
`03:41:12` across three runs, prompt unchanged throughout. Two agreeing runs were enough to
get it recorded as a stable regression with a candidate commit attached, and a fix aimed at
that commit would have been chasing noise.

`AnalyzerStabilityTest` already had the right rule — it asserts **3 of 3**, and an earlier
attempt that stopped at two of three was explicitly not counted as passing. The baseline did
not apply the same standard to itself. The distinction that justified the difference was that
stability measures a mechanism while the baseline measures judgement, but that argues for
*more* runs on the judgement fields, not fewer.

So: **a judgement field needs three runs before a change in it is called a regression.** That
is expensive — three runs is the entire daily quota — which is the real argument, alongside
cost, for the week 3 cache. Until then the affordable discipline is to state how many
observations a claim rests on, and to treat a one- or two-run finding as a hypothesis with the
run count written next to it.

## Run log

Newest first. Compare matrices across runs, not fields within one.

### 2026-08-11 — unchanged control run. Step 4 closes here

No prompt change. The tree was clean at `a40b2cb` and this run measured it as-is, for two
reasons: three samples never executed on 2026-08-10, and the two open items each rested on
observations that an unchanged run could confirm or kill.

**All eight executed**, 3:13 wall clock, no 429. **7 of 8 correct on all four fields.**

```
 SAMPLE                       TYPE   SERVICE  SEVERITY  FIRSTOCC  VERBATIM  CONF       MS
 connection-pool-exhaustion   ok     ok       ok        ok        4/4       0.95     1850
 out-of-memory                ok     ok       ok        ok        4/4       0.95      797
 downstream-timeout           ok     ok       ok        ok        4/4       0.90     1047
 disk-full                    ok     ok       ok        ok        4/4       0.95     1191
 auth-failure-spike           ok     ok       ok        ok        5/5       0.95     1283
 thread-deadlock              ok     ok       ok        ok        3/3       0.95      888
 slow-query-degradation       ok     ok       ok        ok        3/3       0.80      955
 cache-miss-spike             ok     ok       FAIL      ok        3/3       0.85      812
```

| Sample | vs previous run |
|---|---|
| connection-pool-exhaustion | unchanged, all four ok |
| out-of-memory | **`firstOccurrence` FAIL → ok** |
| downstream-timeout | **`errorType` FAIL → ok**, reverted to `UPSTREAM_TIMEOUT` |
| disk-full | unchanged, all four ok |
| auth-failure-spike | unchanged, all four ok — the origin fix held a second run |
| thread-deadlock | first execution since the change, all four ok |
| slow-query-degradation | first execution since the change, all four ok |
| cache-miss-spike | first execution since the change, `severity` MEDIUM — the known limitation |

**The datastore bullet is cleared, not merely untested.** `cache-miss-spike` returned
`profile-service`; the broadened origin rule did not promote the dropped Redis to an origin of
its own. `slow-query-degradation` returned `catalog-service` and `thread-deadlock` returned
`pricing-service`. That was the specific regression risk left hanging by the 429, and it did
not materialise.

**Both open items reverted with nothing changed, so both were variance.** `downstream-timeout`
went back to `UPSTREAM_TIMEOUT`, which was the pre-registered test and it failed — the
availability-vocabulary hypothesis is unsupported and the wording stays. `out-of-memory`
returned the expected `03:41:12Z` after two byte-identical runs at `03:40:55`, so the "stable
regression" was not stable and 712362b was never implicated. Neither queued prompt change was
made. See *how many runs settle a question*.

**Unchanged measurements.** `OTHER` rate 0 of 8, second consecutive run. Confidence spread
four distinct values, 0.80 / 0.85 / 0.90 / 0.95, tracking difficulty — consistent with
dropping the calibration anchors. Verbatim clean on every sample, including the multi-line
deadlock dump and the JSON-structured auth logs.

`downstream-timeout` severity was CRITICAL again where the previous run read HIGH → CRITICAL;
the expectation admits both, so it scores ok either way and is noted only so the next reader
does not rediscover it as news.

**Step 4 closes on this matrix.** The remaining failure is `cache-miss-spike` severity, which
is a deliberate known limitation and is not to be tuned until the sample set has more than one
LOW case. Run 2 of the day's quota was not spent — with both queued items resolved there was
no change left to measure, and a run with no hypothesis attached is just spending.

### 2026-08-10 — affectedService origin-vs-reporter rule

The change: in the `affectedService` block, the origin rule now leads and the service-tag
heuristic is subordinated to it, victimhood is broadened past the down/timeout shape to cover
a dependency that is UP and emitting bad output, and a datastore/cache/broker owned by a
service is declared part of that service rather than a peer.

**Target met.** `auth-failure-spike` returned `auth-service` after three consecutive runs of
`api-gateway`. Four other services were unchanged (`payment-service`, `order-service`,
`inventory-service`, `media-service`).

**The run did not finish.** The daily 100k cap landed with three samples unrun —
`thread-deadlock`, `slow-query-degradation`, `cache-miss-spike`. Those last two are exactly
where the datastore bullet was expected to be load-bearing: `cache-miss-spike` has a Redis
drop that the broadened origin rule could plausibly pull toward `redis-cache` instead of
`profile-service`. **That risk is untested, not cleared.** The runner's "3 of 8 correct"
undercounts for the same reason — it is 3 of the 5 that executed.

| Sample | vs previous run |
|---|---|
| connection-pool-exhaustion | unchanged, all four ok |
| out-of-memory | unchanged, `firstOccurrence` FAIL |
| downstream-timeout | **`errorType` ok → FAIL** |
| disk-full | unchanged, all four ok |
| auth-failure-spike | **`affectedService` FAIL → ok** |
| thread-deadlock, slow-query-degradation, cache-miss-spike | not run — 429 TPD |

**`out-of-memory` `firstOccurrence` is a stable regression.** Byte-identical across two runs:
`2026-08-06T03:40:55.239Z`, the `Export EXP-4471 loaded 1600000 rows into memory` INFO line,
against an expected `03:41:12Z` (the GC thrash) or later. Two identical answers settle it as a
regression rather than variance. The model is not picking the loudest line — it goes the other
way, past the earliest symptom to the earliest *cause*. Loading 1.6M rows is why the heap
filled, but it is normal operation, not a symptom.

It **predates the affectedService change**, appearing first in the run before it, so that
change is not the cause. The remaining candidate is 712362b (two worked examples for
`firstOccurrence`) — it is the commit that touched this field, it landed after the last run
where the sample was correct, and it demonstrably moved `firstOccurrence` behaviour elsewhere
in the same window: `disk-full` began converting `+05:30` → `16:17:29Z` correctly again, which
closes that open question as fixed rather than variance.

> **Superseded by 2026-08-11.** The third run returned `03:41:12Z` with the prompt unchanged,
> so this was variance and 712362b was never implicated. The two paragraphs above are left
> standing because the reasoning error is the useful part: two agreeing runs were treated as
> settling the question, and a commit was named on that basis. `disk-full`'s conversion fix is
> unaffected — it has now held across three runs.

**`downstream-timeout` errorType drifted, and the anti-bleed clause did not prevent it.** The
new rule ends with "This origin rule governs affectedService and NOTHING ELSE. It must not
change which timestamp you report or which errorType you choose," and `errorType` moved
anyway, `UPSTREAM_TIMEOUT` → `UPSTREAM_UNAVAILABLE`. Severity also shifted HIGH → CRITICAL,
though the expectation admits both so it scored ok.

The hypothesis is **availability vocabulary**: the new wording introduced "unreachable",
"down" and "UP and emitting bad output" into a prompt that then had to choose between a
timeout constant and an unavailability constant. That is a hypothesis, not a finding. One
observation cannot separate it from ordinary variance in a judgement between two plausible
constants, and the next run is unchanged precisely so this gets a second data point. If it
sticks, trim the availability wording; if it reverts, it was variance.

Both fields already had explicit fencing before this, and the fencing has now failed once.
Worth weighing before reaching for a third prompt rule.

> **Superseded by 2026-08-11.** It reverted on an unchanged run, so the availability-vocabulary
> hypothesis is unsupported and the wording was never trimmed. The "fencing failed" reading
> also goes with it: there is no longer any evidence the prompt edit moved this field at all.
> What the pair of runs actually shows is that a two-constant judgement moves on its own.

## It reports, it does not assert

The runner contains no assertions and always passes. Two reasons:

1. A failed assertion aborts at the first bad sample and hides the other seven. The point is
   to see every failure mode in one run.
2. The samples are calibration, not a contract. Turning them into a gate is a decision for
   after the prompt is tuned — and it would make the build depend on a paid API and on
   run-to-run model variance.

A per-sample exception (a 429, a parse failure) is captured and reported as `CALL FAILED`
rather than thrown, so one bad sample never costs the rest of the run.

`AnalyzerStabilityTest` is the deliberate exception: it *does* assert. The distinction is what
each one measures. The baseline calibrates against a model's judgement, where a miss can be an
off day. Stability is a guarantee the type system now makes, so a failure there means the
mechanism is broken rather than the model being unlucky. It draws the same line for
infrastructure: a rate limit means the experiment could not be run, which is not the
experiment failing, so it retries once and then aborts as *skipped* — printing the runs it did
collect. Its first real run proved why that matters, surfacing a quota exhaustion as a red
test that looked exactly like instability.

## Reading the output

Per-sample detail blocks first, so you can watch progress, then a comparison matrix:

```
 SAMPLE                       TYPE   SERVICE  SEVERITY  FIRSTOCC  VERBATIM  CONF       MS
 connection-pool-exhaustion   ok     ok       ok        ok        4/4       0.95     2019
 out-of-memory                ok     ok       ok        ok        4/4       0.95     1005
 ...
```

Compare matrices across runs, not fields within one run. A prompt change that fixes its target
and quietly breaks something else looks identical to a clean fix if you only read the current
run — which is why each prompt change gets its own run.
