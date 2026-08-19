package com.shivansh.incidentresponder.agent;

import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.service.AnalyzerService;
import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Baseline probe: runs all six sample logs through the real Analyzer Agent and prints a
 * side-by-side comparison of what came back versus what should have.
 * <p>
 * <b>This makes six real, billable Groq calls.</b> It is tagged {@code llm} and surefire is
 * configured to exclude that tag, so {@code mvn test} never runs it. Run it by hand from the
 * IDE (right-click the class, Run) when you want a fresh baseline.
 * <p>
 * It deliberately <b>asserts nothing</b>. A failed assertion would abort at the first bad
 * sample and hide the other five - and the whole point is to see all six failure modes at
 * once. The only outcome is the report on stdout. Turning any of this into a hard assertion
 * is a decision for after the prompt is tuned, not before.
 * <p>
 * Every sample runs in inference mode ({@code serviceName = null}). Pass-through mode is
 * deterministic by construction - {@code AnalyzerService} copies the caller's name in
 * regardless of the model - and is already covered by {@code AnalyzerServiceTest}. Inference
 * mode is where the agent can actually be wrong.
 */
@Tag("llm")
@SpringBootTest
// Same trick as IncidentResponderApplication.main: spring-dotenv 5.x no longer
// self-registers, so .env is invisible to a test context unless the initializer is
// declared. Without this line GROQ_API_KEY is empty and every call 401s.
@ContextConfiguration(initializers = DotenvApplicationInitializer.class)
class AnalyzerBaselineTest {

    /**
     * What a correct diagnosis looks like for each sample. Kept here rather than as comments
     * inside the {@code .log} files on purpose: anything in the file goes into the prompt, so
     * an "expected:" comment would hand the model the answer and the baseline would be a lie.
     *
     * @param file                 resource name under {@code src/test/resources/logs/}
     * @param expectedErrorType    exact match. This was a set of accepted spellings while
     *                             errorType was free text; the closed {@link ErrorType} enum
     *                             makes that whole scoring problem disappear
     * @param acceptedServices     the service the failure ORIGINATED in, which is not always the
     *                             service whose tag is on the log lines. A set, because in a
     *                             sample built to test something else the origin can be
     *                             legitimately arguable and should not manufacture a failure.
     * @param acceptedSeverities   more than one call is often defensible
     * @param acceptedFirstOccurrences ISO-8601 UTC, compared to SECOND precision because the log
     *                             lines carry milliseconds; several are listed where the start
     *                             of the failure is genuinely arguable
     * @param trap                 what this sample is designed to expose
     */
    private record Sample(
            String file,
            ErrorType expectedErrorType,
            Set<String> acceptedServices,
            Set<Severity> acceptedSeverities,
            Set<String> acceptedFirstOccurrences,
            String trap
    ) {
    }

