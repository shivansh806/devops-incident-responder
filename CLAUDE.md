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
- I am learning — when you use a new concept, explain it in 2-3 lines

## Current Status
Week 1 — Step 3 done.
- Spring Boot skeleton, GET /api/hello working with test
- LangChain4j configured with Groq
- .env + spring-dotenv for GROQ_API_KEY
- Analyzer Agent: LangChain4j AI Service (`AnalyzerAgent`) returning a typed
  `LogAnalysis`, wired into POST /api/analyze. Temporary /api/test-llm removed.
- Structured output uses LangChain4j's prompt-based JSON path, not native
  `json_schema` — Groq's schema support is model-dependent. Do not declare
  `RESPONSE_FORMAT_JSON_SCHEMA` on the model without re-testing.
- `serviceName` in the request is optional. When present it overrides whatever
  the model says for `affectedService`; when absent the model infers it. Don't
  make it mandatory — a multi-service dump is exactly the case where the caller
  does not know the origin.
- Jackson `fail-on-unknown-properties` stays **false** (Week 3 Kafka events will
  carry extra fields). `JacksonConfig` logs a WARN instead so drops stay visible.
- Next: Week 2 — MongoDB, seed past incidents, Resolver Agent
