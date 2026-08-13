package com.shivansh.incidentresponder.controller;

import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.service.IncidentNotFoundException;
import com.shivansh.incidentresponder.service.IncidentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(IncidentController.class)
class IncidentControllerTest {

    private static final String ID = "651f2c9a4b1d3e0001a2b3c4";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IncidentService incidentService;

    @Test
    void readsBackAStoredIncident() throws Exception {
        given(incidentService.findById(ID)).willReturn(new Incident(
                ID,
                ErrorType.CACHE_UNAVAILABLE,
                "profile-service",
                Severity.MEDIUM,
                Instant.parse("2026-08-05T02:14:33Z"),
                List.of("redis: connection refused"),
                0.9,
                "Restarted the redis node and warmed the cache",
                Instant.parse("2026-08-12T09:00:00Z")));

        mockMvc.perform(get("/api/incidents/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID))
                .andExpect(jsonPath("$.resolutionNotes").value("Restarted the redis node and warmed the cache"))
                .andExpect(jsonPath("$.analyzedAt").value("2026-08-12T09:00:00Z"))
                // Same nesting POST /api/analyze returns, and the PascalCase wire format for
                // errorType - not the CACHE_UNAVAILABLE constant name Mongo stores.
                .andExpect(jsonPath("$.analysis.errorType").value("CacheUnavailable"))
                .andExpect(jsonPath("$.analysis.affectedService").value("profile-service"))
                .andExpect(jsonPath("$.analysis.severity").value("MEDIUM"))
                .andExpect(jsonPath("$.analysis.keyEvidence[0]").value("redis: connection refused"))
                .andExpect(jsonPath("$.analysis.confidence").value(0.9));
    }

    @Test
    void returns404ForAnUnknownId() throws Exception {
        willThrow(new IncidentNotFoundException("does-not-exist"))
                .given(incidentService).findById("does-not-exist");

        mockMvc.perform(get("/api/incidents/{id}", "does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No incident found with id 'does-not-exist'"));
    }
}
