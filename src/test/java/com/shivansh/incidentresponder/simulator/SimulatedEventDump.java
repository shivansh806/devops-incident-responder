package com.shivansh.incidentresponder.simulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shivansh.incidentresponder.kafka.IncidentEvent;
import com.shivansh.incidentresponder.kafka.IncidentEventParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Reports, does not assert. Prints what the simulator would put on the topic, and writes one
 * full event to {@code target/simulated-events/} so it can be produced by hand with the
 * console tools.
 * <p>
 * Named {@code Dump} rather than {@code Test} so surefire's naming convention leaves it out of
 * the build, the same arrangement as {@code ResolverPromptDump} and {@code RetrievalProbe}.
 * Offline: no broker, no Groq call, no quota.
 */
class SimulatedEventDump {

    private static final Instant NOW = Instant.parse("2026-08-16T10:23:45.123Z");
    private static final Path OUT = Path.of("target", "simulated-events");

    @Test
    void dumpOneEventPerScenario() throws IOException {
        ObjectMapper mapper = IncidentEventParser.eventObjectMapper();
        Files.createDirectories(OUT);

        System.out.println("\n=================== simulated incident events ===================");
        System.out.printf("  %-28s %-22s %-8s %-9s %s%n",
                "SCENARIO", "SERVICE", "LOGCHARS", "SERVICE?", "UNMODELLED FIELDS");

        int sequence = 1;
        for (IncidentScenario scenario : IncidentScenario.values()) {
            IncidentSimulator.SimulatedEvent event =
                    IncidentSimulator.build(scenario, sequence, NOW, new Random(sequence), mapper);
            JsonNode node = mapper.readTree(event.json());

            List<String> unmodelled = new ArrayList<>();
            node.fieldNames().forEachRemaining(name -> {
                if (!IncidentEvent.KNOWN_FIELDS.contains(name)) {
                    unmodelled.add(name);
                }
            });

            System.out.printf("  %-28s %-22s %-8d %-9s %s%n",
                    scenario, event.key(), event.logChars(),
                    scenario.declaresService() ? "sent" : "omitted",
                    String.join(",", unmodelled));

            // Trailing newline matters: these files are meant to be piped into
            // kafka-console-producer.sh, which splits messages on newlines. Without it,
            // concatenating two files produces one message rather than two.
            Files.writeString(OUT.resolve(scenario.name().toLowerCase() + ".json"),
                    event.json() + System.lineSeparator(), StandardCharsets.UTF_8);
            sequence++;
        }
        System.out.println("\n  written to " + OUT.toAbsolutePath());
        System.out.println("=================================================================\n");

        // One rendered log dump in full, so the demo content can be read rather than trusted.
        IncidentSimulator.SimulatedEvent sample =
                IncidentSimulator.build(IncidentScenario.CONNECTION_POOL_EXHAUSTION, 1, NOW, new Random(4), mapper);
        System.out.println("---------- CONNECTION_POOL_EXHAUSTION, logs field rendered ----------");
        System.out.println(mapper.readTree(sample.json()).get("logs").asText());
        System.out.println("--------------------------------------------------------------------\n");
    }
}
