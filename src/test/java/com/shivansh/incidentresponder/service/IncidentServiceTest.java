package com.shivansh.incidentresponder.service;

import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.repository.IncidentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
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
    private IncidentRepository incidentRepository;

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
    void readsAStoredIncidentBack() {
        Incident stored = withId(Incident.from(ANALYSIS, Instant.parse("2026-08-12T09:00:00Z")), "abc123");
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
                incident.resolutionNotes(), incident.analyzedAt());
    }
}
