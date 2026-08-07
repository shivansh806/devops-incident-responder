package com.shivansh.incidentresponder.model;

/**
 * Request body for {@code POST /api/analyze}.
 *
 * @param logs        raw application log dump, exactly as it would be copied out of a log viewer
 * @param serviceName optional. The service these logs came from, when the caller already
 *                    knows it - a Kafka incident event carries it, a hand-pasted dump
 *                    usually does not. When supplied it is authoritative: it is copied
 *                    straight into the analysis and the model is not asked to infer it.
 *                    <p>
 *                    Leave it out for a dump spanning several services. Supplying a name
 *                    asserts that it <em>is</em> the origin of the failure, so it will
 *                    override the model even if the logs show the failure originating
 *                    somewhere upstream.
 */
public record AnalyzeRequest(String logs, String serviceName) {
}