    private static final List<Sample> SAMPLES = List.of(
            new Sample(
                    "connection-pool-exhaustion.log",
                    ErrorType.CONNECTION_POOL_EXHAUSTED,
                    Set.of("payment-service"),
                    Set.of(Severity.CRITICAL, Severity.HIGH),
                    Set.of("2026-08-05T02:12:15Z", "2026-08-05T02:12:58Z", "2026-08-05T02:14:33Z"),
                    "Baseline - the one case already known to work. Should pass cleanly. "
                            + "firstOccurrence is mildly arguable: pool trouble is visible from the slow "
                            + "acquire at 02:12:15, the first hard timeout is at 02:14:33."),

            new Sample(
                    "out-of-memory.log",
                    ErrorType.OUT_OF_MEMORY,
                    Set.of("order-service"),
                    Set.of(Severity.CRITICAL),
                    Set.of("2026-08-06T03:41:12Z", "2026-08-06T03:42:20Z", "2026-08-06T03:42:21Z"),
                    "The failure starts with GC thrashing at 03:41:12, five minutes BEFORE the "
                            + "OutOfMemoryError at 03:46:30. Reporting 03:46:30 means the model picked the "
                            + "loudest line, not the earliest - which is what the prompt forbids."),

            new Sample(
                    "downstream-timeout.log",
                    ErrorType.UPSTREAM_TIMEOUT,
                    Set.of("inventory-service"),
                    Set.of(Severity.CRITICAL, Severity.HIGH),
                    Set.of("2026-08-07T14:03:40Z", "2026-08-07T14:04:02Z", "2026-08-07T14:05:18Z"),
                    "Every single line is tagged [checkout-service], but checkout is the victim. "
                            + "The prompt's own rule - 'name the dependency that failed first' - makes "
                            + "inventory-service the answer. Expect this one to break."),

            new Sample(
                    "disk-full.log",
                    ErrorType.DISK_SPACE_EXHAUSTED,
                    Set.of("media-service"),
                    Set.of(Severity.CRITICAL),
                    Set.of("2026-08-07T16:17:29Z", "2026-08-07T16:21:38Z",
                            "2026-08-07T16:24:19Z", "2026-08-07T16:24:52Z"),
                    "Timestamps are +05:30, not Z, so firstOccurrence has to be converted to UTC "
                            + "(21:47:29+05:30 is 16:17:29Z). Severity is the other trap: payloads are "
                            + "discarded and unrecoverable, so this is data loss - CRITICAL, not HIGH."),

            new Sample(
                    "auth-failure-spike.log",
                    ErrorType.EXPIRED_CERTIFICATE,
                    Set.of("auth-service"),
                    Set.of(Severity.CRITICAL),
                    Set.of("2026-08-08T00:00:07Z", "2026-08-08T00:01:00Z"),
                    "Three traps at once. (1) JSON-structured logs, every line tagged api-gateway - "
                            + "but the gateway is the victim, auth-service is still signing with the dead "
                            + "key. (2) The cause appears in 2 of 28 lines, buried in a 401 flood. "
                            + "(3) errorType must name the cert expiry; 'AuthenticationFailure' or "
                            + "'UnauthorizedSpike' is the symptom and counts as a miss."),

            new Sample(
                    "thread-deadlock.log",
                    ErrorType.THREAD_DEADLOCK,
                    Set.of("pricing-service"),
                    Set.of(Severity.CRITICAL, Severity.HIGH),
                    Set.of("2026-08-06T11:22:19Z", "2026-08-06T11:22:51Z",
                            "2026-08-06T11:24:02Z", "2026-08-06T11:24:33Z"),
                    "The deadlock dump is a multi-line block with no timestamp of its own - it hangs "
                            + "off the 11:24:33 line. Watch the verbatim column: multi-line evidence is "
                            + "where models start paraphrasing."),

            new Sample(
                    "slow-query-degradation.log",
                    ErrorType.SLOW_QUERY,
                    Set.of("catalog-service"),
                    Set.of(Severity.MEDIUM),
                    Set.of("2026-08-04T09:18:52Z", "2026-08-04T09:11:47Z", "2026-08-04T09:21:19Z"),
                    "Severity floor test. p99 is up 10x and 2.1% of calls hit the caller's timeout, "
                            + "but every retry succeeds, the error rate is 0.00% and nothing is lost. "
                            + "That is MEDIUM by the prompt's own definition. Anything higher means "
                            + "severity is tracking how alarming the logs read, not blast radius."),

            new Sample(
                    "cache-miss-spike.log",
                    ErrorType.CACHE_UNAVAILABLE,
                    // Deliberately lenient: the restart happened in Redis, the log is profile-service
                    // coping with it. This sample exists to test severity, not origin attribution.
                    Set.of("profile-service", "redis", "redis-cache"),
                    Set.of(Severity.LOW),
                    Set.of("2026-08-03T15:42:47Z", "2026-08-03T15:43:04Z"),
                    "The other severity floor, plus a trap the prompt already names: there is an "
                            + "ERROR line (Redis connection lost) at the top. The prompt says an "
                            + "ERROR-level line on its own does not make an incident HIGH. Everything "
                            + "keeps serving, p99 stays under SLO, zero requests fail and the hit rate "
                            + "recovers to 93.8% - so this is LOW.")
    );

    /**
     * Gap between samples.
     * <p>
     * <b>Was 25 seconds, sized for a 12,000 token-per-minute ceiling and a 2,500-3,400 token
     * sample. Both of those numbers died with llama-3.3-70b-versatile.</b> On
     * {@code openai/gpt-oss-120b} the per-minute ceiling is <b>8,000</b>, and one measured
     * call on {@code connection-pool-exhaustion.log} costs <b>5,121 tokens</b> - input 4,109,
     * output 1,012. The input is the 9,141-character system prompt plus the log dump and is
     * not reducible by settings; the output grew because gpt-oss is a reasoning model and its
     * reasoning is billed as completion tokens.
     * <p>
     * At 5,121 tokens a call, <b>two calls in any 60-second window is 10,242 - over the
     * ceiling</b>. So the spacing has to exceed 60 seconds outright, which is a different
     * regime from the old "three per window" arithmetic rather than a tweak to it. 65 seconds
     * guarantees at most one call per window.
     * <p>
     * A full eight-sample run now takes about nine minutes, up from three.
     */
    private static final Duration PACING = Duration.ofSeconds(65);

