package com.shivansh.incidentresponder.agent;

import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Severity;

import java.util.List;

/**
 * Raw, unvalidated shape the model is asked to produce. LangChain4j derives the JSON
 * schema it sends to the LLM from these fields, so this record is effectively part of the
 * prompt - field names are visible to the model and act as hints.
 * <p>
 * Two reasons this is not just {@link com.shivansh.incidentresponder.model.LogAnalysis}:
 * <ol>
 *   <li>{@code firstOccurrence} is a {@link String}. LangChain4j's schema generator has no
 *       mapping for {@code Instant}, so it would treat it as a nested object and ask the
 *       model for its internal {@code seconds}/{@code nanos} fields.</li>
 *   <li>Model output is untrusted input. Confidence can come back as 1.5, severity can be
 *       null, evidence can be null. Keeping a separate type forces every value through
 *       {@code AnalyzerService}'s normalisation before it reaches the API.</li>
 * </ol>
 */
public record AnalyzerOutput(
        ErrorType errorType,
        String affectedService,
        Severity severity,
        String firstOccurrence,
        List<String> keyEvidence,
        Double confidence
) {
}
