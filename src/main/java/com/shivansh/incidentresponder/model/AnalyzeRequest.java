package com.shivansh.incidentresponder.model;

/**
 * Request body for {@code POST /api/analyze}.
 *
 * @param logs raw application log dump, exactly as it would be copied out of a log viewer
 */
public record AnalyzeRequest(String logs) {
}
