package com.shivansh.incidentresponder.cache;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.service.IncidentService;
import com.shivansh.incidentresponder.service.ResolverService;
import com.shivansh.incidentresponder.service.AnalyzerService;
import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reports, does not assert. Settles one prediction with a live run.
 *
 * <h2>The prediction</h2>
 * {@code docs/ingestion.md} computes, from measured call sizes, that <b>a single event's two
 * calls cannot both fit in the per-minute bucket</b>: the Analyzer is 5,121 tokens and the
 * Resolver about 4,700, against a ceiling of 8,000. They run back to back inside
 * {@code IncidentService.analyzeAndRecord} with nothing between them, so the Resolver should
 * 429 - and because {@code ResolverService} absorbs its own failures, the symptom would be an
 * incident stored with a diagnosis and <b>no recommendation</b>, silently.
 * <p>
 * That was arithmetic, never observed. This runs it.
 *
 * <h2>Two calls, deliberately spaced</h2>
 * <b>Call 1</b> starts from a full bucket with the cache cold, so it is a clean test of the
 * burst. <b>Call 2</b> repeats the identical input after a refill pause, so the Analyzer is a
 * cache hit costing nothing - which should leave the entire bucket for the Resolver. If the
 * prediction holds, call 1 has no resolution and call 2 does, and that pair is the cache
 * earning its place rather than a claim that it does.
 * <p>
 * The pause matters: without it, call 2 would run against a bucket that call 1 had just
 * drained, and a failure would say nothing about the cache.
 *
 * <h2>Cost, and what it leaves behind</h2>
 * Roughly 14,500 tokens: ~9,800 for the uncached event and ~4,700 for the cached replay.
 * <b>It writes two real incidents to Atlas</b> - the ids are printed. They carry no embedding,
 * so they are not indexed and cannot turn up as retrieval precedent.
 * <p>
 * Tagged {@code llm}, so {@code mvn test} never runs it:
 * <pre>
 * mvn test -Dtest=LivePipelineProbe -Dsurefire.excludedGroups= -DfailIfNoSpecifiedTests=false
 * </pre>
 */
@Tag("llm")
@SpringBootTest(properties = "incident.cache.enabled=true")
@ContextConfiguration(initializers = DotenvApplicationInitializer.class)
class LivePipelineProbe {

    private static final String SAMPLE = "src/test/resources/logs/downstream-timeout.log";

    /** Long enough for the 8,000 token bucket to refill at ~133 tokens a second. */
    private static final long REFILL_PAUSE_MILLIS = 90_000;

    @Autowired
    private IncidentService incidentService;

    @Value("${langchain4j.open-ai.chat-model.api-key:}")
    private String apiKey;

    @Test
    void doesTheResolverRunOutOfBudgetInsideOneEvent() throws IOException, InterruptedException {
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "No GROQ_API_KEY resolved from .env - skipping the live pipeline probe");

        String logs = Files.readString(Path.of(SAMPLE), StandardCharsets.UTF_8);

        System.out.println();
        System.out.println("================== live pipeline probe ==================");
        System.out.printf("  sample : %s (%,d chars)%n", SAMPLE, logs.length());
        System.out.println("  predicted: call 1 stores a diagnosis with NO resolution (Resolver 429s)");
        System.out.println("             call 2 is a cache hit, so the Resolver gets the whole bucket");
        System.out.println();

        runOnce("CALL 1  cold cache, full bucket", logs);

        System.out.printf("%n  ... pausing %ds for the token bucket to refill ...%n%n",
                REFILL_PAUSE_MILLIS / 1000);
        Thread.sleep(REFILL_PAUSE_MILLIS);

        runOnce("CALL 2  same input, cache should hit", logs);

        System.out.println("=========================================================");
        System.out.println();
    }

    private void runOnce(String label, String logs) {
        ListAppender<ILoggingEvent> captured = attachTo(
                ResolverService.class, AnalyzerService.class, AnalysisCache.class);

        System.out.println("  " + label);
        long start = System.currentTimeMillis();
        Incident stored;
        try {
            // Null service: this sample's logs are tagged with the victim, so the origin is
            // inferred. Same shape a simulator event of this scenario would have.
            stored = incidentService.analyzeAndRecord(logs, null);
        } catch (RuntimeException e) {
            System.out.printf("    PIPELINE FAILED  %s: %s%n", e.getClass().getSimpleName(), e.getMessage());
            detach(captured);
            return;
        }
        long ms = System.currentTimeMillis() - start;

        boolean cacheHit = lineContaining(captured, "Analysis cache HIT") != null;
        String resolverFailure = lineContaining(captured, "Resolver agent call failed");
        boolean rateLimited = mentionsRateLimit(captured);

        System.out.printf("    incident id     : %s%n", stored.id());
        System.out.printf("    took            : %,dms%n", ms);
        System.out.printf("    analyzer        : %s%n", cacheHit ? "CACHE HIT (free)" : "called the model");
        System.out.printf("    diagnosis       : %s on %s (%s)%n",
                stored.errorType(), stored.affectedService(), stored.severity());
        System.out.printf("    resolution      : %s%n",
                stored.resolution() == null ? "NULL - no recommendation stored" : "present");
        if (stored.resolution() != null) {
            System.out.printf("      rootCause     : %s%n", truncate(stored.resolution().rootCause()));
            System.out.printf("      decidingEvid. : %s%n", truncate(stored.resolution().decidingEvidence()));
        }
        System.out.printf("    resolver failed : %s%n", resolverFailure == null ? "no" : "yes");
        System.out.printf("    rate limited    : %s%n", rateLimited ? "YES - a 429 was logged" : "no 429 seen");

        detach(captured);
    }

    private static boolean mentionsRateLimit(ListAppender<ILoggingEvent> captured) {
        return captured.list.stream().anyMatch(event -> {
            String text = event.getFormattedMessage()
                    + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
            String lower = text.toLowerCase(Locale.ROOT);
            return lower.contains("429") || lower.contains("rate limit") || lower.contains("rate_limit");
        });
    }

    private static String lineContaining(ListAppender<ILoggingEvent> captured, String fragment) {
        return captured.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(fragment))
                .findFirst()
                .orElse(null);
    }

    private static ListAppender<ILoggingEvent> attachTo(Class<?>... targets) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        for (Class<?> target : targets) {
            Logger logger = (Logger) LoggerFactory.getLogger(target);
            logger.setLevel(Level.INFO);
            logger.addAppender(appender);
        }
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        for (Class<?> target : List.of(ResolverService.class, AnalyzerService.class, AnalysisCache.class)) {
            ((Logger) LoggerFactory.getLogger(target)).detachAppender(appender);
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 140 ? value : value.substring(0, 137) + "...";
    }
}
