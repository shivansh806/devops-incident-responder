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
 * <b>Every event is two Groq calls</b>, about <b>9,800 tokens</b> on
 * {@code openai/gpt-oss-120b}: an Analyzer call measured at 5,121 (input 4,109, output 1,012)
 * plus a Resolver call of roughly 4,700. Against the free tier's 100,000 tokens per day that
 * is about <b>10 events for the whole day</b>, and a full eight-scenario run is ~78,000 -
 * most of it. The default count is therefore 3, not 8.
 * <p>
 * These figures replace the ~6,000 per event measured on {@code llama-3.3-70b-versatile},
 * which Groq retired. The rise is mostly input: gpt-oss tokenises the same prompt to more
 * tokens, and its reasoning is billed as completion.
 * <p>
 * The interval defaults to <b>120 seconds</b>, and <b>not</b> because of tokens per event.
 * That calculation gives 62-75s - 8,000 refilling at ~133 a second against an event of 8,308
 * measured or 9,800 estimated - and the live run on 2026-08-20 collided at <em>90</em>s, well
 * above both. Per-event cost is not the binding constraint.
 * <p>
 * <b>What binds is Groq's 60-second window plus this pipeline's own retry.</b> A cold event's
 * Resolver is refused, waits 15s in {@code ResolverService} and retries, so the event's last
 * billed call lands ~25-35s after the event began. The next event is clear only once that call
 * has aged out of the trailing 60 seconds: <b>85-95s</b>. 90s sat inside that band, which is
 * what "3 of 4 events resolved, 1 degraded" looks like. 120s puts ~30s of margin on it, and
 * costs nothing - the daily budget already caps this at ~10 events, so the interval was never
 * the limit on how much can run in a day.
 * <p>
 * <b>The interval cannot fix the within-event burst.</b> The two calls run back to back
 * inside {@code IncidentService.analyzeAndRecord}, and 5,121 + 4,700 exceeds the 8,000 bucket
 * however long the pipeline has been idle. The Resolver call is the one that 429s, and
 * {@code ResolverService} absorbs it as a null resolution - so the symptom is incidents
 * stored with a diagnosis and no recommendation, on a free-tier key. The Redis cache is what
 * fixes it; see {@code docs/ingestion.md}.
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

    /**
     * <b>An upper bound, and only half of it is measured.</b> The Analyzer's 5,121 comes from
     * {@code ModelCandidateProbe}; the Resolver's ~4,700 was an estimate from the rendered
     * prompt and is the part to distrust. Three events actually measured against the live
     * limiter cost <b>8,133 / 8,300 / 8,308</b>, with the Resolver at <b>~3,600</b> - see the
     * table in {@code docs/caching.md}.
     * <p>
     * Left at 9,800 deliberately. It feeds a cost warning, where over-estimating is the safe
     * direction, and nothing derives behaviour from it - the interval default does <em>not</em>,
     * despite what the old comment here claimed. Update it when the model changes.
     */
    private static final int TOKENS_PER_EVENT = 9_800;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper mapper = IncidentEventParser.eventObjectMapper();
    private final String topic;
    private final int count;
    private final Duration interval;
    private final Duration initialDelay;

    public IncidentSimulator(KafkaTemplate<String, String> kafkaTemplate,
                             @Value("${incident.kafka.topic}") String topic,
                             @Value("${incident.simulator.count}") int count,
                             @Value("${incident.simulator.interval}") Duration interval,
                             @Value("${incident.simulator.initial-delay:0s}") Duration initialDelay) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.count = count;
        this.interval = interval;
        this.initialDelay = initialDelay;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread emitter = new Thread(this::emitAll, "incident-simulator");
        emitter.setDaemon(true);
        emitter.start();
    }

    private void emitAll() {
        // Hold the first event back so a dashboard can be connected before it lands.
        //
        // This runs as an ApplicationRunner, so without a delay the first event is produced,
        // consumed and broadcast before anyone can open the page - and the broadcast then goes
        // to zero sessions. The incident is not lost: a client connecting afterwards still sees
        // it in the connect backlog, tagged `history` rather than `incident`. So this is not a
        // correctness problem, it is a demo one, and it looks exactly like a broken live push.
        //
        // Default 0s, so nothing changes for an ordinary run.
        if (!initialDelay.isZero() && !initialDelay.isNegative()) {
            log.info("Simulator holding the first event for {}s - connect a dashboard now",
                    initialDelay.toSeconds());
            try {
                Thread.sleep(initialDelay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Simulator interrupted during its initial delay, producing nothing");
                return;
            }
        }

        List<IncidentScenario> order = new ArrayList<>(List.of(IncidentScenario.values()));
        Collections.shuffle(order);

        // "when consumed", not "cost": producing is free, and the whole point of
        // incident.kafka.consumer-enabled=false is to fill a topic without paying for it.
        // An unconditional "estimated cost" line would be wrong on exactly that run.
        log.info("Simulator starting: {} event(s) to '{}', one every {}s. ~{} Groq tokens "
                        + "against a 100,000/day budget WHEN CONSUMED - producing costs nothing",
                count, topic, interval.toSeconds(), count * TOKENS_PER_EVENT);

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
