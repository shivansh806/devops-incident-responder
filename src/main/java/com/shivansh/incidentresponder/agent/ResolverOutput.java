package com.shivansh.incidentresponder.agent;

import java.util.List;

/**
 * Raw, unvalidated shape the Resolver is asked to produce, standing in the same relation to
 * {@link com.shivansh.incidentresponder.model.AgentResolution} as {@link AnalyzerOutput}
 * does to {@code LogAnalysis}, and for the same two reasons.
 * <p>
 * Model output is untrusted input: {@code confidence} can arrive as 1.4, any list can be
 * null or hold nulls, and {@code similarIncidents} can name an incident that was never
 * supplied. Keeping a separate record forces all of it through {@code ResolverService}
 * before it reaches the API.
 * <p>
 * The field names are visible to the model - LangChain4j derives the format instructions
 * from this record - so they are part of the prompt and should read as instructions.
 * {@code suggestedActions} says more about what is wanted than {@code actions} would.
 * <p>
 * Everything is a boxed type or a list on purpose. A primitive {@code double} would
 * deserialise a missing confidence as 0.0, which is indistinguishable from the model
 * genuinely reporting no confidence at all; {@code Double} keeps "omitted" separable from
 * "zero" so the normalisation step can log the difference.
 */
public record ResolverOutput(
        String rootCause,
        List<String> suggestedActions,
        List<String> similarIncidents,
        Double confidence
) {
}
