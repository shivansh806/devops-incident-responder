# Resolver measurement

What the Resolver Agent does when past incidents disagree, measured rather than assumed. It
exists to answer one question: **given three precedents that share a failure class and reached
opposite conclusions, does the agent choose between them on evidence?**

This is the pre-fix baseline. Nothing in the prompt has been changed in response to it.

- Agent and prompt: `src/main/java/com/shivansh/incidentresponder/agent/ResolverAgent.java`
- Rendering: `src/main/java/com/shivansh/incidentresponder/agent/ResolverPromptText.java`
- Normalisation: `src/main/java/com/shivansh/incidentresponder/service/ResolverService.java`
- Harness: `src/test/java/com/shivansh/incidentresponder/agent/ResolverBaselineTest.java`

## The case

A synthetic `subscription-service` connection-pool exhaustion, built so that **chronological
order and similarity order disagree** — the property `docs/retrieval.md` noted was missing from
its own production-shaped query, where the two coincided and the oldest-first sort was never
exercised.

| candidate | listed | similarity | conclusion it reached |
|---|---|---|---|
| INC-2103 | **1st** (oldest, Mar 9) | 0.9476 | pool was a symptom — do **not** grow it |
| INC-2331 | 2nd (Jun 3) | **0.9614** | pool genuinely undersized — **grow it** |
| INC-2464 | 3rd (Jul 28) | 0.9172 | pool was a symptom — do **not** grow it |

**INC-2331 is correct here**, and it is the minority conclusion: two of the three precedents
refuse to grow the pool. The diagnosis carries everything needed to establish that:

- `Connection acquisition p99 2ms -> 4100ms; statement execution p99 unchanged at 6ms` — INC-2331's own discriminator, stated in its notes as *"acquisition time versus query time is what separates the two"*
- `No statement exceeded the 500ms slow-query threshold in the last 30 minutes (0 entries)` — excludes INC-2464
- `Mean transaction hold time 9ms, p99 16ms; no outbound HTTP calls recorded inside transaction scope` — excludes INC-2103

If the Resolver hedges here it is not for want of evidence.

## Setup these numbers came from

| | |
|---|---|
| Model | llama-3.3-70b-versatile on Groq, temperature 0.2 |
| Diagnosis | frozen in the harness, five evidence lines, not produced by a live Analyzer |
| Candidates | frozen, loaded from `seed/incidents.json`, scores from `RetrievalProbe` |
| Retrieval | not called — Atlas is bypassed |
| Prompt size | ~3,500 tokens per call |

**Why the inputs are frozen.** A baseline is comparable across weeks only if its inputs cannot
drift. Freezing also drops the cost per observation by most of an order of magnitude, because
the Analyzer call — a log dump plus a 9,000-character system prompt — is not what is being
measured. That is the difference between affording three observations and affording twelve, and
it matters: at the failure rate below, three clean runs occur by chance about 30% of the time.

## The twelve observations

All twelve are on identical input. Two batches only because the first hit Groq's **12,000
tokens-per-minute** ceiling — separate from the 100,000/day budget — and lost four calls to 429s.
The harness now paces at 25s. The four refused calls returned no output and are not observations;
the four that landed are, and they are included. Discarding them would flatter the result, for
reasons the next section makes concrete.

| # | batch | conf | cited | rootCause | verdict |
|---|---|---|---|---|---|
| 1 | A | 0.85 | `[INC-2331]` | undersized for the current traffic | correct |
| 2 | A | **0.85** | `[INC-2103, INC-2331]` | connections held across a slow operation | **wrong** |
| 3 | A | 0.80 | `[INC-2331, INC-2103]` | likely undersized | correct |
| 4 | A | 0.60 | `[INC-2103, INC-2331]` | held across a slow network call or transaction | **wrong** |
| 5 | B | 0.85 | `[INC-2331]` | likely undersized | correct |
| 6 | B | 0.80 | `[INC-2331]` | likely undersized, acquisition times up | correct |
| 7 | B | 0.55 | all three | *"the exact mechanism is not clear from the provided evidence"* | undecided |
| 8 | B | 0.85 | `[INC-2331]` | undersized for the current workload | correct |
| 9 | B | 0.85 | `[INC-2331]` | undersized to handle the current load | correct |
| 10 | B | 0.80 | `[INC-2331]` | undersized **or** an issue with the acquisition process | correct, hedged |
| 11 | B | 0.78 | `[INC-2103, INC-2331]` | held across a slow operation, potentially a network call | **wrong** |
| 12 | B | 0.80 | `[INC-2331]` | undersized for the current traffic | correct |

**8 correct, 3 wrong, 1 undecided — 67%.**

