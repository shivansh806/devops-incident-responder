package com.shivansh.incidentresponder.service;

import com.shivansh.incidentresponder.agent.AnalyzerAgent;
import com.shivansh.incidentresponder.agent.AnalyzerOutput;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Covers the untrusted-output normalisation. The agent is mocked, so nothing here talks to
 * Groq - these tests run offline and without an API key.
 */
@ExtendWith(MockitoExtension.class)
class AnalyzerServiceTest {

    @Mock
    private AnalyzerAgent analyzerAgent;

    @InjectMocks
    private AnalyzerService analyzerService;

    @Test
    void mapsAWellFormedModelReply() {
        given(analyzerAgent.analyze("some logs")).willReturn(new AnalyzerOutput(
                "ConnectionPoolExhausted",
                "payment-service",
                Severity.CRITICAL,
                "2026-08-05T02:14:33Z",
                List.of("HikariPool-1 - Connection is not available"),
                0.87));

        LogAnalysis analysis = analyzerService.analyze("some logs");

        assertThat(analysis.errorType()).isEqualTo("ConnectionPoolExhausted");
        assertThat(analysis.affectedService()).isEqualTo("payment-service");
        assertThat(analysis.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(analysis.firstOccurrence()).isEqualTo(Instant.parse("2026-08-05T02:14:33Z"));
        assertThat(analysis.keyEvidence()).containsExactly("HikariPool-1 - Connection is not available");
        assertThat(analysis.confidence()).isEqualTo(0.87);
    }

    @Test
    void acceptsTimestampsThatAreNotStrictInstants() {
        given(analyzerAgent.analyze("logs")).willReturn(output("2026-08-05T07:44:33+05:30"));

        assertThat(analyzerService.analyze("logs").firstOccurrence())
                .isEqualTo(Instant.parse("2026-08-05T02:14:33Z"));
    }

    @Test
    void treatsALocalDateTimeAsUtc() {
        given(analyzerAgent.analyze("logs")).willReturn(output("2026-08-05T02:14:33"));

        assertThat(analyzerService.analyze("logs").firstOccurrence())
                .isEqualTo(Instant.parse("2026-08-05T02:14:33Z"));
    }

    @Test
    void nullsOutTimestampsThatCannotBeParsed() {
        given(analyzerAgent.analyze("logs")).willReturn(output("some time around 2am"));

        assertThat(analyzerService.analyze("logs").firstOccurrence()).isNull();
    }

    @Test
    void treatsPlaceholderTimestampsAsAbsent() {
        given(analyzerAgent.analyze("logs")).willReturn(output("unknown"));

        assertThat(analyzerService.analyze("logs").firstOccurrence()).isNull();
    }

    @Test
    void clampsConfidenceIntoRange() {
        given(analyzerAgent.analyze("logs")).willReturn(new AnalyzerOutput(
                "OutOfMemoryError", "order-service", Severity.HIGH, null, List.of(), 1.5));

        assertThat(analyzerService.analyze("logs").confidence()).isEqualTo(1.0);
    }

    @Test
    void fillsInDefaultsWhenTheModelOmitsFields() {
        given(analyzerAgent.analyze("logs")).willReturn(
                new AnalyzerOutput(null, "  ", null, null, null, null));

        LogAnalysis analysis = analyzerService.analyze("logs");

        assertThat(analysis.errorType()).isEqualTo("UnknownError");
        assertThat(analysis.affectedService()).isEqualTo("unknown");
        assertThat(analysis.severity()).isEqualTo(Severity.MEDIUM);
        assertThat(analysis.keyEvidence()).isEmpty();
        assertThat(analysis.confidence()).isEqualTo(0.0);
    }

    @Test
    void dropsBlankAndNullEvidenceLines() {
        given(analyzerAgent.analyze("logs")).willReturn(new AnalyzerOutput(
                "UpstreamTimeout", "order-service", Severity.MEDIUM, null,
                Arrays.asList("  a real line  ", null, "   "), 0.5));

        assertThat(analyzerService.analyze("logs").keyEvidence()).containsExactly("a real line");
    }

    @Test
    void callerSuppliedServiceOverridesTheModel() {
        // The bug that prompted this: the model picked a connection pool name out of the logs.
        given(analyzerAgent.analyzeForService("logs", "payment-service")).willReturn(new AnalyzerOutput(
                "ConnectionPoolExhausted", "HikariPool-1", Severity.CRITICAL, null, List.of(), 0.9));

        assertThat(analyzerService.analyze("logs", "payment-service").affectedService())
                .isEqualTo("payment-service");
    }

    @Test
    void callerSuppliedServiceIsUsedEvenWhenTheModelOmitsIt() {
        given(analyzerAgent.analyzeForService("logs", "order-service")).willReturn(new AnalyzerOutput(
                "UpstreamTimeout", null, Severity.HIGH, null, List.of(), 0.6));

        assertThat(analyzerService.analyze("logs", "order-service").affectedService())
                .isEqualTo("order-service");
    }

    @Test
    void routesToTheInferringPromptWhenNoServiceIsSupplied() {
        given(analyzerAgent.analyze("logs")).willReturn(output(null));

        assertThat(analyzerService.analyze("logs", "  ").affectedService()).isEqualTo("order-service");
        verify(analyzerAgent, never()).analyzeForService(anyString(), anyString());
    }

    @Test
    void trimsTheCallerSuppliedService() {
        given(analyzerAgent.analyzeForService("logs", "payment-service")).willReturn(
                output(null));

        assertThat(analyzerService.analyze("logs", "  payment-service  ").affectedService())
                .isEqualTo("payment-service");
    }

    @Test
    void rejectsEmptyLogs() {
        assertThatThrownBy(() -> analyzerService.analyze("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be empty");
    }

    @Test
    void rejectsOversizedLogs() {
        assertThatThrownBy(() -> analyzerService.analyze("x".repeat(50_001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void wrapsAgentFailures() {
        willThrow(new RuntimeException("groq returned 429"))
                .given(analyzerAgent).analyze("logs");

        assertThatThrownBy(() -> analyzerService.analyze("logs"))
                .isInstanceOf(AnalysisFailedException.class)
                .hasMessageContaining("groq returned 429");
    }

    private static AnalyzerOutput output(String firstOccurrence) {
        return new AnalyzerOutput(
                "UpstreamTimeout", "order-service", Severity.HIGH, firstOccurrence, List.of(), 0.7);
    }
}
