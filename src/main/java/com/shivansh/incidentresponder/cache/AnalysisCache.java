package com.shivansh.incidentresponder.cache;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.shivansh.incidentresponder.model.LogAnalysis;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Caches Analyzer responses in Redis, keyed by everything that could change the answer.
 *
 * <h2>Why this exists: quota, not latency</h2>
 * One Analyzer call is <b>5,121 measured tokens</b> against a free tier of 100,000 a day.
 * Four full baseline runs exhausted the budget, and a single incident event is two calls
 * whose sum (~9,800) exceeds the 8,000 tokens-per-minute ceiling on its own. A cache hit
 * costs nothing and takes nothing out of the per-minute bucket, which is what makes a demo
 * replay free and leaves the whole bucket for the Resolver.
 *
 * <h2>Failing open, always</h2>
 * Redis being unreachable must never fail a diagnosis. Every operation here catches, logs
 * and carries on: a read failure is a miss, a write failure is a shrug. The same trade
 * {@code ResolverService} makes for retrieval - degraded is better than broken.
 *
 * <h2>The switch is a measurement guard, not a feature flag</h2>
 * {@code incident.cache.enabled=false} bypasses this entirely, and the llm-tagged harnesses
 * run with it off. That is not tidiness. <b>{@code AnalyzerStabilityTest} asks whether the
 * model returns the same answer three times; with the cache on, runs two and three are cache
 * hits and the test passes without measuring anything.</b> A baseline run has the same
 * problem more quietly - a stale hit would corrupt exactly the run-to-run comparability that
 * {@code docs/baseline.md} rests on.
 *
 * <h2>Why there is a TTL at all</h2>
 * The key covers the model id, the reasoning effort, both prompts, the output schema and the
 * enum vocabularies - see {@link AnalysisCacheKey}. What it cannot cover is Groq changing the
 * weights behind {@code gpt-oss-120b} without changing its name, which nothing observable
 * here would detect. The TTL bounds how long a silently-stale answer can survive. It is the
 * only reason for one; correctness otherwise comes from the key.
 */
@Slf4j
@Component
public class AnalysisCache {

    /**
     * Its own mapper, configured rather than inherited - the same lesson
     * {@code IncidentEventParser} records. A cached value must survive a round trip exactly,
     * and {@code Instant} is the field that silently degrades to epoch seconds if
     * {@code WRITE_DATES_AS_TIMESTAMPS} is left at its default.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            // A cached value carrying a field this build does not know about means the schema
            // changed - in which case the fingerprint already changed and this entry is
            // unreachable. Being lenient here costs nothing and avoids a hard failure on a
            // value that is only ever an optimisation.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private final Duration ttl;
    private final String modelName;
    private final String reasoningEffort;

    public AnalysisCache(StringRedisTemplate redis,
                         @Value("${incident.cache.enabled:true}") boolean enabled,
                         @Value("${incident.cache.ttl:7d}") Duration ttl,
                         @Value("${langchain4j.open-ai.chat-model.model-name}") String modelName,
                         @Value("${langchain4j.open-ai.chat-model.reasoning-effort:}") String reasoningEffort) {
        this.redis = redis;
        this.enabled = enabled;
        this.ttl = ttl;
        this.modelName = modelName;
        this.reasoningEffort = reasoningEffort;
        log.info("Analysis cache {} (model={}, reasoning-effort={}, ttl={})",
                enabled ? "ENABLED" : "DISABLED", modelName,
                reasoningEffort.isBlank() ? "unset" : reasoningEffort, ttl);
    }

    /** The key this cache would use, exposed so callers can log it without rebuilding it. */
    public String keyFor(String rawLogs, String knownService) {
        return AnalysisCacheKey.build(modelName, reasoningEffort, rawLogs, knownService);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** @return the cached analysis, or empty on a miss, a disabled cache, or any Redis fault */
    public Optional<LogAnalysis> get(String key) {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(MAPPER.readValue(json, LogAnalysis.class));
        } catch (Exception e) {
            // Includes both "Redis is down" and "the stored value will not parse". Neither is
            // worth failing a diagnosis over, and both mean the same thing to the caller.
            log.warn("Analysis cache read failed for {}, treating as a miss: {}", key, e.toString());
            return Optional.empty();
        }
    }

    public void put(String key, LogAnalysis analysis) {
        if (!enabled) {
            return;
        }
        try {
            redis.opsForValue().set(key, MAPPER.writeValueAsString(analysis), ttl);
        } catch (Exception e) {
            log.warn("Analysis cache write failed for {}, continuing uncached: {}", key, e.toString());
        }
    }
}
