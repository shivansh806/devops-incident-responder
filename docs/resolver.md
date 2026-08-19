# Resolver measurement

> ## ⚠ Every measurement in this file was made on a model that no longer exists
>
> Both sets — the twelve pre-fix observations and the eight post-fix ones — were measured on
> **`llama-3.3-70b-versatile`**, which Groq **retired**. The application moved to
> **`openai/gpt-oss-120b`** on 2026-08-19 and **nothing here has been re-measured**.
>
> Two things need re-establishing before anything below is acted on, and they are different
> kinds of claim:
>
> 1. **The rates** (67% → 88%, confidence spread 0.30 → 0.12) describe a model that is gone.
> 2. **The mechanism argument may no longer hold.** `decidingEvidence` was added because the
>    four-step discriminator procedure *had nowhere to run*: generation is autoregressive, so
>    the model was forced to emit `rootCause` before any comparison work could happen.
>    **gpt-oss is a reasoning model and does hidden reasoning before emitting any content at
>    all.** The procedure may now have somewhere to run regardless — which would make the fix
>    redundant — or it may still be load-bearing. Unknown, and worth knowing. The cheapest
>    check is the binary one: `decidingEvidence` stated 8 of 8 was the mechanism-fired
>    measure, and it reads off a single run.
>
> The closing instruction — *measure future interventions against misattribution, not
> blending* — is **suspect for the same reason**. It describes llama's residual failure mode.
> Carrying it forward would repeat exactly the error this project has documented twice:
> assuming a prior failure mode is still the live one.
>
> The **cost and pacing** figures in *Reproducing* have been corrected in place, because a
> stale rate limit causes failed runs rather than merely misleading ones.

What the Resolver Agent does when past incidents disagree, measured rather than assumed. It
exists to answer one question: **given three precedents that share a failure class and reached
opposite conclusions, does the agent choose between them on evidence?**

Two measurements: a pre-fix baseline of twelve observations, and a post-fix set of eight after
the `decidingEvidence` change. Same case, same harness, same frozen inputs throughout.

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
| Model | **llama-3.3-70b-versatile** on Groq, temperature 0.2 — **retired, see the banner** |
| Diagnosis | frozen in the harness, five evidence lines, not produced by a live Analyzer |
| Candidates | frozen, loaded from `seed/incidents.json`, scores from `RetrievalProbe` |
| Retrieval | not called — Atlas is bypassed |
| Prompt size | ~3,500 tokens per call |

**Why the inputs are frozen.** A baseline is comparable across weeks only if its inputs cannot
drift. Freezing also drops the cost per observation by most of an order of magnitude, because
the Analyzer call — a log dump plus a 9,000-character system prompt — is not what is being
measured. That is the difference between affording three observations and affording twelve, and
it matters: at the failure rate below, three clean runs occur by chance about 30% of the time.

## Pre-fix: the twelve observations

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

## The fix

One field, declared **first** on `ResolverOutput` and exposed on `AgentResolution`:

```java
public record ResolverOutput(
        String decidingEvidence,   // first, and the order is the point
        String rootCause,
        ...
```

LangChain4j lists schema fields in declaration order, and generation is autoregressive, so the
first field declared is the first field written. The discriminator now has to be produced before
a cause exists. Steps 1 and 2 of the prompt's procedure stop being instructions 11,000 characters
upstream and become the next thing the model owes.

Three defined values keep a required field from manufacturing content — `PRECEDENTS AGREE`,
`NO RELEVANT PRECEDENT`, and `NOT STATED` (substituted by `ResolverService` and logged at WARN
when the model leaves it empty). The prompt says in as many words that inventing a discriminator
is worse than declaring there is none: an invented one is a fabricated justification, and unlike
a fabricated incident id there is no supplied set to check it against.

Exposed rather than internal because it is the most useful line in the response for someone paged
at 3am. A recommendation you can audit is worth more than one you cannot — which run 8 below
demonstrates better than any argument for it did.

## Post-fix: eight observations

| # | conf | cited | verdict |
|---|---|---|---|
| 1 | 0.82 | all three | correct |
| 2 | 0.85 | all three | correct — text explicitly rejects both others |
| 3 | 0.90 | `[INC-2331, INC-2103]` | correct |
| 4 | 0.80 | `[INC-2331, INC-2103]` | correct |
| 5 | 0.85 | `[INC-2331]` | correct |
| 6 | 0.80 | `[INC-2331, INC-2103]` | correct |
| 7 | 0.92 | `[INC-2331]` | correct |
| 8 | 0.85 | `[INC-2103]` | **wrong — see below** |

**7 correct, 1 wrong, 0 undecided.** `decidingEvidence` was stated on **8 of 8**: the mechanism
fired on every call.

| | pre-fix (12) | post-fix (8) |
|---|---|---|
| correct | 8 (67%) | 7 (88%) |
| wrong | 3 | 1 |
| undecided | 1 | 0 |
| confidence mean | 0.79 | **0.85** |
| confidence spread | 0.30 | **0.12** |
| confidence minimum | 0.55 | **0.80** |

