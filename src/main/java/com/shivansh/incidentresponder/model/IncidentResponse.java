package com.shivansh.incidentresponder.model;

import java.time.Instant;

/**
 * What both {@code POST /api/analyze} and {@code GET /api/incidents/{id}} return.
 * <p>
 * One type for both so the two endpoints agree, and so a caller that has just posted a log
 * dump holds the whole record without a follow-up read.
 * <p>
 * It is a separate type from {@link Incident} rather than the document itself, because a
 * document accumulates fields that have no business on the wire - the embedding vector in
 * the next step being the obvious one. Choosing what the API exposes here, explicitly, is
 * cheaper than remembering to suppress each new storage field.
 *
 * @param id              the stored incident's id, usable directly in {@code /api/incidents/{id}}
 * @param analysis        the Analyzer's diagnosis, unchanged from what week 1 returned
 * @param resolutionNotes how the incident was put right, or null while it is still open
 * @param analyzedAt      when the analysis was run
 */
public record IncidentResponse(
        String id,
        LogAnalysis analysis,
        String resolutionNotes,
        Instant analyzedAt
) {

    public static IncidentResponse of(Incident incident) {
        return new IncidentResponse(
                incident.id(),
                incident.toAnalysis(),
                incident.resolutionNotes(),
                incident.analyzedAt());
    }
}
