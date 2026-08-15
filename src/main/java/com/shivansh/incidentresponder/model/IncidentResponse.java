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
 * @param resolution      the Resolver's recommendation, or null. Null has two causes that
 *                        are worth telling apart in the logs but not on the wire: the
 *                        Resolver was never run over this incident, or its call failed and
 *                        the diagnosis was kept anyway.
 * @param resolutionNotes how the incident was put right <em>by a human</em>, or null while it
 *                        is still open. Distinct from {@link #resolution}, which is a
 *                        proposal - see {@link Incident#resolution()}.
 * @param analyzedAt      when the analysis was run
 */
public record IncidentResponse(
        String id,
        LogAnalysis analysis,
        AgentResolution resolution,
        String resolutionNotes,
        Instant analyzedAt
) {

    public static IncidentResponse of(Incident incident) {
        return new IncidentResponse(
                incident.id(),
                incident.toAnalysis(),
                incident.resolution(),
                incident.resolutionNotes(),
                incident.analyzedAt());
    }
}
