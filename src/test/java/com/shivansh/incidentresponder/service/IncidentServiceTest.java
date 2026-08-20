package com.shivansh.incidentresponder.service;

import com.shivansh.incidentresponder.model.AgentResolution;
import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.repository.IncidentRepository;
import com.shivansh.incidentresponder.websocket.IncidentBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class IncidentServiceTest {

    private static final LogAnalysis ANALYSIS = new LogAnalysis(
            ErrorType.CONNECTION_POOL_EXHAUSTED,
            "payment-service",
            Severity.CRITICAL,
            Instant.parse("2026-08-05T02:14:33Z"),
            List.of("HikariPool-1 - Connection is not available"),
            0.87);

    @Mock
    private AnalyzerService analyzerService;

    @Mock
    private ResolverService resolverService;

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private IncidentBroadcaster broadcaster;

    @InjectMocks
    private IncidentService incidentService;

    @Captor
    private ArgumentCaptor<Incident> savedIncident;

    @Test
    void storesTheAnalysisAndReturnsTheIncidentCarryingItsNewId() {
        given(analyzerService.analyze("HikariPool-1 timed out", null)).willReturn(ANALYSIS);
        // Mongo assigns the id on insert and, because Incident is a record, hands it back on
        // a new instance. Returning the argument here would reproduce the bug this guards.
        given(incidentRepository.save(any(Incident.class)))
                .willAnswer(call -> withId(call.getArgument(0), "651f2c9a4b1d3e0001a2b3c4"));

        Incident result = incidentService.analyzeAndRecord("HikariPool-1 timed out", null);

        assertThat(result.id()).isEqualTo("651f2c9a4b1d3e0001a2b3c4");
        assertThat(result.errorType()).isEqualTo(ErrorType.CONNECTION_POOL_EXHAUSTED);
    }

    @Test
    void copiesEveryAnalysisFieldOntoTheDocumentAndStampsIt() {
        Instant before = Instant.now();
        given(analyzerService.analyze("boom", "payment-service")).willReturn(ANALYSIS);
        given(incidentRepository.save(any(Incident.class))).willAnswer(call -> call.getArgument(0));

        incidentService.analyzeAndRecord("boom", "payment-service");

        then(incidentRepository).should().save(savedIncident.capture());
        Incident stored = savedIncident.getValue();

        assertThat(stored.id()).as("Mongo assigns it on insert").isNull();
        assertThat(stored.resolutionNotes()).as("nothing has resolved it yet").isNull();
        assertThat(stored.analyzedAt()).isBetween(before, Instant.now());
        // Round-tripping proves the two mappings agree, which is the whole contract between
        // the stored document and the API type.
        assertThat(stored.toAnalysis()).isEqualTo(ANALYSIS);
    }

    @Test
    void storesWhatTheResolverRecommendedAlongsideTheDiagnosis() {
        AgentResolution resolution = new AgentResolution(
                "Acquisition time rose while execution held flat, which is INC-2331's case",
                "Connections were held across a slow outbound gateway call",
                List.of("Move the gateway call outside the transaction", "Leave maximum-pool-size alone"),
                List.of("INC-2103", "INC-2331"),
                0.81);
        given(analyzerService.analyze("HikariPool-1 timed out", null)).willReturn(ANALYSIS);
        given(resolverService.resolve(ANALYSIS)).willReturn(resolution);
        given(incidentRepository.save(any(Incident.class))).willAnswer(call -> call.getArgument(0));

        incidentService.analyzeAndRecord("HikariPool-1 timed out", null);

        then(incidentRepository).should().save(savedIncident.capture());
        Incident stored = savedIncident.getValue();

        assertThat(stored.resolution()).isEqualTo(resolution);
        // The two fields must stay apart. resolutionNotes is the human record of what
        // actually fixed an incident and is the only thing retrieval ever shows the agent;
        // writing a generated proposal there would feed the Resolver its own output back as
        // precedent on every future call.
        assertThat(stored.resolutionNotes()).isNull();
    }

    @Test
    void keepsTheDiagnosisWhenTheResolverProducesNothing() {
        given(analyzerService.analyze("HikariPool-1 timed out", null)).willReturn(ANALYSIS);
        // Null is what ResolverService returns when the agent call failed - a rate limit, a
        // timeout, an unusable reply. The analysis was already paid for and is still worth
        // keeping, so the incident is stored regardless.
        given(resolverService.resolve(ANALYSIS)).willReturn(null);
        given(incidentRepository.save(any(Incident.class))).willAnswer(call -> call.getArgument(0));

        Incident result = incidentService.analyzeAndRecord("HikariPool-1 timed out", null);

        assertThat(result.resolution()).isNull();
        assertThat(result.toAnalysis()).isEqualTo(ANALYSIS);
    }

    @Test
    void resolvesTheDiagnosisTheAnalyzerProduced() {
        given(analyzerService.analyze("HikariPool-1 timed out", null)).willReturn(ANALYSIS);
        given(incidentRepository.save(any(Incident.class))).willAnswer(call -> call.getArgument(0));

        incidentService.analyzeAndRecord("HikariPool-1 timed out", null);

        // The order is the architecture: the Resolver's input is the Analyzer's output, and
        // it is that value it must be handed - not the raw logs, and not a rebuilt copy.
        then(resolverService).should().resolve(ANALYSIS);
    }

    @Test
    void pushesTheStoredIncidentToConnectedDashboards() {
        given(analyzerService.analyze("HikariPool-1 timed out", null)).willReturn(ANALYSIS);
        given(incidentRepository.save(any(Incident.class)))
                .willAnswer(call -> withId(call.getArgument(0), "651f2c9a4b1d3e0001a2b3c4"));

        incidentService.analyzeAndRecord("HikariPool-1 timed out", null);

        // The SAVED incident, not the one passed to save(). A client that is pushed an
        // incident with a null id cannot key on it, and keying on id is what makes both the
        // on-connect replay and the REST caller's own echo idempotent.
        then(broadcaster).should().broadcast(savedIncident.capture());
        assertThat(savedIncident.getValue().id()).isEqualTo("651f2c9a4b1d3e0001a2b3c4");
    }

    @Test
    void pushesAfterStoringRatherThanBefore() {
        given(analyzerService.analyze("HikariPool-1 timed out", null)).willReturn(ANALYSIS);
        given(incidentRepository.save(any(Incident.class))).willAnswer(call -> call.getArgument(0));

        incidentService.analyzeAndRecord("HikariPool-1 timed out", null);

        // Storage failure still fails the whole call, unchanged from week 2. Pushing first
        // would tell every dashboard about an incident that then failed to persist and cannot
        // be opened at GET /api/incidents/{id} - a row on screen with no record behind it.
        InOrder order = inOrder(incidentRepository, broadcaster);
        order.verify(incidentRepository).save(any(Incident.class));
        order.verify(broadcaster).broadcast(any(Incident.class));
    }

    @Test
    void readsAStoredIncidentBack() {
        Incident stored = withId(Incident.from(ANALYSIS, null, Instant.parse("2026-08-12T09:00:00Z")), "abc123");
        given(incidentRepository.findById("abc123")).willReturn(Optional.of(stored));

        assertThat(incidentService.findById("abc123")).isEqualTo(stored);
    }

    @Test
    void failsLoudlyWhenTheIncidentIsNotThere() {
        given(incidentRepository.findById("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> incidentService.findById("nope"))
                .isInstanceOf(IncidentNotFoundException.class)
                .hasMessageContaining("nope");
    }

    /** Stands in for what Mongo does to an immutable entity on insert. */
    private static Incident withId(Incident incident, String id) {
        return new Incident(id, incident.errorType(), incident.affectedService(), incident.severity(),
                incident.firstOccurrence(), incident.keyEvidence(), incident.confidence(),
                incident.resolutionNotes(), incident.resolution(), incident.analyzedAt(),
                incident.embedding());
    }
}
