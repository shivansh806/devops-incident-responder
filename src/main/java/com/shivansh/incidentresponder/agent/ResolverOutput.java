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
 * <p>
 * <b>{@code decidingEvidence} is declared first, and the order is the whole point.</b>
 * LangChain4j lists the schema fields in declaration order and appends that schema to the end
 * of the user message, so it is the last thing the model reads before generating - and
 * generation is autoregressive, so the first field declared is the first field written.
 * Before the fix, {@code rootCause} held that slot: the model had to commit to a conclusion
 * with no room to work out which precedent applied, and {@code docs/resolver.md} measured what
 * that cost. Putting the discriminator first converts steps 1 and 2 of the prompt's procedure
 * from instructions 11,000 characters upstream into the next thing the model owes.
 * <p>
 * Reordering these components is therefore a behavioural change, not a cosmetic one. Do not
 * tidy them into a different order.
 */
public record ResolverOutput(
        String decidingEvidence,
        String rootCause,
        List<String> suggestedActions,
        List<String> similarIncidents,
        Double confidence
) {
}
