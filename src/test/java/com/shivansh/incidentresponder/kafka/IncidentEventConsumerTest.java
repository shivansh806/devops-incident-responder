package com.shivansh.incidentresponder.kafka;

import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.service.AnalysisFailedException;
import com.shivansh.incidentresponder.service.IncidentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Offline. The listener is called directly - no broker, no container, no model call. */
@ExtendWith(MockitoExtension.class)
class IncidentEventConsumerTest {

    @Spy
    private IncidentEventParser parser = new IncidentEventParser();

    @Mock
    private IncidentService incidentService;

    @InjectMocks
    private IncidentEventConsumer consumer;

    @Test
    void passesTheDeclaredServiceThroughAsAuthoritative() {
        when(incidentService.analyzeAndRecord(anyString(), any())).thenReturn(storedIncident());

        consumer.onIncidentEvent(record("""
                {"eventId":"EVT-1","service":"payment-service","logs":"ERROR pool exhausted",
                 "environment":"production"}
                """));

        ArgumentCaptor<String> logs = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> service = ArgumentCaptor.forClass(String.class);
        verify(incidentService).analyzeAndRecord(logs.capture(), service.capture());

        assertThat(logs.getValue()).isEqualTo("ERROR pool exhausted");
        assertThat(service.getValue()).isEqualTo("payment-service");
    }

    /**
     * Absence has to reach the Analyzer as null, which is what makes it infer the origin. If
     * this ever became {@code "unknown"} the two scenarios in {@code IncidentScenario} that
     * omit the field on purpose would silently stop testing anything.
     */
    @Test
    void passesNullWhenTheProducerDeclaredNoService() {
        when(incidentService.analyzeAndRecord(anyString(), any())).thenReturn(storedIncident());

        consumer.onIncidentEvent(record("""
                {"eventId":"EVT-2","logs":"504 from inventory-service"}
                """));

        verify(incidentService).analyzeAndRecord("504 from inventory-service", null);
    }

    /**
     * The listener must let this out rather than catching it. Swallowing it would commit the
     * offset and lose the event; letting it out is what hands it to the error handler, which
     * classifies it as terminal and dead-letters it on the first attempt.
     */
    @Test
    void letsAMalformedMessageOutWithoutCallingThePipeline() {
        assertThatThrownBy(() -> consumer.onIncidentEvent(record("this is not json")))
                .isInstanceOf(MalformedEventException.class);

        verify(incidentService, never()).analyzeAndRecord(anyString(), any());
    }

    /**
     * The retryable half of the same contract: an Analyzer failure propagates, so the error
     * handler gets its one delayed retry rather than the event being dropped here.
     */
    @Test
    void letsAnAnalyzerFailureOutSoItCanBeRetried() {
        when(incidentService.analyzeAndRecord(anyString(), any()))
                .thenThrow(new AnalysisFailedException("Groq returned 429"));

        assertThatThrownBy(() -> consumer.onIncidentEvent(record("""
                {"eventId":"EVT-3","logs":"ERROR x"}
                """)))
                .isInstanceOf(AnalysisFailedException.class);
    }

    private static ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("incident-events", 0, 17L, "payment-service", payload);
    }

    private static Incident storedIncident() {
        return new Incident("64f0c2a1b3d4e5f6a7b8c9d0", ErrorType.CONNECTION_POOL_EXHAUSTED,
                "payment-service", Severity.HIGH, Instant.parse("2026-08-16T10:00:00Z"),
                List.of("ERROR pool exhausted"), 0.9, null, null,
                Instant.parse("2026-08-16T10:05:00Z"), null);
    }
}
