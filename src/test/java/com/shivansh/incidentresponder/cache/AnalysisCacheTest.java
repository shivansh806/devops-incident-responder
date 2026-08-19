package com.shivansh.incidentresponder.cache;

import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Offline. No Redis - the template is mocked, including its faults. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalysisCacheTest {

    private static final LogAnalysis ANALYSIS = new LogAnalysis(
            ErrorType.CACHE_UNAVAILABLE, "redis-cache", Severity.MEDIUM,
            Instant.parse("2026-08-19T10:23:45.123Z"),
            List.of("Connection to redis-cache:6379 lost"), 0.92);

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    private AnalysisCache cache(boolean enabled) {
        return new AnalysisCache(redis, enabled, Duration.ofDays(7), "openai/gpt-oss-120b", "medium");
    }

    @Test
    void roundTripsAnAnalysisWithoutLosingTheInstant() throws Exception {
        AnalysisCache cache = cache(true);
        String key = cache.keyFor("ERROR x", null);

        cache.put(key, ANALYSIS);

        org.mockito.ArgumentCaptor<String> stored = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(key), stored.capture(), eq(Duration.ofDays(7)));

        // The field that silently degrades to epoch seconds on a default-configured mapper.
        assertThat(stored.getValue()).contains("2026-08-19T10:23:45.123Z");

        when(valueOps.get(key)).thenReturn(stored.getValue());
        assertThat(cache.get(key)).contains(ANALYSIS);
    }

    @Test
    void missReturnsEmpty() {
        AnalysisCache cache = cache(true);
        when(valueOps.get(anyString())).thenReturn(null);

        assertThat(cache.get(cache.keyFor("ERROR x", null))).isEmpty();
    }

    /**
     * Redis being down must never fail a diagnosis. Both directions fail open: a read fault is
     * a miss, a write fault is a shrug.
     */
    @Test
    void failsOpenWhenRedisIsUnreachable() {
        AnalysisCache cache = cache(true);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("Connection refused"));

        assertThat(cache.get(cache.keyFor("ERROR x", null))).isEmpty();

        org.mockito.Mockito.doThrow(new RuntimeException("Connection refused"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        cache.put(cache.keyFor("ERROR x", null), ANALYSIS);   // must not throw
    }

    /** A corrupt or unparseable stored value is a miss, not an error. */
    @Test
    void treatsAnUnreadableStoredValueAsAMiss() {
        AnalysisCache cache = cache(true);
        when(valueOps.get(anyString())).thenReturn("{ this is not json");

        assertThat(cache.get(cache.keyFor("ERROR x", null))).isEmpty();
    }

    /**
     * The measurement guard. When disabled, nothing reaches Redis at all - not a lookup that
     * misses, no call. That is what lets a baseline or stability run be trusted.
     */
    @Test
    void whenDisabledItDoesNotTouchRedisAtAll() {
        AnalysisCache cache = cache(false);

        assertThat(cache.isEnabled()).isFalse();
        assertThat(cache.get("analysis:v1:whatever")).isEmpty();
        cache.put("analysis:v1:whatever", ANALYSIS);

        verify(valueOps, never()).get(anyString());
        verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void keyReflectsTheConfiguredModel() {
        AnalysisCache onGptOss = new AnalysisCache(redis, true, Duration.ofDays(7), "openai/gpt-oss-120b", "medium");
        AnalysisCache onSomethingElse = new AnalysisCache(redis, true, Duration.ofDays(7), "openai/gpt-oss-20b", "medium");

        assertThat(onGptOss.keyFor("ERROR x", null))
                .isNotEqualTo(onSomethingElse.keyFor("ERROR x", null));
    }
}
