package com.shivansh.incidentresponder.embedding;

import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.seed.IncidentSeeder;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Prints what a candidate query would retrieve, without Atlas and without Groq.
 * <p>
 * Not a test - it asserts nothing. It recomputes every seed vector locally and scores by
 * exact cosine, normalised the way Atlas reports it ({@code (1 + cos) / 2}).
 * {@code docs/retrieval.md} established that this matches the live index to four decimal
 * places, which is what makes an offline answer trustworthy.
 * <p>
 * <b>What it is for.</b> Designing an incident to exercise a specific retrieval situation is
 * otherwise guesswork paid for in Groq quota - the first Resolver run needed a case where
 * chronological order and similarity order disagree, and that is a property of the vectors,
 * knowable before any LLM call. Retrieval is local and deterministic, so this costs nothing
 * and returns the same numbers every time.
 * <p>
 * <b>It does not run in the build.</b> The name matches none of surefire's patterns
 * ({@code *Test}, {@code Test*}, {@code *Tests}, {@code *TestCase}), which is deliberate:
 * it loads a 83MB model and prints rather than asserting, so it has no business slowing
 * {@code mvn test}. Run it on demand:
 * <pre>
 *   mvn test -Dtest=RetrievalProbe -DfailIfNoSpecifiedTests=false
 * </pre>
 * The queries below are whatever was last being investigated. Edit them freely - nothing
 * depends on them.
 */
class RetrievalProbe {

    private static final EmbeddingModel MODEL = new AllMiniLmL6V2EmbeddingModel();

    @Test
    void printCandidateRankings() throws IOException {
        List<Incident> seeds = IncidentSeeder.loadSeedIncidents(
                Jackson2ObjectMapperBuilder.json().build());

        Map<String, String> queries = Map.of(
                "A: acquisition/execution + pool stats + timeout", String.join("\n", List.of(
                        "HikariPool-1 - Connection is not available, request timed out after 25000ms",
                        "HikariPool-1 - Pool stats (total=25, active=25, idle=0, waiting=147)",
                        "HikariPool-1 - Connection acquisition p99 12800ms; statement execution p99 unchanged at 7ms")),
                "B: + no slow queries", String.join("\n", List.of(
                        "HikariPool-1 - Connection is not available, request timed out after 25000ms",
                        "HikariPool-1 - Pool stats (total=25, active=25, idle=0, waiting=147)",
                        "HikariPool-1 - Connection acquisition p99 12800ms; statement execution p99 unchanged at 7ms",
                        "No statement exceeded the 500ms slow-query threshold in the last 30 minutes (0 entries)")),
                "C: + config and txn hold time", String.join("\n", List.of(
                        "HikariPool-1 - Connection is not available, request timed out after 25000ms",
                        "HikariPool-1 - Pool stats (total=25, active=25, idle=0, waiting=147)",
                        "HikariPool-1 - Connection acquisition p99 2ms -> 4100ms; statement execution p99 unchanged at 6ms",
                        "HikariPool-1 - maximumPoolSize=25, unchanged since 2024-11-08; 4 service instances running",
                        "Mean transaction hold time 9ms, p99 16ms; no outbound HTTP calls recorded inside transaction scope")),
                "D: minimal, two boilerplate lines only", String.join("\n", List.of(
                        "HikariPool-1 - Connection is not available, request timed out after 25000ms",
                        "HikariPool-1 - Pool stats (total=25, active=25, idle=0, waiting=147)")));

        queries.keySet().stream().sorted().forEach(label -> {
            String evidence = queries.get(label);
            LogAnalysis analysis = new LogAnalysis(
                    ErrorType.CONNECTION_POOL_EXHAUSTED,
                    "subscription-service",
                    Severity.CRITICAL,
                    Instant.parse("2026-08-15T13:49:55Z"),
                    List.of(evidence.split("\n")),
                    0.9);

            float[] query = MODEL.embed(IncidentEmbeddingText.of(analysis)).content().vector();

            System.out.println("\n==== " + label + " ====");
            seeds.stream()
                    .map(seed -> Map.entry(seed, score(query,
                            MODEL.embed(IncidentEmbeddingText.of(seed)).content().vector())))
                    .sorted(Map.Entry.<Incident, Double>comparingByValue(Comparator.reverseOrder()))
                    .limit(5)
                    .forEach(hit -> System.out.printf("  %.4f  %-9s %-28s %s   [%s]%n",
                            hit.getValue(),
                            hit.getKey().id(),
                            hit.getKey().errorType(),
                            hit.getKey().affectedService(),
                            hit.getKey().firstOccurrence().toString().substring(0, 10)));
        });
    }

    /** Atlas reports (1 + cos) / 2, not the raw cosine. */
    private static double score(float[] a, float[] b) {
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return (1 + dot / (Math.sqrt(normA) * Math.sqrt(normB))) / 2;
    }
}
