package com.shivansh.incidentresponder.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Closed vocabulary of failure classes the Analyzer may report.
 * <p>
 * This was a free-text {@code String} until the baseline caught it drifting: the same
 * input returned {@code RedisConnectionLoss} on one run and {@code RedisConnectionFailure}
 * on the next. Both are reasonable answers, and that is the problem - week 2 matches past
 * incidents on this field and week 3 hashes it into a Redis cache key, so an identifier
 * that moves breaks both regardless of which spelling is "right".
 * <p>
 * The fix is structural rather than prompt-based. {@link Severity} has been an enum from
 * the start and has never drifted across any baseline run; {@code errorType} was prose and
 * drifted twice. LangChain4j derives the format instructions from the return type, so the
 * constants below are sent to the model as the permitted set.
 * <p>
 * <b>On the two naming conventions.</b> LangChain4j builds the schema from
 * {@code getEnumConstants()} and {@code name()}, so the model is shown
 * {@code CONNECTION_POOL_EXHAUSTED}. The API contract, however, predates this change and
 * serialises PascalCase - the brief's example JSON and the controller test both assume it.
 * {@link #jsonValue()} keeps the wire format, and {@link #fromModel(String)} accepts either
 * convention, so the mismatch never becomes a parse failure.
 * <p>
 * Sized deliberately small. {@link #OTHER} is a measurement, not a failure: a high OTHER
 * rate across the baseline is the evidence that the vocabulary needs another constant.
 * Grow it from that evidence rather than by guessing.
 */
public enum ErrorType {

    /** Callers are queuing for a database connection that never becomes available. */
    CONNECTION_POOL_EXHAUSTED("ConnectionPoolExhausted"),

    /** JVM heap exhausted - OutOfMemoryError, or the GC thrashing that precedes it. */
    OUT_OF_MEMORY("OutOfMemory"),

    /** No space left on device: writes, spooling or the database failing to extend files. */
    DISK_SPACE_EXHAUSTED("DiskSpaceExhausted"),

    /** Worker threads all busy or blocked, so new work is rejected or queued indefinitely. */
    THREAD_POOL_EXHAUSTED("ThreadPoolExhausted"),

    /** Two or more threads each holding a lock the other needs. Nothing progresses. */
    THREAD_DEADLOCK("ThreadDeadlock"),

    /** A dependency accepted the request and did not answer in time - 504, read timeout. */
    UPSTREAM_TIMEOUT("UpstreamTimeout"),

    /** A dependency refused or could not be reached at all - connection refused, 503, DNS. */
    UPSTREAM_UNAVAILABLE("UpstreamUnavailable"),

    /** The database itself is down, unreachable, or rejecting connections. */
    DATABASE_UNAVAILABLE("DatabaseUnavailable"),

    /** Queries degraded rather than failed - missing index, plan regression, table growth. */
    SLOW_QUERY("SlowQuery"),

    /** Cache lost, flushed or unreachable, pushing load onto the origin. */
    CACHE_UNAVAILABLE("CacheUnavailable"),

    /** A certificate or signing key passed its validity window. */
    EXPIRED_CERTIFICATE("ExpiredCertificate"),

    /** Wrong, missing or unparseable configuration - env vars, properties, feature flags. */
    CONFIGURATION_ERROR("ConfigurationError"),

    /** Payload could not be read or written - schema mismatch, malformed JSON, bad encoding. */
    SERIALIZATION_FAILURE("SerializationFailure"),

    /** A quota or throttle was hit - 429, token budget, connection limit. */
    RATE_LIMITED("RateLimited"),

    /** The input was not application logs at all. */
    NOT_A_LOG_FILE("NotALogFile"),

    /**
     * A real failure that none of the above describes. Deliberately the last resort: every
     * OTHER is a hint that the vocabulary is missing a constant, so they are worth counting.
     */
    OTHER("Other");

    private static final Logger log = LoggerFactory.getLogger(ErrorType.class);

    /**
     * Keyed on the normalised form, so {@code CONNECTION_POOL_EXHAUSTED},
     * {@code ConnectionPoolExhausted} and {@code connection-pool-exhausted} all collapse to
     * one entry. Both spellings of every constant are registered; where they normalise
     * identically the second put is a harmless no-op.
     */
    private static final Map<String, ErrorType> BY_NORMALISED_FORM = new HashMap<>();

    static {
        for (ErrorType type : values()) {
            BY_NORMALISED_FORM.put(normalise(type.name()), type);
            BY_NORMALISED_FORM.put(normalise(type.jsonValue), type);
        }
    }

    private final String jsonValue;

    ErrorType(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    /** The wire format. {@code @JsonValue} makes Jackson serialise this instead of {@code name()}. */
    @JsonValue
    public String jsonValue() {
        return jsonValue;
    }

    /**
     * Lenient parse of whatever the model actually returned.
     * <p>
     * This is deserialisation robustness, not a synonym table: it ignores case and
     * punctuation so the two naming conventions in play both resolve, but it will never map
     * one failure class onto a different one. {@code RedisConnectionFailure} does not become
     * {@code CACHE_UNAVAILABLE} here - it becomes {@link #OTHER}, and gets logged, which is
     * how we find out the vocabulary needs work.
     *
     * @return the matching constant, or {@link #OTHER} for anything unrecognised. Never null,
     *         so a null field means the model omitted it entirely - a different problem,
     *         handled by the caller.
     */
    @JsonCreator
    public static ErrorType fromModel(String raw) {
        if (raw == null || raw.isBlank()) {
            return OTHER;
        }
        ErrorType matched = BY_NORMALISED_FORM.get(normalise(raw));
        if (matched == null) {
            log.warn("Model returned errorType '{}', which is outside the vocabulary - recording as OTHER", raw);
            return OTHER;
        }
        return matched;
    }

    /** Case- and punctuation-insensitive form used for matching only. */
    private static String normalise(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
