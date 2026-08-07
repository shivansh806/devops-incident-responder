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
Week 1 — Step 2 done.
- Spring Boot skeleton, GET /api/hello working with test
- LangChain4j configured with Groq
- .env + spring-dotenv for GROQ_API_KEY
- Next: Analyzer Agent (structured log analysis)
