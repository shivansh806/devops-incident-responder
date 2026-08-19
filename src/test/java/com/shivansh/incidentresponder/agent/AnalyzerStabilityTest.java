package com.shivansh.incidentresponder.agent;

import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.service.AnalyzerService;
import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.junit.jupiter.api.Assumptions;
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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sends one sample through the Analyzer three times and checks {@code errorType} comes back
 * identical each time.
 * <p>
 * This is the acceptance test for the {@link ErrorType} enum, and it tests the property that
 * change was actually made for. While errorType was free text this sample returned
 * {@code RedisConnectionLoss} on one baseline run and {@code RedisConnectionFailure} on the
 * next - both defensible, which is exactly why prose was the wrong type. Week 2 matches past
 * incidents on this field and week 3 hashes it into a cache key; an identifier that moves
 * breaks both.
 * <p>
 * Unlike {@link AnalyzerBaselineTest} this one <b>does assert</b>. The baseline is
 * calibration against a model's judgement, where a failure can just be an off day. Stability
 * is a contract the type system now guarantees, so a failure here means the mechanism is
 * broken rather than the model being unlucky. It still makes three real Groq calls, so it
 * carries the {@code llm} tag and never runs in a normal build.
 * <p>
 * Three calls, roughly 10k tokens.
 */
@Tag("llm")
@SpringBootTest
@ContextConfiguration(initializers = DotenvApplicationInitializer.class)
class AnalyzerStabilityTest {

    /** The sample that actually drifted, so this measures the real case rather than a hopeful one. */
    private static final String SAMPLE = "cache-miss-spike.log";

    private static final int RUNS = 3;

    /**
     * Same TPM reasoning as the baseline runner, and the same correction: the ceiling is now
     * 8,000 tokens a minute and one call costs about 5,100, so a single call is all a
     * 60-second window will hold. Anything at or under 60 seconds puts two in a window and
     * 429s the second. See {@code AnalyzerBaselineTest.PACING} for the measurement.
     */
    private static final Duration PACING = Duration.ofSeconds(65);

    /** A full window's wait, so a retry starts from a clean per-minute budget. */
    private static final Duration RATE_LIMIT_BACKOFF = Duration.ofSeconds(65);

    @Autowired
    private AnalyzerService analyzerService;

    @Value("${langchain4j.open-ai.chat-model.api-key:}")
    private String apiKey;

    /**
     * The value {@code affectedService} has actually returned on this model, pinned so that a
     * change is a red test rather than a line nobody reads.
     * <p>
     * <b>This is what the model does, not what the baseline says it should.</b> They disagree.
     * {@code docs/baseline.md} expects {@code profile-service}, and recorded the risk of the
     * dropped Redis being promoted to an origin of its own as "cleared, not merely untested" -
     * a clearance measured on llama-3.3-70b-versatile, which Groq has retired. gpt-oss promotes
     * it, 3 of 3. Pinning the observed value is what makes the <em>next</em> change visible;
     * whether the value is correct is the baseline runner's question, and it is recorded as
     * open there.
     */
    private static final String EXPECTED_SERVICE = "redis-cache";

    /**
     * Severity is checked by membership rather than by stability, and the asymmetry is
     * deliberate.
     * <p>
     * {@code errorType} and {@code affectedService} have been observed identical on every run,
     * so "did it move?" is a sound question to fail on. Severity has not: across four
     * observations of this sample it returned MEDIUM three times and LOW once. Asserting
     * stability on a field measured to vary would produce a gate that goes red on an ordinary
     * day, and a gate that cries wolf gets ignored - which is the failure mode this change
     * exists to remove, not to introduce.
     * <p>
     * So the assertion is the one the evidence supports: every run must land inside the range
     * this sample has ever produced. HIGH or CRITICAL would be a real finding about severity
     * calibration and fails immediately. MEDIUM-versus-LOW is the known open question that
     * {@code docs/baseline.md} says not to tune until the sample set has more than one LOW case.
     */
    private static final Set<Severity> ACCEPTED_SEVERITIES = EnumSet.of(Severity.LOW, Severity.MEDIUM);

    @Test
    void returnsTheSameDiagnosisForTheSameInput() {
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "No GROQ_API_KEY resolved from .env or the environment - skipping the stability run");

        String logs = read(SAMPLE);
        List<ErrorType> answers = new ArrayList<>();
        List<String> services = new ArrayList<>();
        List<Severity> severities = new ArrayList<>();

        System.out.println();
        System.out.println("=================================================================");
        System.out.printf(" DIAGNOSIS STABILITY  -  %s x%d%n", SAMPLE, RUNS);
        System.out.println("=================================================================");

