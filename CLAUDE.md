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

**Next:** seed 15-20 past incidents, then embeddings + vector search,
then the Resolver Agent.

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