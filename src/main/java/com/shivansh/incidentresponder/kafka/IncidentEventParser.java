package com.shivansh.incidentresponder.kafka;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turns a raw Kafka message into an {@link IncidentEvent}, and reports what it had to ignore.
 *
 * <h2>Why this does not use the application's ObjectMapper</h2>
 * {@code JacksonConfig} installs a {@code DeserializationProblemHandler} that logs a WARN for
 * every unknown property, so that a mistyped field in a REST request body does not fail
 * silently. On this path an unknown property is not a typo - it is the forward-compatibility
 * case working as intended, and warning about it would devalue the signal the REST path needs.
 * The mapper here is therefore built locally rather than injected.
 * <p>
 * <b>Building it locally is also the point, not an accident.</b> Measured with
 * {@code KafkaObjectMapperProbe}: Spring Kafka's own {@code JsonDeserializer} does not inherit
 * the handler, but a {@code JsonDeserializer} handed the application mapper does - so
 * "does the consumer warn?" depends entirely on a wiring choice that is easy to make by
 * accident. A private field cannot be wired wrongly.
 * <p>
 * <b>And leniency is configured, not inherited.</b> The same probe showed a bare
 * {@code new ObjectMapper()} rejecting this application's own events with
 * {@code UnrecognizedPropertyException}: {@code FAIL_ON_UNKNOWN_PROPERTIES} defaults to
 * <em>on</em> in Jackson and is off in this app only because Spring Boot turns it off. A
 * dedicated mapper that forgot the line below would fail every event carrying an upstream
 * field - the exact opposite of what a dedicated mapper was for.
 *
 * <h2>Keeping the drops visible without one line per event</h2>
 * Silently discarding fields is how a producer's rename goes unnoticed for a week. But the
 * REST-side approach - one WARN per unknown property per message - reports the same handful of
 * field names forever. So each distinct <em>set</em> of unmodelled field names is reported
 * once, at INFO. The cost is O(distinct event shapes) rather than O(events), and it says the
 * more useful thing: not "this message had an extra field" but "events on this topic carry
 * environment, region, schemaVersion".
 */
@Slf4j
@Component
public class IncidentEventParser {

    /**
     * Upper bound on remembered shapes, so a producer emitting a per-message unique field
     * cannot grow this set without limit. Reaching it is itself worth a warning: it means the
     * payload shape is not stable, which is a different problem from an added field.
     */
    private static final int MAX_REPORTED_SHAPES = 50;

    private final ObjectMapper mapper = eventObjectMapper();
    private final Set<String> reportedShapes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean capReported = new AtomicBoolean();

    /**
     * The mapper for the incident-event wire format, read and write.
     * <p>
     * <b>One recipe, both sides</b> - {@code IncidentSimulator} serialises with this and this
     * parser deserialises with it, the same arrangement {@code IncidentEmbeddingText} uses for
     * the two halves of the embedding text. A producer and consumer that configure their own
     * mappers separately disagree about date format eventually, and the failure surfaces as a
     * parse error on the consumer with the producer looking innocent.
     * <p>
     * Exposed as a static factory rather than a Spring bean on purpose: an
     * {@code ObjectMapper} bean is exactly the thing that gets autowired into the wrong place,
     * which is the mistake the class javadoc describes.
     */
    public static ObjectMapper eventObjectMapper() {
        return JsonMapper.builder()
                // Not optional. See the class javadoc - Jackson defaults this to on, and every
                // event carrying an upstream field would be rejected without it.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // detectedAt is an Instant; without this it deserialises as a number of
                // seconds or fails outright depending on the shape it arrives in.
                .addModule(new JavaTimeModule())
                .disable(DeserializationFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
                // Writes detectedAt as "2026-08-16T10:23:45.123Z" rather than 1755340000.123.
                // The read side accepts both, so without this the wire format would quietly
                // depend on which side was written first.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    /**
     * @throws MalformedEventException if the payload is not a JSON object, cannot be bound to
     *                                 {@link IncidentEvent}, or carries no log text. All three
     *                                 are permanent conditions - see that exception's javadoc
     *                                 for why they must not be retried.
     */
    public IncidentEvent parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new MalformedEventException("Kafka message has no payload");
        }

        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (Exception e) {
            throw new MalformedEventException("Kafka message is not valid JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new MalformedEventException("Kafka message is not a JSON object");
        }

        IncidentEvent event;
        try {
            event = mapper.treeToValue(root, IncidentEvent.class);
        } catch (Exception e) {
            throw new MalformedEventException("Kafka message is not an incident event: " + e.getMessage(), e);
        }

        // After binding, not before: a payload that fails to bind has a different problem, and
        // reporting its field names would be noise on top of an error.
        reportUnmodelledFields(root);

        if (event.logs() == null || event.logs().isBlank()) {
            throw new MalformedEventException(
                    "Incident event %s carries no 'logs' field".formatted(event.eventId()));
        }
        return event;
    }

    private void reportUnmodelledFields(JsonNode root) {
        List<String> unmodelled = new ArrayList<>();
        for (Iterator<String> names = root.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!IncidentEvent.KNOWN_FIELDS.contains(name)) {
                unmodelled.add(name);
            }
        }
        if (unmodelled.isEmpty()) {
            return;
        }

        // Sorted so that field order in the payload does not make the same shape look new.
        unmodelled.sort(String::compareTo);
        String shape = String.join(", ", unmodelled);

        if (reportedShapes.contains(shape)) {
            return;
        }
        if (reportedShapes.size() >= MAX_REPORTED_SHAPES) {
            // Not added to the set - that is what keeps the cap a cap. The flag is what stops
            // this warning becoming the per-event line the whole design avoids.
            if (capReported.compareAndSet(false, true)) {
                log.warn("Stopped reporting unmodelled event fields after {} distinct shapes - "
                        + "the payload shape is not stable, which is a different problem from an added field",
                        MAX_REPORTED_SHAPES);
            }
            return;
        }
        if (reportedShapes.add(shape)) {
            log.info("Incident events carry {} field(s) this application does not model, ignoring: {}. "
                    + "This is expected - reported once per distinct shape, not once per event",
                    unmodelled.size(), shape);
        }
    }
}
