package com.shivansh.incidentresponder.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.seed.IncidentSeeder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs entirely offline - the text is a pure function of the incident, with no model and no
 * database anywhere near it. That is worth stating, because it means retrieval quality can be
 * argued about here, cheaply and repeatably, before a single vector exists.
 * <p>
 * Most of these assertions run against the real seed file rather than fixtures. The text that
 * gets embedded in production is the thing worth pinning; a fixture would only prove the
 * builder is self-consistent.
 */
class IncidentEmbeddingTextTest {

    private static final ObjectMapper OBJECT_MAPPER = Jackson2ObjectMapperBuilder.json().build();

    private static Map<String, Incident> seedsById;

    @BeforeAll
    static void loadTheSeedFile() throws IOException {
        seedsById = IncidentSeeder.loadSeedIncidents(OBJECT_MAPPER).stream()
                .collect(toMap(Incident::id, incident -> incident));
    }

    // --- the invariant --------------------------------------------------------------------

    @Test
    void buildsIdenticalTextFromAStoredIncidentAndFromItsAnalysis() {
        // The reason this class exists. A stored document and an incoming analysis must land
        // in the same place in vector space, and the only way to guarantee that is for both
        // to come out of one recipe. If the two overloads ever drift, every search silently
        // starts ranking on the drift - which would look like poor retrieval, not like a bug.
        assertThat(seedsById.values()).allSatisfy(incident ->
                assertThat(IncidentEmbeddingText.of(incident.toAnalysis()))
                        .as(incident.id())
                        .isEqualTo(IncidentEmbeddingText.of(incident)));
    }

    // --- the exact text -------------------------------------------------------------------

    @Test
    void writesAHeadlineThenTheEvidenceVerbatim() {
        String expected = """
                connection pool exhausted on payment-service
                HikariPool-1 - Connection is not available, request timed out after 30000ms
                HikariPool-1 - Pool stats (total=10, active=10, idle=0, waiting=47)
                Payment authorisation error rate 2% -> 61% within 4 minutes""";

        assertThat(IncidentEmbeddingText.of(seedsById.get("INC-2103"))).isEqualTo(expected);
    }

    @Test
    void spellsTheErrorTypeAsSeparateWords() {
        // Not cosmetic. The tokeniser lowercases before splitting, so "ConnectionPoolExhausted"
        // reaches the model as "connectionpoolexhausted" and shatters into fragments; the
        // spaced form gives it three words it already has embeddings for.
        String text = IncidentEmbeddingText.of(seedsById.get("INC-2103"));

        assertThat(text).startsWith("connection pool exhausted");
        assertThat(text.lines().findFirst().orElseThrow())
                .doesNotContain("_")
                .doesNotContain("ConnectionPoolExhausted")
                .doesNotContain("CONNECTION_POOL_EXHAUSTED");
    }

    @Test
    void leavesOutEverythingTheQuerySideCannotSupply() {
        String text = IncidentEmbeddingText.of(seedsById.get("INC-2103"));

        // resolutionNotes is the one that would help most and is the one that cannot be used:
        // a new incident has no resolution, so embedding it here would compare postmortem
        // prose against raw symptoms.
        assertThat(text).doesNotContain("processRefund");
        // Timestamps, severity and confidence are all present on both sides and still left
        // out - see the class javadoc for why each is noise rather than signal.
        assertThat(text).doesNotContain("2026-03-09", "CRITICAL", "0.89");
    }

    // --- the discrimination that makes retrieval worth doing --------------------------------

    @Test
    void separatesThePoolIncidentsThatShareAnErrorTypeButNotAFix() {
        // INC-2103 (connections held across a network call), INC-2331 (pool genuinely too
        // small) and INC-2464 (a slow query upstream of the pool) are one errorType with three
        // contradictory fixes. Filtering on errorType cannot tell them apart. The claim being
        // pinned here is narrower than "retrieval will rank them correctly" - only that the
        // information needed to separate them survives into the embedded text.
        String leak = IncidentEmbeddingText.of(seedsById.get("INC-2103"));
        String undersized = IncidentEmbeddingText.of(seedsById.get("INC-2331"));
        String slowQuery = IncidentEmbeddingText.of(seedsById.get("INC-2464"));

        assertThat(List.of(leak, undersized, slowQuery)).doesNotHaveDuplicates();

        assertThat(undersized).contains("per-query duration unchanged");
        assertThat(slowQuery).contains("Slow query log");
        assertThat(leak).doesNotContain("per-query duration unchanged", "Slow query log");
    }

    // --- the edges ------------------------------------------------------------------------

    @Test
    void dropsAnUnknownServiceRatherThanEmbeddingTheWord() {
        // "unknown" means the Analyzer could not identify a service. Keeping it would cluster
        // every unidentified incident around a word that describes none of them.
        assertThat(IncidentEmbeddingText.of(analysis(ErrorType.OUT_OF_MEMORY, "unknown")))
                .isEqualTo("out of memory\njava.lang.OutOfMemoryError: Java heap space");
        assertThat(IncidentEmbeddingText.of(analysis(ErrorType.OUT_OF_MEMORY, "UNKNOWN")))
                .isEqualTo("out of memory\njava.lang.OutOfMemoryError: Java heap space");
    }

    @Test
    void dropsAMissingOrBlankService() {
        assertThat(IncidentEmbeddingText.of(analysis(ErrorType.OUT_OF_MEMORY, null)))
                .doesNotContain(" on ");
        assertThat(IncidentEmbeddingText.of(analysis(ErrorType.OUT_OF_MEMORY, "   ")))
                .doesNotContain(" on ");
    }

    @Test
    void stillDescribesAnIncidentThatQuotedNoEvidence() {
        // NotALogFile is defined to carry no evidence, so empty is legitimate input and the
        // headline has to stand on its own.
        LogAnalysis noEvidence = new LogAnalysis(
                ErrorType.NOT_A_LOG_FILE, "unknown", Severity.LOW, null, List.of(), 0.0);

        assertThat(IncidentEmbeddingText.of(noEvidence)).isEqualTo("not a log file");
    }

    @Test
    void refusesToBuildTextWithoutAFailureClass() {
        LogAnalysis noErrorType = new LogAnalysis(
                null, "payment-service", Severity.HIGH, null, List.of("something broke"), 0.5);

        assertThatThrownBy(() -> IncidentEmbeddingText.of(noErrorType))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("errorType");
    }

    private static LogAnalysis analysis(ErrorType errorType, String affectedService) {
        return new LogAnalysis(errorType, affectedService, Severity.HIGH,
                Instant.parse("2026-08-05T02:14:33Z"),
                List.of("java.lang.OutOfMemoryError: Java heap space"), 0.9);
    }
}
