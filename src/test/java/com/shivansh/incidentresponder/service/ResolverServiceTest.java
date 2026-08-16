package com.shivansh.incidentresponder.service;

import com.shivansh.incidentresponder.agent.ResolverAgent;
import com.shivansh.incidentresponder.agent.ResolverOutput;
import com.shivansh.incidentresponder.embedding.SimilarIncident;
import com.shivansh.incidentresponder.embedding.SimilarIncidentSearch;
import com.shivansh.incidentresponder.model.AgentResolution;
import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

/**
 * Offline: no Groq, no Atlas, no embedding model. Everything here is about what the service
 * does with a reply, not about the quality of the reply - the model is mocked, so its answers
 * are whatever this file says they are.
 * <p>
 * The cases worth having are the misbehaving ones. A well-formed response needs almost no
 * code; the reason this class exists is that a real model returns confidence 1.4, cites an
 * incident that was never supplied, and sometimes does not answer at all.
 */
@ExtendWith(MockitoExtension.class)
class ResolverServiceTest {

    private static final LogAnalysis ANALYSIS = new LogAnalysis(
            ErrorType.CONNECTION_POOL_EXHAUSTED,
            "checkout-service",
            Severity.CRITICAL,
            Instant.parse("2026-08-14T09:12:00Z"),
            List.of("HikariPool-1 - Connection is not available, request timed out after 15000ms"),
            0.86);

    @Mock
    private ResolverAgent resolverAgent;

    @Mock
    private SimilarIncidentSearch similarIncidentSearch;

    @InjectMocks
    private ResolverService resolverService;

    @Captor
    private ArgumentCaptor<String> pastIncidentsBlock;

    /**
     * Three is the measured number, argued in full on the constant itself. Pinned because it
     * is a decision with evidence behind it, and a silent drift to 5 would change what the
     * Resolver sees on every call with nothing to notice it by.
     */
    @Test
    void asksForExactlyThreePastIncidents() {
        given(similarIncidentSearch.findSimilar(any(), anyInt())).willReturn(List.of());
        given(resolverAgent.resolve(anyString(), anyString())).willReturn(wellFormed());

        resolverService.resolve(ANALYSIS);

        then(similarIncidentSearch).should().findSimilar(ANALYSIS, 3);
    }

    @Test
    void handsTheAgentTheRetrievedIncidents() {
        given(similarIncidentSearch.findSimilar(any(), anyInt()))
                .willReturn(List.of(match("INC-2103"), match("INC-2331")));
        given(resolverAgent.resolve(anyString(), anyString())).willReturn(wellFormed());

        resolverService.resolve(ANALYSIS);

        then(resolverAgent).should().resolve(anyString(), pastIncidentsBlock.capture());
        assertThat(pastIncidentsBlock.getValue()).contains("[INC-2103]", "[INC-2331]");
    }

    @Test
    void normalisesAWellFormedReply() {
        given(similarIncidentSearch.findSimilar(any(), anyInt())).willReturn(List.of(match("INC-2103")));
        given(resolverAgent.resolve(anyString(), anyString())).willReturn(new ResolverOutput(
                "  Acquisition time rose while execution held flat  ",
                "  Connections were held across a slow gateway call  ",
                List.of("  Move the call out of the transaction  ", "", "   "),
                List.of("INC-2103"),
                0.78));

        AgentResolution resolution = resolverService.resolve(ANALYSIS);

        assertThat(resolution.decidingEvidence()).isEqualTo("Acquisition time rose while execution held flat");
        assertThat(resolution.rootCause()).isEqualTo("Connections were held across a slow gateway call");
        assertThat(resolution.suggestedActions()).containsExactly("Move the call out of the transaction");
        assertThat(resolution.similarIncidents()).containsExactly("INC-2103");
        assertThat(resolution.confidence()).isEqualTo(0.78);
    }

    /**
     * A citation is a claim about provenance. An invented id makes the answer look grounded
     * in history that does not exist, which is worse than a weak recommendation because it is
     * the part a reader would check it against.
     */
    @Test
    void dropsCitedIncidentsThatWereNeverSupplied() {
        given(similarIncidentSearch.findSimilar(any(), anyInt())).willReturn(List.of(match("INC-2103")));
        given(resolverAgent.resolve(anyString(), anyString())).willReturn(new ResolverOutput(
                "Acquisition time rose while execution held flat",
                "Connections were held across a slow gateway call",
                List.of("Move the call out of the transaction"),
                // EXAMPLE-A is from the prompt's worked example and INC-9999 is invented.
                // Both are exactly what this filter is for.
                Arrays.asList("INC-2103", "EXAMPLE-A", "INC-9999", null, "  INC-2103  "),
                0.78));

        AgentResolution resolution = resolverService.resolve(ANALYSIS);

        assertThat(resolution.similarIncidents()).containsExactly("INC-2103");
    }

