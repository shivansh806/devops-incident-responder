package com.shivansh.incidentresponder.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Offline. No broker, no Spring context, no model call. */
class IncidentEventParserTest {

    private IncidentEventParser parser;
    private ListAppender<ILoggingEvent> logs;
    private Logger parserLogger;

    @BeforeEach
    void setUp() {
        parser = new IncidentEventParser();
        parserLogger = (Logger) LoggerFactory.getLogger(IncidentEventParser.class);
        logs = new ListAppender<>();
        logs.start();
        parserLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        parserLogger.detachAppender(logs);
    }

    @Test
    void readsTheFieldsItModels() {
        IncidentEvent event = parser.parse("""
                {"eventId":"EVT-1","service":"payment-service",
                 "detectedAt":"2026-08-16T10:23:45.123Z","logs":"ERROR pool exhausted"}
                """);

        assertThat(event.eventId()).isEqualTo("EVT-1");
        assertThat(event.service()).isEqualTo("payment-service");
        assertThat(event.detectedAt()).isEqualTo(Instant.parse("2026-08-16T10:23:45.123Z"));
        assertThat(event.logs()).isEqualTo("ERROR pool exhausted");
    }

    /**
     * The forward-compatibility case, and the reason this parser exists. A bare
     * {@code new ObjectMapper()} throws {@code UnrecognizedPropertyException} on this input -
     * measured in {@code KafkaObjectMapperProbe}.
     */
    @Test
    void ignoresFieldsItDoesNotModel() {
        IncidentEvent event = parser.parse("""
                {"eventId":"EVT-2","logs":"ERROR disk full","environment":"production",
                 "region":"ap-south-1","schemaVersion":2,"nested":{"pod":"x-1","labels":["a","b"]}}
                """);

        assertThat(event.eventId()).isEqualTo("EVT-2");
        assertThat(event.logs()).isEqualTo("ERROR disk full");
    }

    @Test
    void reportsEachDistinctShapeOnceRatherThanEachEvent() {
        String shapeA = """
                {"eventId":"EVT-%d","logs":"ERROR x","environment":"production","region":"ap-south-1"}
                """;
        for (int i = 0; i < 5; i++) {
            parser.parse(shapeA.formatted(i));
        }

        assertThat(reportLines())
                .as("five events, one shape")
                .hasSize(1)
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("environment, region");

        // A genuinely new shape is worth a second line; more of the first one is not.
        parser.parse("""
                {"eventId":"EVT-9","logs":"ERROR y","environment":"production","region":"ap-south-1",
                 "runbookUrl":"https://runbooks.internal/x"}
                """);

        assertThat(reportLines()).hasSize(2);
        assertThat(reportLines().get(1)).contains("environment, region, runbookUrl");
    }

    /** Field order in the payload is not a new shape. */
    @Test
    void treatsReorderedFieldsAsTheSameShape() {
        parser.parse("""
                {"logs":"ERROR x","environment":"production","region":"ap-south-1"}
                """);
        parser.parse("""
                {"logs":"ERROR x","region":"ap-south-1","environment":"production"}
                """);

        assertThat(reportLines()).hasSize(1);
    }

    @Test
    void saysNothingWhenEveryFieldIsModelled() {
        parser.parse("""
                {"eventId":"EVT-3","service":"order-service","logs":"ERROR x"}
                """);

        assertThat(reportLines()).isEmpty();
    }

    /**
     * All four are permanent conditions, and all four must surface as the exception
     * {@code KafkaConsumerConfig} classifies as non-retryable. A retryable classification here
     * would block the partition for a minute per bad message and change nothing.
     */
    @Test
    void rejectsMessagesThatCanNeverBeProcessed() {
        assertThatThrownBy(() -> parser.parse("not json at all"))
                .isInstanceOf(MalformedEventException.class);

        assertThatThrownBy(() -> parser.parse("[1,2,3]"))
                .isInstanceOf(MalformedEventException.class)
                .hasMessageContaining("not a JSON object");

        assertThatThrownBy(() -> parser.parse("{\"eventId\":\"EVT-4\"}"))
                .isInstanceOf(MalformedEventException.class)
                .hasMessageContaining("no 'logs' field");

        assertThatThrownBy(() -> parser.parse("{\"eventId\":\"EVT-5\",\"logs\":\"   \"}"))
                .isInstanceOf(MalformedEventException.class)
                .hasMessageContaining("no 'logs' field");

        assertThatThrownBy(() -> parser.parse("  "))
                .isInstanceOf(MalformedEventException.class);
    }

    /**
     * A missing service is not an error: it means the producer is not asserting the origin and
     * the Analyzer should infer it. Distinct from the producer claiming "unknown".
     */
    @Test
    void acceptsAnEventWithNoServiceField() {
        assertThat(parser.parse("{\"eventId\":\"EVT-6\",\"logs\":\"ERROR x\"}").service()).isNull();
    }

    private List<String> reportLines() {
        return logs.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("does not model"))
                .toList();
    }
}
