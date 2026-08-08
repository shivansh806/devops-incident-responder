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
Week 1, Step 4 in progress — prompt tuning against an 8-sample baseline.

Done:
- Spring Boot skeleton, LangChain4j + Groq configured, .env for API key
- Analyzer Agent returning typed structured output via POST /api/analyze
- 8 log samples + AnalyzerBaselineTest (tagged `llm`, excluded from `mvn test`)
- Fix (a): errorType names the cause, not the symptom
- Fix: errorType's cause rule separated from firstOccurrence's
  earliest-symptom rule (these were bleeding into each other)

Next:
- Fix (b): forbid splicing a timestamped header onto a continuation line
- Fix (c): confidence calibration anchors
- Open question: disk-full firstOccurrence lost its +05:30 → UTC conversion
  in the last run. Side effect or variance? Needs a repeat run.

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