    /** A full window's wait, so a retry starts from a clean budget rather than a nearly full one. */
    private static final Duration RATE_LIMIT_BACKOFF = Duration.ofSeconds(65);

    @Autowired
    private AnalyzerService analyzerService;

    @Value("${langchain4j.open-ai.chat-model.api-key:}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name:unknown}")
    private String modelName;

    @Test
    void printsABaselineForEverySample() {
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "No GROQ_API_KEY resolved from .env or the environment - skipping the baseline run");

        out("");
        out("==================================================================================");
        out(" ANALYZER BASELINE  -  model %s, %d samples, inference mode (no serviceName)", modelName, SAMPLES.size());
        out(" Started %s", Instant.now());
        out("==================================================================================");

        out(" Pacing %ds between samples to stay inside the Groq free-tier budget.", PACING.toSeconds());

        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < SAMPLES.size(); i++) {
            if (i > 0) {
                pause(PACING, "pacing before sample " + (i + 1));
            }
            Sample sample = SAMPLES.get(i);
            Outcome outcome = run(sample);
            outcomes.add(outcome);
            report(i + 1, SAMPLES.size(), outcome);
        }
        matrix(outcomes);
    }

    /**
     * One sample, one call, retried once if the free tier pushes back.
     * <p>
     * Other failures are captured rather than thrown, so a single bad sample does not cost
     * the rest of the run.
     */
    private Outcome run(Sample sample) {
        String logs = read(sample.file());
        long start = System.nanoTime();
        try {
            return new Outcome(sample, logs, analyzerService.analyze(logs, null), null, elapsedMillis(start));
        } catch (RuntimeException first) {
            if (!isRateLimit(first)) {
                return new Outcome(sample, logs, null, describe(first), elapsedMillis(start));
            }
            out("       rate limited, backing off %ds and retrying once", RATE_LIMIT_BACKOFF.toSeconds());
            pause(RATE_LIMIT_BACKOFF, "rate-limit backoff");
            try {
                return new Outcome(sample, logs, analyzerService.analyze(logs, null), null, elapsedMillis(start));
            } catch (RuntimeException second) {
                return new Outcome(sample, logs, null, describe(second), elapsedMillis(start));
            }
        }
    }

    /** Groq surfaces a token-per-minute breach as an HTTP 429 wrapped by AnalysisFailedException. */
    private static boolean isRateLimit(Throwable t) {
        for (Throwable cause = t; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.contains("429")
                    || message.toLowerCase(Locale.ROOT).contains("rate limit")
                    || message.toLowerCase(Locale.ROOT).contains("rate_limit"))) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static String describe(Throwable t) {
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static void pause(Duration duration, String why) {
        out("       waiting %ds (%s)", duration.toSeconds(), why);
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Baseline run interrupted while " + why, e);
        }
    }

    private record Outcome(Sample sample, String logs, LogAnalysis analysis, String failure, long millis) {

        boolean errorTypeOk() {
            return analysis != null && sample.expectedErrorType() == analysis.errorType();
        }

        boolean serviceOk() {
            return analysis != null && sample.acceptedServices().stream()
                    .anyMatch(accepted -> accepted.equalsIgnoreCase(analysis.affectedService()));
        }

        boolean severityOk() {
            return analysis != null && sample.acceptedSeverities().contains(analysis.severity());
        }

        /**
         * Compared to second precision. The log lines carry milliseconds and the model copies
         * them through, so 02:12:15.400Z is the right answer to an expectation written as
         * 02:12:15Z. Matching on the exact Instant scored four correct answers as failures.
         */
        boolean firstOccurrenceOk() {
            if (analysis == null || analysis.firstOccurrence() == null) {
                return false;
            }
            Instant actual = analysis.firstOccurrence().truncatedTo(ChronoUnit.SECONDS);
            return sample.acceptedFirstOccurrences().stream()
                    .map(Instant::parse)
                    .map(accepted -> accepted.truncatedTo(ChronoUnit.SECONDS))
                    .anyMatch(actual::equals);
        }

        /**
         * The prompt demands evidence copied character for character. Whitespace is collapsed
         * before comparing, because that is formatting noise rather than paraphrasing.
         */
        boolean isVerbatim(String evidenceLine) {
            return collapse(logs).contains(collapse(evidenceLine));
        }

        long verbatimCount() {
            return analysis == null ? 0
                    : analysis.keyEvidence().stream().filter(this::isVerbatim).count();
        }

        int evidenceCount() {
            return analysis == null ? 0 : analysis.keyEvidence().size();
        }
    }

    private static void report(int index, int total, Outcome outcome) {
        Sample sample = outcome.sample();
        out("");
        out("----------------------------------------------------------------------------------");
        out(" [%d/%d] %s   (%,d ms)", index, total, sample.file(), outcome.millis());
        out(" Designed to expose: %s", wrap(sample.trap(), 78, "                     "));
        out("----------------------------------------------------------------------------------");

        if (outcome.analysis() == null) {
            out("  CALL FAILED  %s", outcome.failure());
            return;
        }

        LogAnalysis a = outcome.analysis();
        out("  %-4s errorType        %s", mark(outcome.errorTypeOk()), a.errorType());
        if (!outcome.errorTypeOk()) {
            out("            expected         %s", sample.expectedErrorType());
        }
        out("  %-4s affectedService  %s", mark(outcome.serviceOk()), a.affectedService());
        if (!outcome.serviceOk()) {
            out("            expected one of  %s", String.join(", ", sample.acceptedServices()));
        }
        out("  %-4s severity         %s", mark(outcome.severityOk()), a.severity());
        if (!outcome.severityOk()) {
            out("            expected one of  %s", sample.acceptedSeverities());
        }
        out("  %-4s firstOccurrence  %s", mark(outcome.firstOccurrenceOk()), a.firstOccurrence());
        if (!outcome.firstOccurrenceOk()) {
            out("            expected one of  %s", String.join(", ", sample.acceptedFirstOccurrences()));
        }
        out("       confidence       %.2f", a.confidence());

        out("       keyEvidence      %d line(s), %d verbatim", outcome.evidenceCount(), outcome.verbatimCount());
        List<String> evidence = a.keyEvidence();
        for (int i = 0; i < evidence.size(); i++) {
            String line = evidence.get(i);
            boolean verbatim = outcome.isVerbatim(line);
            out("  %-4s   [%d] %s", mark(verbatim), i + 1, truncate(line, 200));
            if (!verbatim) {
                out("            ^ not found in the source log - paraphrased or invented");
            }
        }
    }

    /** The side-by-side view: one row per sample, so the failure pattern is visible at a glance. */
    private static void matrix(List<Outcome> outcomes) {
        out("");
        out("==================================================================================");
        out(" COMPARISON");
        out("==================================================================================");
        out(" %-28s %-6s %-8s %-9s %-9s %-9s %-6s %6s",
                "SAMPLE", "TYPE", "SERVICE", "SEVERITY", "FIRSTOCC", "VERBATIM", "CONF", "MS");
        out(" %s", "-".repeat(82));
        for (Outcome o : outcomes) {
            if (o.analysis() == null) {
                out(" %-28s %s", o.sample().file().replace(".log", ""), "CALL FAILED - " + o.failure());
                continue;
            }
            out(" %-28s %-6s %-8s %-9s %-9s %-9s %-6.2f %6d",
                    o.sample().file().replace(".log", ""),
                    mark(o.errorTypeOk()),
                    mark(o.serviceOk()),
                    mark(o.severityOk()),
                    mark(o.firstOccurrenceOk()),
                    o.verbatimCount() + "/" + o.evidenceCount(),
                    o.analysis().confidence(),
                    o.millis());
        }
        out(" %s", "-".repeat(82));

        long passed = outcomes.stream()
                .filter(o -> o.errorTypeOk() && o.serviceOk() && o.severityOk() && o.firstOccurrenceOk())
                .count();
        out(" %d of %d samples correct on all four fields.", passed, outcomes.size());
        out(" Nothing here is asserted - this is a baseline, not a gate.");
        out("==================================================================================");
        out("");
    }

    private String read(String file) {
        String path = "/logs/" + file;
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing log sample on the classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }

    private static long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private static String mark(boolean ok) {
        return ok ? "ok" : "FAIL";
    }

    /** Collapses whitespace runs, so indentation in a stack trace does not fail a verbatim check. */
    private static String collapse(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + " ...";
    }

    /** Keeps the long trap descriptions inside the console width. */
    private static String wrap(String text, int width, String indent) {
        StringBuilder wrapped = new StringBuilder();
        int lineLength = 0;
        for (String word : text.split(" ")) {
            if (lineLength + word.length() > width) {
                wrapped.append(System.lineSeparator()).append(indent);
                lineLength = 0;
            }
            wrapped.append(word).append(' ');
            lineLength += word.length() + 1;
        }
        return wrapped.toString().stripTrailing();
    }

    private static void out(String format, Object... args) {
        System.out.println(args.length == 0 ? format : String.format(format, args));
    }
}
