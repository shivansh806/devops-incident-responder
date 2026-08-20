# Week 4, step 3. Builds one image that serves the dashboard bundle and the API from a
# single origin - the topology the Vite dev proxy has been standing in for since step 2.
#
# Three stages, because the thing that builds an artifact is not the thing that runs it.
# Node and Maven exist only during the build; the runtime receives one jar.
#
#   docker compose --profile app up -d --wait --build
#
# Mongo is deliberately absent: it stays on Atlas, reached with MONGODB_URI from .env.
# The vector index has no plain community-Mongo equivalent. See docs/infra.md.


# ---------------------------------------------------------------------------
# Stage 1 - the dashboard bundle
# ---------------------------------------------------------------------------
FROM node:22-alpine AS frontend

WORKDIR /frontend

# Manifests first, sources second. Docker caches per instruction, so this ordering
# means editing App.jsx re-runs the build but NOT the install. Copy the sources first
# and every image build re-downloads every npm dependency.
COPY frontend/package.json frontend/package-lock.json ./
# ci, not install: installs exactly the lockfile and fails if the two disagree, which is
# what makes an image build reproducible rather than "whatever resolved today".
RUN npm ci

COPY frontend/ ./
# Vite emits to dist/ with hashed asset names and absolute /assets/... references, which
# is correct here precisely because the bundle is served from the application root.
RUN npm run build


# ---------------------------------------------------------------------------
# Stage 2 - the application jar, with the bundle inside it
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS backend

WORKDIR /build

# Same ordering argument as above. Resolving this project's dependencies is the slowest
# step in the whole build - roughly 200MB of embedding jars alone - and a Java source
# edit must not invalidate it.
COPY pom.xml ./
RUN mvn -B dependency:go-offline

COPY src/ ./src/

# The single line that makes one origin structural rather than arranged. Spring Boot
# serves classpath:/static/** with no configuration at all, so putting the bundle here
# means the same process that answers /api/analyze and /ws/incidents also hands out the
# page that calls them. No second server, no reverse proxy, nothing whose config could
# drift away from the application's.
COPY --from=frontend /frontend/dist/ ./src/main/resources/static/

# Tests are skipped on purpose. The image build is not this project's test gate - `mvn
# test` is, and all 194 run offline. Running them here would double every image build to
# re-prove what a green run already proved.
RUN mvn -B package -DskipTests


# ---------------------------------------------------------------------------
# Stage 3 - runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# curl is here for the container healthcheck and nothing else. It earns its size: with
# no healthcheck, `--wait` returns as soon as the JVM process exists, which is well
# before Spring can serve a request - the same trap docs/infra.md records for Kafka,
# where a plain `up -d` returned about ten seconds early and read as a config error.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Runs as a non-root user. The image holds an API key in its environment and an incident
# corpus in its memory; root inside the container is root on a bind mount if one is ever
# added.
RUN useradd --system --create-home --uid 10001 responder
USER responder
WORKDIR /home/responder

# One artifact crosses from the build. Neither Node, nor Maven, nor node_modules, nor
# ~/.m2 exists past this line - on the order of a gigabyte of build-time-only weight.
# The image is still large regardless: the local embedding model is ~200MB of jars
# (83MB model, 93MB onnxruntime, 19MB DJL tokenizer), which CLAUDE.md's cost note said
# to budget for here rather than be surprised by. The -q quantized model is the lever.
COPY --from=backend /build/target/*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --retries=12 --start-period=40s \
    CMD curl -fsS http://localhost:8080/api/hello || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
