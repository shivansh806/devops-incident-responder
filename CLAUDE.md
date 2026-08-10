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
Week 1, Step 4 — prompt tuning against the 8-sample baseline. Measured state as
of 2026-08-10. The run log with the full reasoning is in docs/baseline.md.

Done:
- Spring Boot skeleton, LangChain4j + Groq configured, .env for API key
- Analyzer Agent returning typed structured output via POST /api/analyze
- 8 log samples + AnalyzerBaselineTest (tagged `llm`, excluded from `mvn test`)
- Fix (a): errorType names the cause, not the symptom
- Fix: errorType's cause rule separated from firstOccurrence's
  earliest-symptom rule (these were bleeding into each other)
- Fix (b): forbid splicing a timestamped header onto a continuation line
- errorType closed enum, now VERIFIED end to end: AnalyzerStabilityTest 3/3
  clean (CACHE_UNAVAILABLE three times) and the 8-sample OTHER rate is 0/8.
- Fix: affectedService names the origin, not the reporter. auth-failure-spike
  returns auth-service after three runs of api-gateway. Four other services
  unchanged.
- disk-full's +05:30 → UTC conversion is correct again. That open question is
  closed — fixed by the worked examples, not variance.

Not doing:
- Fix (c), confidence calibration anchors. Confidence varies on its own (0.95
  clear, 0.80–0.90 ambiguous, four distinct values). The "0.95 on everything"
  concern was an artefact of the early test set.

Next, in this order. One full 8-sample run is ~27k of the 100k/day, so this is
roughly three runs' worth of work and they cannot be chained in one day:
1. Re-run the full 8 UNCHANGED. Three samples never ran on 2026-08-10
   (thread-deadlock, slow-query-degradation, cache-miss-spike), so the
   datastore-bullet regression risk is untested rather than cleared — and it
   gives downstream-timeout's drift a second data point.
2. downstream-timeout errorType: UPSTREAM_TIMEOUT → UPSTREAM_UNAVAILABLE, one
   observation. If it sticks, trim the availability vocabulary ("unreachable",
   "down", "UP and emitting bad output") out of the affectedService rule.
3. out-of-memory firstOccurrence: stable regression, byte-identical over two
   runs, reporting the cause (03:40:55 export load) instead of the earliest
   symptom (03:41:12 GC thrash). Candidate is 712362b. Its own fix, own run.

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
- An enum pins the vocabulary, not the choice. errorType was stable 3/3 on
  identical input and prompt, then moved between two valid constants when an
  unrelated rule was reworded. The type stops "which spelling"; only a run
  answers "which constant".
- Writing "this rule governs X and NOTHING ELSE" into the prompt did not stop
  the bleed — errorType moved on a change scoped to affectedService. Fencing
  is worth writing, but it is not a control. Only the matrix tells you.