## The residual failure mode: stated correctly, misattributed

Run 8's `decidingEvidence`, in full:

> "Connection acquisition p99 increased from 2ms to 4100ms without a corresponding increase in
> statement execution time, which is **more aligned with INC-2103's case of connections being
> held across a slow network call**."

The observation is right. The attribution is wrong. Acquisition rising while execution holds flat
is INC-2331's signature, named in INC-2331's own notes as *"acquisition time versus query time is
what separates the two"* — and the model wrote that observation out correctly and then filed it
under the precedent it excludes.

**This is not the pre-fix failure.** Pre-fix, the model asserted INC-2103's conclusion while
`no outbound HTTP calls recorded inside transaction scope` sat unused in the prompt: the
discriminator was never engaged. Post-fix it engages the discriminator and mis-assigns it. The
work is happening; the mapping from observation to precedent is what failed.

It is also the first failure in this project that **shows its own error**. One line, checkable
against the precedent's own notes by anyone reading the response. The same wrong answer arrived
pre-fix with nothing to check it against.

> **This is the one to watch if the Resolver is revisited.** Any future intervention should be
> measured against misattribution, not against blending — and the pre-fix failure modes should
> not be assumed to still be the live ones.

## A metric that no longer means what it did

"Cites all three" went **up**: 2 of 8, against 1 of 12 pre-fix. It is no longer a blend signature.

Run 2 cites all three and its rootCause reads: *"Unlike INC-2103 where connections were held
across a network call, and unlike INC-2464 where a slow query was the cause..."* That is the
prompt's instruction being followed — include the precedent you reasoned against, because
rejecting one shapes the answer as much as following one.

The pre-fix blend cited all three and named no mechanism. The post-fix runs cite all three and
name a mechanism plus two explicit rejections. **Counting citations alone would now score the
best runs as failures.** Read the text.

## What this establishes, and what it does not

Every measure moved in the predicted direction. Correctness up, confidence up, spread halved,
minimum raised, mechanism firing 8 of 8, and the surviving failure changed character.

**The correctness count alone does not clear significance.** At the pre-fix failure rate of 4 in
12, obtaining 7 or 8 correct out of 8 by chance has probability ≈ **0.20**. Eight runs was chosen
to distinguish a fix from luck at a 1-in-3 failure rate; it distinguishes a *large* effect, and
this one is not large enough on that measure alone.

**The distributional shift is the evidence.** Confidence is continuous, so it carries more per
observation than a binary verdict. Pre-fix put two observations below 0.78 (0.55 and 0.60);
post-fix the minimum is 0.80 and the spread more than halved, on identical input across twenty
total observations. Together with `decidingEvidence` stated 8 of 8 — a mechanism that either
fires or does not, and fired every time — the fix is **supported by the distribution, not proven
by the count**.

Anyone revisiting this should not read "88%" as the number. Read the spread.

## Known limitations

**One case.** Every number here is one synthetic incident with one correct answer. Nothing about
this generalises to other failure classes yet.

**The diagnosis is frozen and hand-built**, not produced by a live Analyzer. It approximates what
the Analyzer produced on the live run but is not identical, so these rates are not directly
comparable to full-pipeline runs.

**Twelve observations is small, and eight is smaller.** Both rates have wide intervals; treat the
ordering of the failure modes as established and the exact rates as indicative. The post-fix set
is the weaker of the two and its correctness figure is the weakest number in this document.

**The two sets are not the same prompt.** Adding a field changes the schema, which changes the
tail of the prompt. The comparison is between two prompts that differ in exactly one intended way
— nothing else was touched between them — but it is not a controlled experiment on one prompt.

**The post-fix set has no undecided runs, so the ABSENT branch is untested.** Every post-fix
observation had a resolvable case in front of it. Whether `decidingEvidence` behaves well when the
discriminator is genuinely missing, and whether `PRECEDENTS AGREE` and `NO RELEVANT PRECEDENT` are
ever actually used, has not been measured at all.

**For context, not pooled:** three full-pipeline runs of the same incident through
`POST /api/analyze` gave one blend (0.6) and two correct (0.7, 0.85). Different case — the live
Analyzer chose its own evidence lines — so those are not in the twelve.

## Reproducing

```
mvn test -Dtest=ResolverBaselineTest -Dsurefire.excludedGroups= -DfailIfNoSpecifiedTests=false
```

Eight calls. **On `gpt-oss-120b` that is ~38k tokens and about nine minutes**, not the ~28k and
3.5 minutes this took on the retired model: the prompt renders to ~3,750 input tokens, gpt-oss
bills its reasoning as completion on top, and the per-minute ceiling dropped from 12,000 to
8,000 — so the pacing had to go from 25s to 65s. Two calls in a 60-second window is now over
the limit, which no setting below a full window can satisfy. Tagged `llm`, so `mvn test` never
runs it.

## Status: measured 2026-08-16 on a model retired since

Pre-fix: 12 observations, prompt as of commit `7380e74`.
Post-fix: 8 observations, after `decidingEvidence` was added as the first output field.
One case throughout. Residual failure mode: **misattribution**, not blending.
