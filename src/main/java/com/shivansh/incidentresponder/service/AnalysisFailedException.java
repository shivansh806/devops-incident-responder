package com.shivansh.incidentresponder.service;

/**
 * Thrown when the Analyzer Agent could not produce a diagnosis - the model call failed,
 * timed out, or the reply could not be parsed into the expected structure.
 * <p>
 * Distinct from {@link IllegalArgumentException}, which means the caller sent bad input.
 * The two map to different HTTP statuses.
 */
public class AnalysisFailedException extends RuntimeException {

    public AnalysisFailedException(String message) {
        super(message);
    }

    public AnalysisFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
