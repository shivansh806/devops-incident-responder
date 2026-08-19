package com.shivansh.incidentresponder.service;

import com.shivansh.incidentresponder.agent.AnalyzerAgent;
import com.shivansh.incidentresponder.cache.AnalysisCache;
import com.shivansh.incidentresponder.agent.AnalyzerOutput;
import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the Analyzer Agent and turns its raw reply into a trustworthy {@link LogAnalysis}.
 * <p>
 * The mapping below is the point of this class. Everything the model returns is untrusted:
 * fields can be null, enums can be missing, confidence can sit outside 0-1, and timestamps
 * arrive as prose. Normalising here means callers - the REST controller today, a Kafka
 * consumer in week 3 - never have to defend against a badly behaved model.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyzerService {

    /**
     * Upper bound on a single request. A full 10,000-line dump would blow the context
     * window and cost far more than it is worth. Rejecting is deliberate: silently
     * truncating would drop the earliest lines and corrupt firstOccurrence. Log
     * summarisation before the model call is a week 3 concern.
     */
    private static final int MAX_LOG_CHARS = 50_000;

    /** Values models emit instead of leaving a field out. All mean "no value". */
    private static final Set<String> NULL_LIKE = Set.of("null", "none", "unknown", "n/a", "na", "-");

    private static final String UNKNOWN_SERVICE = "unknown";

    private final AnalyzerAgent analyzerAgent;

    /**
     * Caching sits here rather than in {@code IncidentService} because this is the boundary
     * the expensive, deterministic-enough call lives behind. The Resolver is deliberately not
     * cached: its input includes retrieved precedent, which changes as the corpus grows, so a
     * hit would be answering a question that is no longer the one being asked.
     */
    private final AnalysisCache analysisCache;

    /** Analyses logs whose originating service is unknown, leaving the model to identify it. */
    public LogAnalysis analyze(String rawLogs) {
        return analyze(rawLogs, null);
    }

    /**
     * @param knownService the originating service if the caller already knows it, else null.
     *                     When supplied it is authoritative - it is copied into the result
     *                     verbatim and the model's own answer for that field is discarded.
     * @throws IllegalArgumentException if the log dump is empty or over {@link #MAX_LOG_CHARS}
     * @throws AnalysisFailedException  if the agent call or its parsing fails
     */
    public LogAnalysis analyze(String rawLogs, String knownService) {
        if (rawLogs == null || rawLogs.isBlank()) {
            throw new IllegalArgumentException("'logs' must not be empty");
        }
        if (rawLogs.length() > MAX_LOG_CHARS) {
            throw new IllegalArgumentException(
                    "'logs' is %,d characters, the limit is %,d".formatted(rawLogs.length(), MAX_LOG_CHARS));
        }

        String service = isMissing(knownService) ? null : knownService.trim();
        log.info("Analyzing {} characters of logs (service={})",
                rawLogs.length(), service == null ? "to be inferred" : service);

        // The normalised service is what goes into the key, so a caller sending " payment "
        // and one sending "payment" share an entry rather than paying twice.
        String cacheKey = null;
        if (analysisCache.isEnabled()) {
            cacheKey = analysisCache.keyFor(rawLogs, service);
            Optional<LogAnalysis> cached = analysisCache.get(cacheKey);
            if (cached.isPresent()) {
                LogAnalysis hit = cached.get();
                // Logged at INFO and worth it: a hit is the difference between a free call and
                // 5,121 tokens, and "was this answer paid for?" is the first question anyone
                // reading a surprising diagnosis should be able to settle from the log.
                log.info("Analysis cache HIT {} - returning {} on {} without calling the model",
                        cacheKey, hit.errorType(), hit.affectedService());
                return hit;
            }
            log.info("Analysis cache MISS {}", cacheKey);
        }

        AnalyzerOutput output;
        try {
            output = service == null
                    ? analyzerAgent.analyze(rawLogs)
                    : analyzerAgent.analyzeForService(rawLogs, service);
        } catch (RuntimeException e) {
            log.error("Analyzer agent call failed", e);
            throw new AnalysisFailedException("Analyzer agent call failed: " + e.getMessage(), e);
        }
        if (output == null) {
            throw new AnalysisFailedException("Analyzer agent returned no result");
        }

        LogAnalysis analysis = toAnalysis(output, service);
        log.info("Diagnosis: errorType={} service={} severity={} confidence={}",
                analysis.errorType(), analysis.affectedService(), analysis.severity(), analysis.confidence());

        // Only a normalised, fully-formed analysis is stored - never the raw model output.
        // Whatever is cached is what a later caller receives, so it has to have been through
        // the same defences as an uncached answer.
        if (cacheKey != null) {
            analysisCache.put(cacheKey, analysis);
        }
        return analysis;
    }

    private LogAnalysis toAnalysis(AnalyzerOutput output, String knownService) {
        return new LogAnalysis(
                errorTypeOrDefault(output.errorType()),
                affectedService(output.affectedService(), knownService),
                severityOrDefault(output.severity()),
                parseFirstOccurrence(output.firstOccurrence()),
                cleanEvidence(output.keyEvidence()),
                clampConfidence(output.confidence()));
    }

    /**
     * A caller-supplied service name always wins. The prompt also tells the model to use it,
     * but a prompt is a request, not a guarantee - this is the guarantee. Only when the
     * caller does not know the service does the model's answer get used.
     */
    private static String affectedService(String fromModel, String knownService) {
        if (knownService != null) {
            if (!isMissing(fromModel) && !knownService.equalsIgnoreCase(fromModel.trim())) {
                log.debug("Model answered affectedService='{}', overridden by caller-supplied '{}'",
                        fromModel.trim(), knownService);
            }
            return knownService;
        }
        return orDefault(fromModel, UNKNOWN_SERVICE);
    }

    private static String orDefault(String value, String fallback) {
        return isMissing(value) ? fallback : value.trim();
    }

    /**
     * A null here means the model left the field out entirely. An unrecognised <em>value</em>
     * never reaches this point - {@link ErrorType#fromModel} has already turned it into
     * {@code OTHER} and logged what it was. Both end up as OTHER; the logs tell them apart.
     */
    private static ErrorType errorTypeOrDefault(ErrorType errorType) {
        if (errorType == null) {
            log.warn("Model omitted errorType, defaulting to OTHER");
            return ErrorType.OTHER;
        }
        return errorType;
    }

    private static Severity severityOrDefault(Severity severity) {
        if (severity == null) {
            // An unrecognised enum value parses to null. Defaulting beats failing the whole
            // request - MEDIUM avoids both false alarms and false calm.
            log.warn("Model omitted severity or returned an unrecognised value, defaulting to MEDIUM");
            return Severity.MEDIUM;
        }
        return severity;
    }

    /**
     * The prompt asks for ISO-8601 UTC, but models drift towards whatever format the logs
     * used, so a few shapes are accepted before giving up. A null result is a legitimate
     * answer - it means the logs carried no usable timestamp.
     */
    private static Instant parseFirstOccurrence(String value) {
        if (isMissing(value)) {
            return null;
        }
        String trimmed = value.trim();

        try {
            return Instant.parse(trimmed);                                   // 2026-08-05T02:14:33Z
        } catch (DateTimeParseException ignored) {
            // not an instant, try the next shape
        }
        try {
            return OffsetDateTime.parse(trimmed).toInstant();                // 2026-08-05T07:44:33+05:30
        } catch (DateTimeParseException ignored) {
            // not an offset date-time, try the next shape
        }
        try {
            return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC);   // 2026-08-05T02:14:33
        } catch (DateTimeParseException e) {
            log.warn("Model returned an unparseable firstOccurrence: '{}'", trimmed);
            return null;
        }
    }

    private static List<String> cleanEvidence(List<String> evidence) {
        if (evidence == null) {
            return List.of();
        }
        return evidence.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private static double clampConfidence(Double confidence) {
        if (confidence == null) {
            log.warn("Model omitted confidence, defaulting to 0.0");
            return 0.0;
        }
        // Math.clamp is new in Java 21: it pins a value into a range in one call.
        return Math.clamp(confidence, 0.0, 1.0);
    }

    private static boolean isMissing(String value) {
        return value == null
                || value.isBlank()
                || NULL_LIKE.contains(value.trim().toLowerCase(Locale.ROOT));
    }
}
