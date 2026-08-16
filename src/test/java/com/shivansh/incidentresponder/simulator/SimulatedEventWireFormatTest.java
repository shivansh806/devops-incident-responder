package com.shivansh.incidentresponder.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.shivansh.incidentresponder.kafka.IncidentEvent;
import com.shivansh.incidentresponder.kafka.IncidentEventParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The producer and the consumer agree about the wire format.
 * <p>
 * Offline, and the only check here that could not be made by reading one side. Both sides are
 * individually tested and could still disagree about how an {@code Instant} is written, or
 * about whether an omitted {@code service} arrives as absent or as null - a disagreement that
 * would surface as a dead-lettered event during a demo rather than in a build.
 */
class SimulatedEventWireFormatTest {

    private static final Instant NOW = Instant.parse("2026-08-16T10:23:45.123Z");

    @ParameterizedTest
    @EnumSource(IncidentScenario.class)
    void everySimulatedEventParsesBackIntoAnIncidentEvent(IncidentScenario scenario) throws IOException {
        IncidentSimulator.SimulatedEvent produced = IncidentSimulator.build(
                scenario, 1, NOW, new Random(11), IncidentEventParser.eventObjectMapper());

        IncidentEvent parsed = new IncidentEventParser().parse(produced.json());

        assertThat(parsed.eventId()).startsWith("EVT-");
        assertThat(parsed.logs()).isNotBlank().hasSize(produced.logChars());
        assertThat(parsed.detectedAt())
                .as("Instant survives the round trip as a timestamp, not as epoch seconds")
                .isEqualTo(NOW);

        if (scenario.declaresService()) {
            assertThat(parsed.service()).isEqualTo(produced.key());
        } else {
            assertThat(parsed.service())
                    .as("%s withholds the service so the Analyzer infers the origin", scenario)
                    .isNull();
        }
    }

    /**
     * {@code detectedAt} must be an ISO-8601 string on the wire, not a number. Jackson defaults
     * to epoch seconds; the consumer accepts both, so a regression here would be invisible
     * until something other than this application read the topic.
     */
    @Test
    void detectedAtIsWrittenAsAnIsoStringNotEpochSeconds() throws IOException {
        JsonNode node = readTree(IncidentScenario.SLOW_QUERY);

        assertThat(node.get("detectedAt").isTextual()).isTrue();
        assertThat(node.get("detectedAt").asText()).isEqualTo("2026-08-16T10:23:45.123Z");
    }

    /** The forward-compatibility case has to actually be on the wire for the consumer to ignore it. */
    @Test
    void eventsCarryFieldsTheConsumerDoesNotModel() throws IOException {
        JsonNode node = readTree(IncidentScenario.CACHE_UNAVAILABLE);

        List<String> unmodelled = new ArrayList<>();
        node.fieldNames().forEachRemaining(name -> {
            if (!IncidentEvent.KNOWN_FIELDS.contains(name)) {
                unmodelled.add(name);
            }
        });

        assertThat(unmodelled).contains("environment", "region", "cluster", "alertRuleId",
                "schemaVersion", "podName");
    }

    /** Every fifth event adds a field, so the consumer's per-shape reporting fires more than once. */
    @Test
    void theEventShapeChangesOccasionally() throws IOException {
        assertThat(readTree(IncidentScenario.SLOW_QUERY, 1).has("runbookUrl")).isFalse();
        assertThat(readTree(IncidentScenario.SLOW_QUERY, 5).has("runbookUrl")).isTrue();
    }

    private static JsonNode readTree(IncidentScenario scenario) throws IOException {
        return readTree(scenario, 1);
    }

    private static JsonNode readTree(IncidentScenario scenario, int sequence) throws IOException {
        return IncidentEventParser.eventObjectMapper().readTree(
                IncidentSimulator.build(scenario, sequence, NOW, new Random(5),
                        IncidentEventParser.eventObjectMapper()).json());
    }
}
