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
Week 1, **Step 4 CLOSED** on the 2026-08-11 control run. The full run log and
reasoning are in docs/baseline.md.

Final Step 4 result — full 8-sample baseline, unchanged prompt, all eight
executed, no quota abort:
- **7 of 8 correct** on all four scored fields
- **OTHER rate 0 of 8** — second consecutive run, no vocabulary gap
- **Confidence spread 0.80–0.95**, four distinct values tracking difficulty
- Verbatim clean on every sample, multi-line dump and JSON logs included
- The one failure is cache-miss-spike severity (MEDIUM vs expected LOW), a
  deliberate known limitation — do not tune it until the set has more than one
  LOW sample

Done:
- Spring Boot skeleton, LangChain4j + Groq configured, .env for API key
- Analyzer Agent returning typed structured output via POST /api/analyze
- 8 log samples + AnalyzerBaselineTest (tagged `llm`, excluded from `mvn test`)
- Fix (a): errorType names the cause, not the symptom
- Fix: errorType's cause rule separated from firstOccurrence's
  earliest-symptom rule (these were bleeding into each other)
- Fix (b): forbid splicing a timestamped header onto a continuation line
- errorType closed enum, VERIFIED end to end: AnalyzerStabilityTest 3/3 clean
  (CACHE_UNAVAILABLE three times) and the OTHER rate is 0/8 twice over.
- Fix: affectedService names the origin, not the reporter. auth-failure-spike
  returns auth-service after three runs of api-gateway, and held on the
  following run. The datastore/cache bullet's regression risk is now cleared:
  cache-miss-spike returns profile-service, not redis-cache.
- disk-full's +05:30 → UTC conversion holds across three runs.

Not doing, and why — all three resolved without a prompt change:
- Fix (c), confidence calibration anchors. Confidence varies on its own (0.95
  clear, 0.80–0.90 ambiguous, four distinct values). The "0.95 on everything"
  concern was an artefact of the early test set.
- downstream-timeout's UPSTREAM_TIMEOUT → UPSTREAM_UNAVAILABLE drift. Reverted
  on the unchanged run, so it was variance. The availability vocabulary in the
  affectedService rule stays as written.
- out-of-memory's firstOccurrence "stable regression". Also reverted. Two
  byte-identical runs were not enough to call it stable — see the run-count
  rule under Prompt engineering learnings. 712362b was never implicated.

Next: Week 1 Step 5. Nothing is queued against the baseline.

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

## Cost note
Groq free tier: 100k tokens/day. Four full 8-sample baseline runs exhausted it.
This is the concrete justification for Week 3's Redis caching layer.

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