package com.shivansh.incidentresponder.model;

import java.time.Instant;
import java.util.List;

/**
 * The Analyzer Agent's diagnosis: what happened, where, and how sure we are.
 * <p>
 * Deliberately contains no remediation advice - suggesting fixes is the Resolver Agent's
 * job. Keeping the two separate is what makes this a multi-agent system rather than one
 * overloaded prompt.
 * <p>
 * This is the shape the API returns and the shape the rest of the application works with.
 * It is <em>not</em> the shape the model produces; see
 * {@link com.shivansh.incidentresponder.agent.AnalyzerOutput}.
 *
 * @param errorType       stable PascalCase identifier for the failure class, e.g. {@code ConnectionPoolExhausted}
 * @param affectedService service where the failure originated, or {@code "unknown"}
 * @param severity        blast radius of the incident
 * @param firstOccurrence earliest log line belonging to this failure; null when the logs carry no parseable timestamp
 * @param keyEvidence     log lines quoted verbatim from the input that support the diagnosis
 * @param confidence      calibrated 0.0-1.0 score for how well the evidence supports the diagnosis
 */
public record LogAnalysis(
        String errorType,
        String affectedService,
        Severity severity,
        Instant firstOccurrence,
        List<String> keyEvidence,
        double confidence
) {
}
