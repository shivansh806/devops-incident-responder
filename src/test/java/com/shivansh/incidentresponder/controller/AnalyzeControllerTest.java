package com.shivansh.incidentresponder.controller;

import com.shivansh.incidentresponder.config.JacksonConfig;
import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.service.AnalysisFailedException;
import com.shivansh.incidentresponder.service.AnalyzerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// JacksonConfig is a @Configuration, which the @WebMvcTest slice filters out, so it has to
// be imported explicitly for the unknown-property handler to be part of this test.
@WebMvcTest(AnalyzeController.class)
@Import(JacksonConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class AnalyzeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalyzerService analyzerService;

    @Test
    void returnsTheStructuredAnalysis() throws Exception {
        given(analyzerService.analyze("HikariPool-1 timed out", null)).willReturn(new LogAnalysis(
                ErrorType.CONNECTION_POOL_EXHAUSTED,
                "payment-service",
                Severity.CRITICAL,
                Instant.parse("2026-08-05T02:14:33Z"),
                List.of("HikariPool-1 - Connection is not available"),
                0.87));

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logs\":\"HikariPool-1 timed out\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorType").value("ConnectionPoolExhausted"))
                .andExpect(jsonPath("$.affectedService").value("payment-service"))
                .andExpect(jsonPath("$.severity").value("CRITICAL"))
                .andExpect(jsonPath("$.firstOccurrence").value("2026-08-05T02:14:33Z"))
                .andExpect(jsonPath("$.keyEvidence[0]").value("HikariPool-1 - Connection is not available"))
                .andExpect(jsonPath("$.confidence").value(0.87));
    }

    @Test
    void forwardsTheCallerSuppliedServiceName() throws Exception {
        given(analyzerService.analyze("HikariPool-1 timed out", "payment-service")).willReturn(
                new LogAnalysis(ErrorType.CONNECTION_POOL_EXHAUSTED, "payment-service", Severity.CRITICAL,
                        null, List.of(), 0.9));

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logs\":\"HikariPool-1 timed out\",\"serviceName\":\"payment-service\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedService").value("payment-service"));
    }

    @Test
    void warnsAboutUnknownPropertiesButStillSucceeds(CapturedOutput output) throws Exception {
        given(analyzerService.analyze(anyString(), any())).willReturn(new LogAnalysis(
                ErrorType.UPSTREAM_TIMEOUT, "unknown", Severity.HIGH, null, List.of(), 0.6));

        // "service_name" is a plausible typo for "serviceName" - it must not be fatal,
        // but it must not vanish silently either.
        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logs\":\"boom\",\"service_name\":\"payment-service\"}"))
                .andExpect(status().isOk());

        assertThat(output).contains("Ignoring unknown JSON property 'service_name'")
                .contains("AnalyzeRequest");
    }

    @Test
    void serialisesAMissingTimestampAsNull() throws Exception {
        given(analyzerService.analyze(anyString(), any())).willReturn(new LogAnalysis(
                ErrorType.NOT_A_LOG_FILE, "unknown", Severity.LOW, null, List.of(), 0.0));

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logs\":\"hello there\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstOccurrence").doesNotExist());
    }

    @Test
    void returns400WhenLogsAreMissing() throws Exception {
        willThrow(new IllegalArgumentException("'logs' must not be empty"))
                .given(analyzerService).analyze(null, null);

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("'logs' must not be empty"));
    }

    @Test
    void returns502WhenTheAgentFails() throws Exception {
        willThrow(new AnalysisFailedException("Analyzer agent call failed: timeout"))
                .given(analyzerService).analyze(anyString(), any());

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logs\":\"boom\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Analyzer agent call failed: timeout"));
    }
}
