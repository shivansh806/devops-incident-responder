package com.shivansh.incidentresponder.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.List;

/**
 * Reports, does not assert. Answers one question that CLAUDE.md's week-3 note assumes the
 * answer to: <b>does a Kafka consumer inherit the unknown-property WARN handler?</b>
 * <p>
 * The handler is installed by {@code JacksonConfig} through a
 * {@code Jackson2ObjectMapperBuilderCustomizer}, which customises Spring Boot's
 * auto-configured ObjectMapper bean. Whether the consumer sees it depends entirely on which
 * mapper the consumer deserialises with - which is not the same question as whether the
 * handler exists.
 */
@SpringBootTest
class KafkaObjectMapperProbe {

    /** A stand-in for any event DTO: one known field, and the payload carries three it does not model. */
    record Probe(String eventId) {
    }

    private static final String PAYLOAD = """
            {"eventId":"EV-1","environment":"production","region":"ap-south-1","schemaVersion":2}
            """;

    @Autowired
    private ObjectMapper springObjectMapper;

    @Test
    void whichMappersCarryTheWarnHandler() throws Exception {
        System.out.println("\n================ unknown-property handler probe ================");
        System.out.printf("payload: %s%n", PAYLOAD.strip());
        System.out.println("unknown properties in it: environment, region, schemaVersion\n");

        report("Spring Boot's auto-configured ObjectMapper (used by @RestController)",
                () -> springObjectMapper.readValue(PAYLOAD, Probe.class));

        report("Spring Kafka JsonDeserializer, default construction",
                () -> new JsonDeserializer<>(Probe.class).deserialize("t", PAYLOAD.getBytes()));

        report("Spring Kafka JsonDeserializer handed the Spring ObjectMapper",
                () -> new JsonDeserializer<>(Probe.class, springObjectMapper, false)
                        .deserialize("t", PAYLOAD.getBytes()));

        report("A plain new ObjectMapper()",
                () -> new ObjectMapper().readValue(PAYLOAD, Probe.class));

        System.out.println("===============================================================\n");
    }

    private static void report(String label, ThrowingRunnable action) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        String outcome;
        try {
            action.run();
            outcome = "parsed";
        } catch (Exception e) {
            outcome = "FAILED: " + e.getClass().getSimpleName();
        } finally {
            root.detachAppender(appender);
        }

        List<ILoggingEvent> warns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("unknown JSON property"))
                .toList();

        System.out.printf("  %-58s  %-8s  warn lines: %d%n", label, outcome, warns.size());
        warns.forEach(w -> System.out.printf("      -> %s%n", w.getFormattedMessage()));
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
