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

- **errorType** — matched against a set of accepted names, compared after lowercasing and
  stripping non-alphanumerics. Naming varies run to run; `DiskSpaceExhausted` and
  `DiskSpaceExhaustion` are the same answer. Widening a set for a suffix is morphology and
  costs nothing; widening it for a different *meaning* would destroy what the sample tests, so
  don't.
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

## It reports, it does not assert

The runner contains no assertions and always passes. Two reasons:

1. A failed assertion aborts at the first bad sample and hides the other seven. The point is
   to see every failure mode in one run.
2. The samples are calibration, not a contract. Turning them into a gate is a decision for
   after the prompt is tuned — and it would make the build depend on a paid API and on
   run-to-run model variance.

A per-sample exception (a 429, a parse failure) is captured and reported as `CALL FAILED`
rather than thrown, so one bad sample never costs the rest of the run.

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
