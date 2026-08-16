package com.shivansh.incidentresponder.kafka;

/**
 * Thrown when a Kafka message cannot be read as an {@link IncidentEvent} at all - the payload
 * is not JSON, or it is JSON of the wrong shape.
 * <p>
 * <b>Its only job is to be non-retryable.</b> A message that failed to parse will fail to
 * parse identically on every redelivery, so retrying costs a blocked partition and buys
 * nothing. {@code KafkaConsumerConfig} classifies this exception as terminal, which sends the
 * message straight to the dead-letter topic on the first attempt.
 * <p>
 * Distinct from {@link com.shivansh.incidentresponder.service.AnalysisFailedException}, which
 * means the message was fine and the model call was not - that one <em>is</em> worth one
 * retry.
 */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }

    public MalformedEventException(String message) {
        super(message);
    }
}