    @Test
    void clampsConfidenceIntoRangeAndSurvivesItBeingOmitted() {
        given(similarIncidentSearch.findSimilar(any(), anyInt())).willReturn(List.of());
        given(resolverAgent.resolve(anyString(), anyString()))
                .willReturn(new ResolverOutput("what decided it", "a cause", List.of("do the thing"), null, 1.4))
                .willReturn(new ResolverOutput(null, "a cause", null, null, null));

        assertThat(resolverService.resolve(ANALYSIS).confidence()).isEqualTo(1.0);

        AgentResolution second = resolverService.resolve(ANALYSIS);
        assertThat(second.confidence()).isEqualTo(0.0);
        assertThat(second.suggestedActions()).isEmpty();
        assertThat(second.similarIncidents()).isEmpty();
    }

    /**
     * An empty decidingEvidence means the field declared first did not get written, which is
     * the fix's mechanism failing on that call. It is worth a defined value and a log line,
     * but not worth discarding an otherwise sound recommendation - unlike rootCause below.
     */
    @Test
    void substitutesADefinedValueWhenDecidingEvidenceIsMissing() {
        given(similarIncidentSearch.findSimilar(any(), anyInt())).willReturn(List.of());
        given(resolverAgent.resolve(anyString(), anyString()))
                .willReturn(new ResolverOutput("   ", "a cause", List.of("do the thing"), List.of(), 0.7));

        AgentResolution resolution = resolverService.resolve(ANALYSIS);

        assertThat(resolution).isNotNull();
        assertThat(resolution.decidingEvidence()).isEqualTo(AgentResolution.NOT_STATED);
        assertThat(resolution.rootCause()).isEqualTo("a cause");
    }

    /**
     * Actions with nothing stating what they address is the exact shape of output this agent
     * exists to avoid producing. Shipping it under a plausible confidence would be worse than
     * shipping nothing.
     */
    @Test
    void discardsAResolutionWithNoRootCause() {
        given(similarIncidentSearch.findSimilar(any(), anyInt())).willReturn(List.of());
        given(resolverAgent.resolve(anyString(), anyString()))
                .willReturn(new ResolverOutput("what decided it", "   ", List.of("restart it"), List.of(), 0.9));

        assertThat(resolverService.resolve(ANALYSIS)).isNull();
    }

    /**
     * A rate limit is an expected condition on a 100,000 token daily budget, not an
     * exception worth losing a paid-for diagnosis over.
     */
    @Test
    void degradesToNoResolutionWhenTheAgentCallFails() {
        given(similarIncidentSearch.findSimilar(any(), anyInt())).willReturn(List.of());
        willThrow(new RuntimeException("429 rate limit"))
                .given(resolverAgent).resolve(anyString(), anyString());

        assertThat(resolverService.resolve(ANALYSIS)).isNull();
    }

    @Test
    void degradesToNoResolutionWhenTheAgentReturnsNothing() {
        given(similarIncidentSearch.findSimilar(any(), anyInt())).willReturn(List.of());
        given(resolverAgent.resolve(anyString(), anyString())).willReturn(null);

        assertThat(resolverService.resolve(ANALYSIS)).isNull();
    }

    /**
     * The two failures degrade differently on purpose. A broken vector index should cost
     * precedent, not the whole recommendation - the prompt already has a branch for having no
     * history to draw on.
     */
    @Test
    void stillResolvesWhenRetrievalFails() {
        willThrow(new RuntimeException("no such index: incident_embedding_index"))
                .given(similarIncidentSearch).findSimilar(any(), eq(3));
        given(resolverAgent.resolve(anyString(), anyString())).willReturn(wellFormed());

        AgentResolution resolution = resolverService.resolve(ANALYSIS);

        assertThat(resolution).isNotNull();
        then(resolverAgent).should().resolve(anyString(), pastIncidentsBlock.capture());
        assertThat(pastIncidentsBlock.getValue()).contains("No past incidents were retrieved");
    }

    private static ResolverOutput wellFormed() {
        return new ResolverOutput("what decided it", "a cause", List.of("do the thing"), List.of(), 0.7);
    }

    private static SimilarIncident match(String id) {
        return new SimilarIncident(new Incident(
                id,
                ErrorType.CONNECTION_POOL_EXHAUSTED,
                "payment-service",
                Severity.CRITICAL,
                Instant.parse("2026-03-09T14:22:07Z"),
                List.of("HikariPool-1 - Connection is not available"),
                0.89,
                "Moved the gateway call out of the transaction",
                null,
                Instant.parse("2026-03-09T14:58:31Z"),
                null), 0.94);
    }
}
