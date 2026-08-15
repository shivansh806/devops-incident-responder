package com.shivansh.incidentresponder.agent;

import com.shivansh.incidentresponder.embedding.SimilarIncident;
import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline: no model, no database, no network. What is pinned here is the SHAPE of what the
 * Resolver is shown, because the two devices that stop it trusting retrieval order are
 * structural rather than written as rules - they exist only as the rendering below, and a
 * refactor could undo them without any prompt text changing.
 * <p>
 * This asserts nothing about the quality of what the model does with it. That needs real
 * calls and is measured separately.
 */
class ResolverPromptTextTest {

    private static final LogAnalysis ANALYSIS = new LogAnalysis(
            ErrorType.CONNECTION_POOL_EXHAUSTED,
            "checkout-service",
            Severity.CRITICAL,
            Instant.parse("2026-08-14T09:12:00Z"),
            List.of("HikariPool-1 - Connection is not available, request timed out after 15000ms"),
            0.86);

    /**
     * The case the whole ordering device exists for, and deliberately not the one the live
     * corpus happens to produce.
     * <p>
     * In {@code docs/retrieval.md}'s production-shaped query the three pool incidents come
     * back with scores that descend in the same order as their dates, so sorting
     * chronologically leaves the highest scorer first anyway and the device does no visible
     * work. Here the newest incident is the best-scoring one, so the two orders genuinely
     * disagree and the sort has to be doing something.
     */
    @Test
    void listsCandidatesOldestFirstEvenWhenThatContradictsTheScoreOrder() {
        String block = ResolverPromptText.pastIncidents(List.of(
                match("INC-2464", "2026-07-28T08:51:22Z", 0.96),
                match("INC-2103", "2026-03-09T14:22:07Z", 0.81),
                match("INC-2331", "2026-06-03T19:05:27Z", 0.88)));

        assertThat(block.indexOf("[INC-2103]"))
                .as("oldest first, despite scoring lowest")
                .isLessThan(block.indexOf("[INC-2331]"));
        assertThat(block.indexOf("[INC-2331]"))
                .isLessThan(block.indexOf("[INC-2464]"));
    }

    @Test
    void namesTheOrderingSoPositionCannotBeReadAsRelevance() {
        String block = ResolverPromptText.pastIncidents(List.of(match("INC-2103", "2026-03-09T14:22:07Z", 0.96)));

        assertThat(block).contains("OLDEST FIRST");
        assertThat(block).contains("it is not a ranking");
    }

    /**
     * The number is shown - it is what separates "this happened before" from "nothing here
     * resembles it" - but never as a bare figure. What it measures is in the label.
     */
    @Test
    void labelsTheScoreAsSymptomSimilarity() {
        String block = ResolverPromptText.pastIncidents(List.of(match("INC-2103", "2026-03-09T14:22:07Z", 0.9602)));

        assertThat(block).contains("symptom similarity to the current incident: 0.96");
    }

    /**
     * No relevance floor anywhere in the pipeline, so a weak candidate must still arrive
     * intact. Dropping it here would be a threshold guessed from one query over 21
     * documents, hidden inside a formatter.
     */
    @Test
    void rendersEveryCandidateHoweverLowItScored() {
        String block = ResolverPromptText.pastIncidents(List.of(
                match("INC-2103", "2026-03-09T14:22:07Z", 0.96),
                match("INC-2507", "2026-04-01T00:00:00Z", 0.41)));

        assertThat(block).contains("[INC-2507]");
        assertThat(block).contains("0.41");
    }

    @Test
    void carriesTheResolutionNotesInFullBecauseTheDiscriminatorIsWrittenInThem() {
        String notes = "Acquisition time versus query time is what separates the two.";
        String block = ResolverPromptText.pastIncidents(List.of(
                new SimilarIncident(incident("INC-2331", "2026-06-03T19:05:27Z", notes), 0.94)));

        assertThat(block).contains(notes);
    }

    @Test
    void saysSoWhenARetrievedIncidentHasNoResolutionNotes() {
        String block = ResolverPromptText.pastIncidents(List.of(
                new SimilarIncident(incident("INC-2331", "2026-06-03T19:05:27Z", null), 0.94)));

        assertThat(block).contains("Not recorded");
        assertThat(block).contains("nothing about what fixed them");
    }

    /** An incident that cannot be dated has no place on a timeline, so it goes last. */
    @Test
    void putsUndatedCandidatesAtTheEnd() {
        String block = ResolverPromptText.pastIncidents(List.of(
                new SimilarIncident(incident("INC-9999", null, "no timestamp in the logs"), 0.99),
                match("INC-2464", "2026-07-28T08:51:22Z", 0.70)));

        assertThat(block.indexOf("[INC-2464]")).isLessThan(block.indexOf("[INC-9999]"));
    }

    /**
     * The prompt has a branch for having no precedent at all, and it needs to be reachable.
     * An empty heading would invite the model to fill it in.
     */
    @Test
    void saysPlainlyWhenNothingWasRetrieved() {
        assertThat(ResolverPromptText.pastIncidents(List.of())).contains("No past incidents were retrieved");
        assertThat(ResolverPromptText.pastIncidents(null)).contains("No past incidents were retrieved");
    }

    @Test
    void rendersTheDiagnosisWithItsEvidenceAndTheConfidenceItCarries() {
        String block = ResolverPromptText.analysis(ANALYSIS);

        assertThat(block).contains("failure: ConnectionPoolExhausted");
        assertThat(block).contains("service: checkout-service");
        assertThat(block).contains("severity: CRITICAL");
        assertThat(block).contains("began: 2026-08-14T09:12:00Z");
        // The prompt tells the Resolver its confidence must not exceed this one, so the
        // number has to actually be in front of it.
        assertThat(block).contains("confidence in this diagnosis: 0.86");
        assertThat(block).contains("- HikariPool-1 - Connection is not available, request timed out after 15000ms");
    }

    @Test
    void toleratesADiagnosisWithNoTimestampAndNoEvidence() {
        String block = ResolverPromptText.analysis(new LogAnalysis(
                ErrorType.NOT_A_LOG_FILE, "unknown", Severity.LOW, null, List.of(), 0.0));

        assertThat(block).contains("began: unknown");
        assertThat(block).contains("(none recorded)");
    }

    private static SimilarIncident match(String id, String firstOccurrence, double score) {
        return new SimilarIncident(incident(id, firstOccurrence, "resolved by doing the thing"), score);
    }

    private static Incident incident(String id, String firstOccurrence, String resolutionNotes) {
        return new Incident(
                id,
                ErrorType.CONNECTION_POOL_EXHAUSTED,
                "payment-service",
                Severity.CRITICAL,
                firstOccurrence == null ? null : Instant.parse(firstOccurrence),
                List.of("HikariPool-1 - Connection is not available"),
                0.89,
                resolutionNotes,
                null,
                Instant.parse("2026-03-09T14:58:31Z"),
                null);
    }
}
