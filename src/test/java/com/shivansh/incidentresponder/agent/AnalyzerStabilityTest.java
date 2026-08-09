package com.shivansh.incidentresponder.agent;

import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.LogAnalysis;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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

    /** Same TPM reasoning as the baseline runner: three calls in a window stays inside 12k. */
    private static final Duration PACING = Duration.ofSeconds(25);

    @Autowired
    private AnalyzerService analyzerService;

    @Value("${langchain4j.open-ai.chat-model.api-key:}")
    private String apiKey;

    @Test
    void returnsTheSameErrorTypeForTheSameInput() {
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "No GROQ_API_KEY resolved from .env or the environment - skipping the stability run");

        String logs = read(SAMPLE);
        List<ErrorType> answers = new ArrayList<>();

        System.out.println();
        System.out.println("=================================================================");
        System.out.printf(" ERRORTYPE STABILITY  -  %s x%d%n", SAMPLE, RUNS);
        System.out.println("=================================================================");

        for (int run = 1; run <= RUNS; run++) {
            if (run > 1) {
                pause();
            }
            LogAnalysis analysis = analyzerService.analyze(logs, null);
            answers.add(analysis.errorType());
            System.out.printf("  run %d  errorType=%-22s severity=%-8s confidence=%.2f%n",
                    run, analysis.errorType(), analysis.severity(), analysis.confidence());
        }

        Set<ErrorType> distinct = new LinkedHashSet<>(answers);
        System.out.println("-----------------------------------------------------------------");
        System.out.printf("  distinct values: %d  %s%n", distinct.size(), distinct);
        if (distinct.size() == 1 && distinct.contains(ErrorType.OTHER)) {
            // Stable but uninformative. Worth saying out loud - it is the signal that the
            // vocabulary is missing a constant, not that the enum failed.
            System.out.println("  STABLE, but every run landed on OTHER - the vocabulary is missing a value");
        }
        System.out.println("=================================================================");
        System.out.println();

        assertThat(distinct)
                .as("errorType must be identical across %d runs of the same input", RUNS)
                .hasSize(1);
    }

    private void pause() {
        try {
            Thread.sleep(PACING.toMillis());
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
