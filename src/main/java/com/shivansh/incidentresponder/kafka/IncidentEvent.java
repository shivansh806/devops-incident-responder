package com.shivansh.incidentresponder.kafka;

import java.time.Instant;
import java.util.Set;

/**
 * One incident event as it arrives on the Kafka topic.
 * <p>
 * <b>This models only the fields this application uses, and that is the design.</b> Real
 * events come from alerting pipelines that add their own metadata - environment, region,
 * cluster, pod name, the id of the alert rule that fired - and those fields appear and change
 * without warning. Modelling them would mean a deployment every time an upstream team adds a
 * label. So unknown fields are ignored on purpose; see {@link IncidentEventParser} for how
 * that is made visible rather than silent.
 * <p>
 * The counterpart of {@link com.shivansh.incidentresponder.model.AnalyzeRequest} on the REST
 * side, and deliberately a separate type rather than a reused one. The two have different
 * pressures: a request body is written by a caller reading our docs, an event is emitted by a
 * system that has never heard of us.
 *
 * @param eventId    the producer's id for this event. Not used for deduplication yet - see the
 *                   at-least-once note in {@code docs/ingestion.md} - but logged, so a
 *                   duplicate is at least identifiable after the fact.
 * @param service    the service the event is about, when the producer knows it. Optional, and
 *                   <b>authoritative when present</b>: it is passed to the Analyzer as the
 *                   known service and overrides whatever the model would have inferred. A
 *                   producer that is not certain should leave it out rather than guess, since
 *                   asserting the wrong origin is worse than asking for inference.
 * @param detectedAt when the producer noticed the problem. Distinct from the incident's own
 *                   {@code firstOccurrence}, which the Analyzer reads out of the log text and
 *                   is usually earlier.
 * @param logs       the raw log dump, exactly as it would be copied out of a log viewer. The
 *                   only required field.
 */
public record IncidentEvent(
        String eventId,
        String service,
        Instant detectedAt,
        String logs
) {

    /**
     * The field names this record models. Anything else in the payload is an extra, which is
     * expected rather than wrong.
     * <p>
     * Maintained by hand because it has exactly one job - telling {@link IncidentEventParser}
     * which names to report as unmodelled - and deriving it reflectively would make a
     * mismatch a runtime surprise instead of a compile-time edit. Adding a component above
     * without adding it here means it gets reported as unknown on every event, which is loud
     * and immediately obvious.
     */
    public static final Set<String> KNOWN_FIELDS = Set.of("eventId", "service", "detectedAt", "logs");
}