Confidence: `0.55, 0.60, 0.78, 0.80, 0.80, 0.80, 0.80, 0.85, 0.85, 0.85, 0.85, 0.85`, mean 0.79,
**spread 0.30 on fixed input**.

## 1. The failure is a contradiction, not a hedge

The common failure — 3 of 12 — is asserting INC-2103's conclusion, that connections are held
across a slow outbound call. The prompt contains `no outbound HTTP calls recorded inside
transaction scope`. **The exclusion is present and goes unused.**

This is worse than the blend it was mistaken for after one observation. A blend announces itself:
it cites every precedent, names no mechanism, and reports low confidence. A contradiction reads
as a decision. Observation 2 asserted a ruled-out cause at **0.85** with a two-precedent citation
and a confident action list.

The blend proper occurred **once** in twelve (observation 7), at the lowest confidence recorded,
and it was honest about itself: *"the exact mechanism is not clear from the provided evidence."*
That is the correct answer to a case it could not resolve — wrong here only because the case was
resolvable.

> The first live run was a blend, and it was written up as the failure mode. It was one
> observation of the rarer and less serious of the two.

## 2. Confidence is a signal, not a gate

| band | correct |
|---|---|
| ≥ 0.80 | **8 of 9** |
| < 0.80 | **0 of 3** |

Everything below 0.80 failed, which is genuinely useful — a downstream consumer could treat it as
"not decided". But the single counterexample is the number that matters: **observation 2 was wrong
at 0.85**, the joint-highest confidence recorded.

In batch B alone the separation is perfect — everything ≥0.80 correct, everything below not — and
that is exactly why the four batch-A observations are in this table. Dropping them on the grounds
that their batch hit a rate limit would produce a clean, false rule: *confidence ≥0.80 means
correct*. One discarded observation is the whole difference between a safe gate and a hint.

## 3. When it works, it is genuinely using the precedent

The correct runs reproduce INC-2331's own chain — confirm traffic, verify database headroom, then
raise the pool — and several name `hikaricp_connections_pending`, a string that appears nowhere
except INC-2331's resolution notes. That is retrieval paying for itself, not a prior about
connection pools.

## 4. Position anchoring: not shown, not excluded

Every failure lands on **INC-2103**; none lands on INC-2464. INC-2103 is both first-listed and
second-highest-scoring, so position and similarity are confounded in this case and it cannot
separate them. INC-2464 is last on both, which is consistent with either explanation.

What can be said: the oldest-first sort did not prevent the first-listed precedent from winning
the failures, and it did not cause the correct second-listed precedent to lose the majority of
runs.

## The mechanism

Dumped offline with a capturing `ChatModel` (`ResolverPromptDump`), no Groq call:

```
---END PAST INCIDENTS---

You must answer strictly in the following JSON format: {
"rootCause": (type: string),
...
```

LangChain4j derives format instructions from the return type and appends them to the **end of the
user message**. Generation is autoregressive, so `rootCause` is the first thing produced. The
four-step discriminator procedure sits at character 1,955 of a 9,141-character system prompt —
roughly 11,000 characters and one full candidate set earlier.

Steps 1 and 2, *find the discriminator* and *test it against the current evidence*, are work that
must happen before a conclusion exists. There is no field for it, no scratchpad, and nothing
between the last candidate and the demand for `rootCause`. **The procedure has nowhere to run.**

A model forced to produce a conclusion first, holding three contradictory sources, does the work
implicitly or not at all — which predicts exactly what the distribution shows: mostly right, with
a minority of runs that commit to a ruled-out cause and one that gives up.

## Known limitations

**One case.** Every number here is one synthetic incident with one correct answer. Nothing about
this generalises to other failure classes yet.

**The diagnosis is frozen and hand-built**, not produced by a live Analyzer. It approximates what
the Analyzer produced on the live run but is not identical, so these rates are not directly
comparable to full-pipeline runs.

**Twelve observations is small.** The 67% has a wide interval; treat the ordering of the failure
modes as established and the exact rate as indicative.

**Not the same prompt after any fix.** Adding a field changes the schema, which changes the tail
of the prompt, so everything here describes the current prompt and nothing else.

**For context, not pooled:** three full-pipeline runs of the same incident through
`POST /api/analyze` gave one blend (0.6) and two correct (0.7, 0.85). Different case — the live
Analyzer chose its own evidence lines — so those are not in the twelve.

## Reproducing

```
mvn test -Dtest=ResolverBaselineTest -Dsurefire.excludedGroups= -DfailIfNoSpecifiedTests=false
```

Eight calls, ~28k tokens, ~3.5 minutes with pacing. Tagged `llm`, so `mvn test` never runs it.

## Status: measured 2026-08-16, pre-fix

12 observations, one case, prompt unchanged from commit `7380e74`.
