package com.shivansh.incidentresponder.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.shivansh.incidentresponder.model.Incident;
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
import java.util.Locale;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reports, does not assert. Answers the one question the cache could not: <b>does a
 * <em>cold</em> incident keep its resolution now?</b>
 * <p>
 * {@code LivePipelineProbe} showed the cache rescuing a <em>replay</em> — the Analyzer served
 * free, leaving the whole per-minute bucket for the Resolver. It could not show anything about
 * a first diagnosis of new logs, which is what a real incident is and where both calls are paid
 * back to back.
 * <p>
 * So the cache is <b>deliberately disabled here</b>. That guarantees the burst rather than
 * hoping for it: the Analyzer spends its ~5,000 tokens, the Resolver asks for ~3,600 against an
 * 8,000 ceiling, and gets refused. The question is whether
 * {@code ResolverService}'s rate-limit retry then rescues it.
 * <p>
 * Expected shape of a pass: rate limited <b>yes</b>, retry fired <b>yes</b>, resolution
 * <b>present</b>, and roughly 20-25 seconds wall clock — the Analyzer, a refusal, the 15 second
 * wait, then a successful Resolver call.
 * <p>
 * Costs about 8,700 tokens: one Analyzer call plus one Resolver call, the refused attempt being
 * free. Writes one real incident to Atlas; the id is printed.
 * <pre>
 * mvn test -Dtest=ResolverRateLimitProbe -Dsurefire.excludedGroups= -DfailIfNoSpecifiedTests=false
 * </pre>
 */
@Tag("llm")
@SpringBootTest(properties = "incident.cache.enabled=false")
@ContextConfiguration(initializers = DotenvApplicationInitializer.class)
class ResolverRateLimitProbe {

    /**
     * Defaults to the largest measured sample on purpose. The deficit that has to be waited out
     * is {@code analyzer + resolver - 8000}, and the Analyzer half scales with the log dump - so
     * a small sample produces a small deficit that LangChain4j's own 1.25s of retrying already
     * covers, and this probe would report a rescue it had nothing to do with. Measured: this
     * file costs 5,121 Analyzer tokens against thread-deadlock's ~4,540.
     */
    private static final String SAMPLE = System.getProperty(
            "probe.sample", "src/test/resources/logs/connection-pool-exhaustion.log");

    @Autowired
    private IncidentService incidentService;

    @Value("${langchain4j.open-ai.chat-model.api-key:}")
    private String apiKey;

    @Test
    void doesAColdIncidentKeepItsResolution() throws IOException {
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "No GROQ_API_KEY resolved from .env - skipping the rate-limit probe");

        String logs = Files.readString(Path.of(SAMPLE), StandardCharsets.UTF_8);

        ListAppender<ILoggingEvent> captured = attach();

        System.out.println();
        System.out.println("============ resolver rate-limit retry probe ============");
        System.out.printf("  sample : %s (%,d chars)%n", SAMPLE, logs.length());
        System.out.println("  cache  : DISABLED, so both calls are paid and the burst is certain");
        System.out.println();

        long start = System.currentTimeMillis();
        Incident stored;
        try {
            stored = incidentService.analyzeAndRecord(logs, null);
        } catch (RuntimeException e) {
            System.out.printf("  PIPELINE FAILED  %s: %s%n", e.getClass().getSimpleName(), e.getMessage());
            System.out.println("=========================================================");
            detach(captured);
            return;
        }
        long ms = System.currentTimeMillis() - start;

        boolean rateLimited = anyMentioning(captured, "429", "rate limit", "rate_limit");
        // LangChain4j retries internally too, so "a 429 happened" and "our retry ran" are
        // different questions. Only the second sentence is ours.
        boolean lc4jRetried = anyMentioning(captured, "A retriable exception occurred");
        boolean ourRetryFired = anyMentioning(captured, "Waiting", "and retrying once");

        System.out.printf("  incident id  : %s%n", stored.id());
        System.out.printf("  took         : %,dms%n", ms);
        System.out.printf("  diagnosis    : %s on %s (%s)%n",
                stored.errorType(), stored.affectedService(), stored.severity());
        System.out.printf("  rate limited     : %s%n", rateLimited ? "YES - a 429 was returned" : "no");
        System.out.printf("  lc4j retried     : %s%n", lc4jRetried ? "YES (its own 1.25s policy)" : "no");
        System.out.printf("  OUR retry fired  : %s%n", ourRetryFired ? "YES - ResolverService waited" : "no");
        System.out.printf("  resolution   : %s%n",
                stored.resolution() == null ? "NULL - still lost" : "PRESENT - rescued");
        if (stored.resolution() != null) {
            System.out.printf("    rootCause      : %s%n", truncate(stored.resolution().rootCause()));
            System.out.printf("    decidingEvid.  : %s%n", truncate(stored.resolution().decidingEvidence()));
            System.out.printf("    drewOn         : %s%n", stored.resolution().similarIncidents());
        }
        System.out.println("=========================================================");
        System.out.println();

        detach(captured);
    }

    private static boolean anyMentioning(ListAppender<ILoggingEvent> captured, String... fragments) {
        return captured.list.stream().anyMatch(event -> {
            String text = (event.getFormattedMessage()
                    + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage()))
                    .toLowerCase(Locale.ROOT);
            for (String fragment : fragments) {
                if (text.contains(fragment.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        });
    }

    /**
     * Attached to the <b>root</b> logger, not to {@code ResolverService}.
     * <p>
     * The first version of this probe watched only this application's logger and reported
     * "rate limited: no" on a run whose log plainly contained a 429 - because LangChain4j
     * reports its own retries under {@code dev.langchain4j.internal.RetryUtils}. That is a
     * false negative in the measurement, not in the code under test, and it is exactly the
     * shape of mistake that gets a fix marked verified when it never ran.
     */
    private static ListAppender<ILoggingEvent> attach() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.setLevel(Level.INFO);
        root.addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 150 ? value : value.substring(0, 147) + "...";
    }
}
