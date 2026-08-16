package com.shivansh.incidentresponder.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shivansh.incidentresponder.kafka.IncidentEventParser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Produces realistic incident events onto the Kafka topic, so the pipeline can be demonstrated
 * without waiting for something to actually break.
 *
 * <h2>Running it</h2>
 * Gated behind the {@code simulator} profile, exactly like {@code IncidentSeeder} is behind
 * {@code seed}, so it never fires on an ordinary boot. Put {@code simulator} in IntelliJ's
 * <em>Active profiles</em> and start the app.
 * <p>
 * It emits on its own thread rather than holding the {@code ApplicationRunner} callback,
 * because it deliberately runs for minutes - see the pacing below - and blocking startup for
 * that long would be indistinguishable from a hang.
 *
 * <h2>What it costs, and why the defaults are small</h2>
 * <b>Every event is two Groq calls</b>, roughly 6,000 tokens: an Analyzer call of 2,500-3,400
 * plus a Resolver call of about 3,500. Against the free tier's 100,000 tokens per day that is
 * about <b>16 events for the whole day</b>, and a full eight-scenario run is roughly half of
 * it. The default count is therefore 3, not 8.
 * <p>
 * The interval defaults to 40 seconds for a different limit: 12,000 tokens per <em>minute</em>.
 * At ~6,000 tokens an event, anything faster than about 35 seconds walks into a 429 that has
 * nothing to do with the daily budget. This is the same arithmetic that made
 * {@code AnalyzerBaselineTest} pace at 25 seconds for a one-call sample.
 * <p>
 * Producing costs nothing by itself. To fill a topic for free and look at the events before
 * paying for any of them, run with {@code incident.kafka.consumer-enabled=false}.
 *
 * <h2>Varied by construction, not by luck</h2>
 * Scenarios are shuffled once and then taken in order, so N events are N <em>different</em>
 * failure classes up to the scenario count. Drawing at random would repeat: with eight
 * scenarios and eight draws, a repeat is near-certain, and a demo that shows the same failure
 * twice while claiming variety is worse than one that shows three.
 */
@Slf4j
@Component
@Profile("simulator")
public class IncidentSimulator implements ApplicationRunner {

    /**
     * Extra fields the application does not model, added to every event. These are the
     * forward-compatibility case made concrete - they are what a real alerting pipeline
     * decorates an event with, and the consumer must ignore them without complaint.
     */
    private static final Map<String, Object> ALERTING_METADATA = Map.of(
            "environment", "production",
            "region", "ap-south-1",
            "cluster", "prod-blr-1",
            "alertRuleId", "AR-2291",
            "schemaVersion", 2);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper = IncidentEventParser.eventObjectMapper();
    private final String topic;
    private final int count;
    private final Duration interval;

    public IncidentSimulator(KafkaTemplate<String, String> kafkaTemplate,
                             @Value("${incident.kafka.topic}") String topic,
                             @Value("${incident.simulator.count}") int count,
                             @Value("${incident.simulator.interval}") Duration interval) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.count = count;
        this.interval = interval;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread emitter = new Thread(this::emitAll, "incident-simulator");
        emitter.setDaemon(true);
        emitter.start();
    }

    private void emitAll() {
        List<IncidentScenario> order = new ArrayList<>(List.of(IncidentScenario.values()));
        Collections.shuffle(order);

        log.info("Simulator starting: {} event(s) to '{}', one every {}s. Estimated cost ~{} Groq tokens "
                        + "against a 100,000/day budget",
                count, topic, interval.toSeconds(), count * 6_000);

        for (int i = 0; i < count; i++) {
            IncidentScenario scenario = order.get(i % order.size());
            try {
                emit(scenario, i + 1);
            } catch (Exception e) {
                // One bad template must not end the run - the remaining scenarios are still
                // worth producing, and the same reasoning applies here as to AnalyzerBaselineTest
                // reporting CALL FAILED rather than aborting.
                log.error("Simulator failed to emit {} ({} of {})", scenario, i + 1, count, e);
            }

            if (i < count - 1) {
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("Simulator interrupted after {} event(s)", i + 1);
                    return;
                }
            }
        }
        log.info("Simulator finished: {} event(s) produced to '{}'", count, topic);
    }

    private void emit(IncidentScenario scenario, int sequence) throws IOException {
        SimulatedEvent event = build(scenario, sequence, Instant.now(), ThreadLocalRandom.current(), mapper);

        // Keyed by the origin service so that, if this topic ever gets more than one partition,
        // events about one service stay ordered relative to each other.
        kafkaTemplate.send(topic, event.key(), event.json());

        log.info("Simulated event {} of {}: {} on {} ({} chars of logs, service field {})",
                sequence, count, scenario, event.key(), event.logChars(),
                scenario.declaresService() ? "sent" : "omitted so the model must infer it");
    }

    /**
     * One event, exactly as it goes on the wire.
     *
     * @param key       Kafka message key - the origin service
     * @param json      the serialised payload
     * @param logChars  size of the rendered log dump, for logging
     */
    record SimulatedEvent(String key, String json, int logChars) {
    }

    /**
     * Separated from {@link #emit} and given its inputs rather than reading the clock and the
     * random source itself, so a test can build the identical payload and feed it through
     * {@code IncidentEventParser}. The producer and the consumer agreeing about the wire format
     * is the one thing here that cannot be checked by reading either side alone.
     */
    static SimulatedEvent build(IncidentScenario scenario, int sequence, Instant now,
                                RandomGenerator random, ObjectMapper mapper) throws IOException {
        String service = pick(scenario.services(), random);
        String caller = pick(scenario.callers(), random);
        // Two minutes back, so the log's own last line sits a little before "now" - which is
        // what detectedAt then means. A log timestamped in the future reads as broken.
        Instant base = now.minusSeconds(120);

        String logs = IncidentLogRenderer.render(loadTemplate(scenario), base, service, caller, random);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", "EVT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        // Omitted entirely for the scenarios whose logs name the victim rather than the origin.
        // See IncidentScenario.declaresService - a null here would be a claim of "unknown",
        // while absence is the producer declining to speak.
        if (scenario.declaresService()) {
            payload.put("service", service);
        }
        payload.put("detectedAt", now);
        payload.put("logs", logs);
        payload.putAll(ALERTING_METADATA);
        payload.put("podName", "%s-%s".formatted(service, UUID.randomUUID().toString().substring(0, 5)));
        if (sequence % 5 == 0) {
            // A field that appears only sometimes, so the consumer's "report each distinct
            // shape once" behaviour can be seen firing a second time rather than assumed.
            payload.put("runbookUrl", "https://runbooks.internal/%s".formatted(scenario.name().toLowerCase()));
        }

        return new SimulatedEvent(service, mapper.writeValueAsString(payload), logs.length());
    }

    private static String loadTemplate(IncidentScenario scenario) throws IOException {
        ClassPathResource resource = new ClassPathResource("simulator/" + scenario.resource());
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static <T> T pick(List<T> options, RandomGenerator random) {
        return options.get(random.nextInt(options.size()));
    }
}
