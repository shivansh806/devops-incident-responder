package com.shivansh.incidentresponder.agent;

import com.shivansh.incidentresponder.embedding.SimilarIncident;
import com.shivansh.incidentresponder.embedding.SimilarIncidentSearch;
import com.shivansh.incidentresponder.model.AgentResolution;
import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.seed.IncidentSeeder;
import com.shivansh.incidentresponder.service.ResolverService;
import me.paulschwarz.springdotenv.spring.DotenvApplicationInitializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.context.ContextConfiguration;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Measures the Resolver Agent's judgement on one fixed case, repeatedly.
 * <p>
 * <b>This makes {@value #RUNS} real, billable Groq calls.</b> Tagged {@code llm}, which
 * surefire excludes, so {@code mvn test} never runs it. Run it deliberately:
 * <pre>
 *   mvn test -Dtest=ResolverBaselineTest -Dsurefire.excludedGroups= -DfailIfNoSpecifiedTests=false
 * </pre>
 *
 * <h2>Why it skips the Analyzer</h2>
 * The full pipeline costs an Analyzer call per measurement - a 23-line log dump plus a
 * 9,000-character system prompt - and that call is not what is being measured. Freezing the
 * diagnosis and calling only the Resolver drops the cost per run by most of an order of
 * magnitude, which is the difference between affording three runs and affording eight. That
 * matters more than it sounds: at the observed 1-in-3 failure rate, three clean runs happen
 * by chance {@code (2/3)^3} of the time, about 30%. Eight get it under 5%.
 *
 * <h2>Why the retrieved incidents are frozen too</h2>
 * Retrieval is deterministic, so calling Atlas here would add no variance - but it would add
 * a dependency, and worse, it would let the measurement move if the corpus ever changes. A
 * baseline is only comparable across weeks if its inputs cannot drift. The three candidates
 * below are loaded from the seed file and handed over with the similarity scores measured by
 * {@code RetrievalProbe}, so this runs with nothing but a Groq key.
 * <p>
 * The scores are the ones for the {@code subscription-service} case in
 * {@code docs/retrieval.md}'s successor measurement: INC-2331 highest at 0.9614, and
 * INC-2103 - listed first, being oldest - second at 0.9476. That disagreement between
 * chronological and similarity order is the whole reason this case was chosen.
 *
 * <h2>What it asserts</h2>
 * Nothing, deliberately, exactly like {@code AnalyzerBaselineTest}. An assertion would abort
 * the run partway and destroy the distribution, and the distribution is the entire output.
 * Confidence is recorded on every run because it is continuous: correct-or-blended is one
 * bit per call, while 0.60 / 0.70 / 0.85 on identical input says something about how settled
 * the judgement is, and buys more evidence from the same quota.
 */
@Tag("llm")
@SpringBootTest
@ContextConfiguration(initializers = DotenvApplicationInitializer.class)
class ResolverBaselineTest {

    static final int RUNS = 8;

    /**
     * Groq's free tier caps tokens per <em>minute</em> at 12,000, separately from the 100,000
     * per day. This prompt is about 3,500 tokens a call, so three calls back to back reach the
     * ceiling and everything after them 429s - the first attempt at this baseline lost four of
     * its eight runs that way.
     * <p>
     * 25 seconds keeps it under three calls per minute with room to spare. It makes a full
     * baseline take three and a half minutes, which is the correct trade: a refused call
     * measures nothing, and a run of nulls in the middle of a distribution is worse than
     * waiting.
     */
    private static final long PACING_MILLIS = 25_000;

    /**
     * The diagnosis, frozen. Approximates what the Analyzer produced on the live run: it
     * picked the acquisition-versus-execution line as {@code firstOccurrence}, which is both
     * the earliest sign of trouble and the discriminator itself.
     * <p>
     * Five evidence lines, the maximum the Analyzer prompt allows, carrying the discriminator
     * and both exclusions - acquisition slow while execution is flat (INC-2331's signature),
     * no slow queries (rules out INC-2464), no outbound calls inside transactions (rules out
     * INC-2103). Everything needed to decide is present. That is the point: if the Resolver
     * hedges here, it is not for want of evidence.
     */
    private static final LogAnalysis DIAGNOSIS = new LogAnalysis(
            ErrorType.CONNECTION_POOL_EXHAUSTED,
            "subscription-service",
            Severity.CRITICAL,
            Instant.parse("2026-08-15T13:49:55Z"),
            List.of(
                    "HikariPool-1 - Connection acquisition p99 2ms -> 4100ms; statement execution p99 unchanged at 6ms",
                    "HikariPool-1 - Pool stats (total=25, active=25, idle=0, waiting=147)",
                    "HikariPool-1 - Connection is not available, request timed out after 25000ms",
                    "No statement exceeded the 500ms slow-query threshold in the last 30 minutes (0 entries)",
                    "Mean transaction hold time 9ms, p99 16ms; no outbound HTTP calls recorded inside transaction scope"),
            0.91);

    /** Measured by {@code RetrievalProbe} against this diagnosis. Keyed by incident id. */
    private static final Map<String, Double> SCORES = Map.of(
            "INC-2331", 0.9614,
            "INC-2103", 0.9476,
            "INC-2464", 0.9172);

    /** The answer, for reading the report - not for asserting on. */
    private static final String EXPECTED = "INC-2331";

    @Autowired
    private ResolverAgent resolverAgent;

    @Test
    void measureTheResolverOnOneFixedCase() throws IOException {
        SimilarIncidentSearch frozenSearch = Mockito.mock(SimilarIncidentSearch.class);
        Mockito.when(frozenSearch.findSimilar(Mockito.any(), Mockito.anyInt())).thenReturn(candidates());
        ResolverService resolverService = new ResolverService(resolverAgent, frozenSearch);

        List<AgentResolution> results = new ArrayList<>();
        for (int run = 1; run <= RUNS; run++) {
            if (run > 1) {
                pause();
            }
            AgentResolution resolution = resolverService.resolve(DIAGNOSIS);
            results.add(resolution);
            report(run, resolution);
        }

        summarise(results);
    }

    private static void pause() {
        try {
            Thread.sleep(PACING_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("baseline interrupted while pacing", e);
        }
    }

    private static void report(int run, AgentResolution resolution) {
        System.out.printf("%n---------------- run %d of %d ----------------%n", run, RUNS);
        if (resolution == null) {
            System.out.println("  NO RESOLUTION - the agent call failed or returned nothing usable");
            return;
        }
        System.out.printf("  confidence       : %.2f%n", resolution.confidence());
        System.out.printf("  similarIncidents : %s%s%n", resolution.similarIncidents(),
                resolution.similarIncidents().size() == SCORES.size() ? "   <-- cites ALL THREE" : "");
        System.out.printf("  rootCause        : %s%n", resolution.rootCause());
        System.out.println("  suggestedActions :");
        for (int i = 0; i < resolution.suggestedActions().size(); i++) {
            System.out.printf("     %d. %s%n", i + 1, resolution.suggestedActions().get(i));
        }
    }

    private static void summarise(List<AgentResolution> results) {
        List<AgentResolution> usable = results.stream().filter(java.util.Objects::nonNull).toList();

        System.out.printf("%n================ %d runs, one fixed case ================%n", results.size());
        System.out.printf("  failed calls        : %d%n", results.size() - usable.size());
        if (usable.isEmpty()) {
            return;
        }

        List<Double> confidences = usable.stream()
                .map(AgentResolution::confidence)
                .sorted()
                .toList();
        double mean = confidences.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        System.out.printf(Locale.ROOT, "  confidence          : %s%n", confidences);
        System.out.printf(Locale.ROOT, "  confidence min/mean/max : %.2f / %.2f / %.2f   spread %.2f%n",
                confidences.getFirst(), mean, confidences.getLast(),
                confidences.getLast() - confidences.getFirst());

        // The blend signature is mechanical and worth counting separately from whether the
        // answer was right: citing every precedent means nothing was rejected.
        long citedAll = usable.stream().filter(r -> r.similarIncidents().size() == SCORES.size()).count();
        long citedExpectedAlone = usable.stream()
                .filter(r -> r.similarIncidents().equals(List.of(EXPECTED)))
                .count();
        System.out.printf("  cited all three     : %d of %d%n", citedAll, usable.size());
        System.out.printf("  cited %s alone : %d of %d%n", EXPECTED, citedExpectedAlone, usable.size());

        Map<List<String>, Integer> byCitedSet = new LinkedHashMap<>();
        usable.forEach(r -> byCitedSet.merge(r.similarIncidents(), 1, Integer::sum));
        System.out.println("  cited-set distribution:");
        byCitedSet.entrySet().stream()
                .sorted(Map.Entry.<List<String>, Integer>comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> System.out.printf("     %dx  %s%n", e.getValue(), e.getKey()));

        System.out.println("\n  rootCause is a judgement and is not scored here - read the runs above.");
    }

    /**
     * The three connection-pool incidents from the seed file, with their measured scores.
     * Loaded rather than hand-written so the resolution notes are the real ones, character
     * for character - they are what the Resolver reads, and a paraphrase would measure a
     * different prompt.
     */
    private static List<SimilarIncident> candidates() throws IOException {
        return IncidentSeeder.loadSeedIncidents(Jackson2ObjectMapperBuilder.json().build()).stream()
                .filter(incident -> SCORES.containsKey(incident.id()))
                .map((Incident incident) -> new SimilarIncident(incident, SCORES.get(incident.id())))
                .toList();
    }
}
