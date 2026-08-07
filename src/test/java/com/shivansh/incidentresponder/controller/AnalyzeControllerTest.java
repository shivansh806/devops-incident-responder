package com.shivansh.incidentresponder.controller;

import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.service.AnalysisFailedException;
import com.shivansh.incidentresponder.service.AnalyzerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalyzeController.class)
class AnalyzeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnalyzerService analyzerService;

    @Test
    void returnsTheStructuredAnalysis() throws Exception {
        given(analyzerService.analyze("HikariPool-1 timed out")).willReturn(new LogAnalysis(
                "ConnectionPoolExhausted",
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
    void serialisesAMissingTimestampAsNull() throws Exception {
        given(analyzerService.analyze(anyString())).willReturn(new LogAnalysis(
                "NotALogFile", "unknown", Severity.LOW, null, List.of(), 0.0));

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logs\":\"hello there\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstOccurrence").doesNotExist());
    }

    @Test
    void returns400WhenLogsAreMissing() throws Exception {
        willThrow(new IllegalArgumentException("'logs' must not be empty"))
                .given(analyzerService).analyze(null);

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("'logs' must not be empty"));
    }

    @Test
    void returns502WhenTheAgentFails() throws Exception {
        willThrow(new AnalysisFailedException("Analyzer agent call failed: timeout"))
                .given(analyzerService).analyze(anyString());

        mockMvc.perform(post("/api/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logs\":\"boom\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("Analyzer agent call failed: timeout"));
    }
}
