package com.shivansh.incidentresponder.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.time.Duration;

/**
 * What happens to an incident event that fails to process.
 *
 * <h2>The offset contract</h2>
 * Offsets are committed by the container, not by Kafka's auto-commit, which Spring Boot
 * disables. A listener that returns normally has its offset committed and the event is never
 * redelivered. A listener that throws is redelivered <em>on the same consumer thread</em>,
 * with the offset held back, until the back-off below is exhausted - and then the recoverer
 * runs and the offset is committed anyway.
 * <p>
 * <b>That last clause is why the dead-letter topic exists.</b> "Giving up" means committing
 * past the event, so without a recoverer that keeps it, giving up is silent data loss.
 *
 * <h2>Why the back-off is one retry and not the default ten</h2>
 * {@link DefaultErrorHandler}'s default is ten attempts with no delay. Retries block the
 * consumer thread, so every attempt counts against {@code max.poll.interval.ms} - and an
 * event here is two LLM calls against a 60 second timeout, roughly 125 seconds in the worst
 * case. Ten attempts is about 21 minutes against a five minute default: the broker would
 * evict this consumer mid-event, rebalance, and redeliver work that was already billed. The
 * paired settings in {@code application.yml} - {@code max-poll-records: 1} and a raised
 * {@code max.poll.interval.ms} - are the other half of this and are not decoration.
 * <p>
 * One retry is also what {@code AnalyzerBaselineTest} settled empirically. A per-minute rate
 * limit clears after about a minute, so one delayed retry recovers it; a per-day limit will
 * refuse every attempt no matter how many are allowed, so further retries only burn wall
 * clock. Worst case here is 125 + 60 + 125 = 310 seconds, inside the 600 second poll interval
 * with room to spare.
 *
 * <h2>What is retried and what is not</h2>
 * <ul>
 *   <li>{@link MalformedEventException} and {@link IllegalArgumentException} - <b>never
 *       retried.</b> Bad JSON parses identically on every attempt, and an oversized log dump
 *       is oversized on every attempt. Retrying buys nothing and blocks the partition.</li>
 *   <li>{@code AnalysisFailedException} - <b>retried once.</b> A 429 or a timeout is the
 *       failure that a delay can genuinely fix.</li>
 *   <li>A failed Resolver call never arrives here at all: {@code ResolverService} catches it
 *       and returns null, and the incident is stored with its diagnosis and no
 *       recommendation. The only LLM failure that reaches this handler is the Analyzer's.</li>
 * </ul>
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    /**
     * Long enough for a per-minute Groq rate limit to clear. Deliberately not longer: this
     * delay is spent holding the consumer thread, so it is paid out of the poll interval
     * budget described above.
     */
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(60);

    /** Attempts after the first. One, for the reasons in the class javadoc. */
    private static final long RETRIES = 1L;

    /**
     * Spring Boot wires a single {@code CommonErrorHandler} bean into the auto-configured
     * listener container factory, so declaring this replaces the default without needing a
     * hand-built factory.
     */
    @Bean
    public DefaultErrorHandler incidentEventErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // Publishes to "<original topic>.DLT" on the same partition number, carrying the
        // original message plus the exception in headers. The broker's auto-create handles
        // the topic; it inherits the single partition, which matches the source.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        DefaultErrorHandler handler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(RETRY_BACKOFF.toMillis(), RETRIES));

        // The classifier walks the cause chain, so these match even though Spring wraps the
        // listener's exception in a ListenerExecutionFailedException.
        handler.addNotRetryableExceptions(MalformedEventException.class, IllegalArgumentException.class);

        handler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn("Delivery attempt {} failed for offset {} on {}-{}: {}",
                        deliveryAttempt, record.offset(), record.topic(), record.partition(),
                        exception.getMessage()));

        return handler;
    }
}