        for (int run = 1; run <= RUNS; run++) {
            if (run > 1) {
                pause(PACING);
            }
            LogAnalysis analysis = analyse(logs, answers, run);
            answers.add(analysis.errorType());
            services.add(analysis.affectedService());
            severities.add(analysis.severity());
            System.out.printf("  run %d  errorType=%-22s service=%-18s severity=%-8s confidence=%.2f%n",
                    run, analysis.errorType(), analysis.affectedService(),
                    analysis.severity(), analysis.confidence());
        }

        Set<ErrorType> distinctTypes = new LinkedHashSet<>(answers);
        Set<String> distinctServices = new LinkedHashSet<>(services);
        Set<Severity> distinctSeverities = new LinkedHashSet<>(severities);

        System.out.println("-----------------------------------------------------------------");
        System.out.printf("  errorType       distinct: %d  %s%n", distinctTypes.size(), distinctTypes);
        System.out.printf("  affectedService distinct: %d  %s%n", distinctServices.size(), distinctServices);
        System.out.printf("  severity        distinct: %d  %s%n", distinctSeverities.size(), distinctSeverities);
        if (distinctTypes.size() == 1 && distinctTypes.contains(ErrorType.OTHER)) {
            // Stable but uninformative. Worth saying out loud - it is the signal that the
            // vocabulary is missing a constant, not that the enum failed.
            System.out.println("  STABLE, but every run landed on OTHER - the vocabulary is missing a value");
        }
        if (distinctSeverities.size() > 1) {
            System.out.printf("  NOTE severity varied %s - expected on this sample, see ACCEPTED_SEVERITIES%n",
                    distinctSeverities);
        }
        System.out.println("=================================================================");
        System.out.println();

        // Asserted together rather than one at a time. A run costs 5,121 tokens and over two
        // minutes of pacing, so stopping at the first failure would hide the other two fields
        // and cost a whole further run to see them. Same reasoning as the baseline runner
        // reporting every sample instead of aborting - except these are mechanism claims, so
        // unlike the baseline they do assert.
        assertAll(
                () -> assertThat(distinctTypes)
                        .as("errorType must be identical across %d runs of the same input", RUNS)
                        .hasSize(1),
                () -> assertThat(distinctServices)
                        .as("affectedService must be identical across %d runs of the same input", RUNS)
                        .hasSize(1),
                () -> assertThat(distinctServices)
                        .as("affectedService moved - if this is a deliberate prompt or model change, "
                                + "update EXPECTED_SERVICE and record it in docs/baseline.md")
                        .containsExactly(EXPECTED_SERVICE),
                () -> assertThat(severities)
                        .as("severity outside the range this sample has ever produced - a real "
                                + "finding about calibration, not a flaky test")
                        .allMatch(ACCEPTED_SEVERITIES::contains));
    }

    /**
     * One call, retried once if the free tier pushes back.
     * <p>
     * A rate limit means the experiment could not be run, which is not the same as the
     * experiment failing. Running out of daily quota mid-run would otherwise surface as a
     * red test that looks exactly like errorType being unstable - and would discard the runs
     * already collected. So the partial result is printed and the test is ABORTED (reported
     * as skipped) rather than failed.
     */
    private LogAnalysis analyse(String logs, List<ErrorType> collectedSoFar, int run) {
        try {
            return analyzerService.analyze(logs, null);
        } catch (RuntimeException first) {
            if (!isRateLimit(first)) {
                throw first;
            }
            System.out.printf("  run %d  rate limited, backing off %ds and retrying once%n",
                    run, RATE_LIMIT_BACKOFF.toSeconds());
            pause(RATE_LIMIT_BACKOFF);
            try {
                return analyzerService.analyze(logs, null);
            } catch (RuntimeException second) {
                if (!isRateLimit(second)) {
                    throw second;
                }
                System.out.printf("  collected %d of %d runs before quota ran out: %s%n",
                        collectedSoFar.size(), RUNS, collectedSoFar);
                System.out.println("=================================================================");
                return Assumptions.abort(
                        "Groq quota exhausted after %d of %d runs - stability was not measured, not disproved. %s"
                                .formatted(collectedSoFar.size(), RUNS, second.getMessage()));
            }
        }
    }

    /** Both the per-minute and the per-day ceilings arrive as a 429. */
    private static boolean isRateLimit(Throwable t) {
        for (Throwable cause = t; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.contains("429")
                    || message.toLowerCase(Locale.ROOT).contains("rate limit")
                    || message.toLowerCase(Locale.ROOT).contains("rate_limit"))) {
                return true;
            }
        }
        return false;
    }

    private void pause(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Stability run interrupted", e);
        }
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
}
