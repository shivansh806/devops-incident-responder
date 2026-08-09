package com.shivansh.incidentresponder.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the two conventions {@link ErrorType} has to straddle and the leniency that keeps
 * them from colliding. All offline - no API key, no model call.
 */
class ErrorTypeTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serialisesAsPascalCaseToKeepTheApiContract() throws Exception {
        // The brief's example JSON and AnalyzeControllerTest both assume this wire format.
        assertThat(objectMapper.writeValueAsString(ErrorType.CONNECTION_POOL_EXHAUSTED))
                .isEqualTo("\"ConnectionPoolExhausted\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CONNECTION_POOL_EXHAUSTED",  // what LangChain4j shows the model - enum name()
            "ConnectionPoolExhausted",    // what @JsonValue emits
            "connection_pool_exhausted",
            "connection-pool-exhausted",
            "Connection Pool Exhausted",
            "connectionPoolExhausted"
    })
    void acceptsEitherNamingConventionAndAnyPunctuation(String raw) {
        // This is the load-bearing case: the schema advertises name(), Jackson would
        // otherwise expect the @JsonValue form, and without leniency that mismatch is a
        // parse failure on every single call.
        assertThat(ErrorType.fromModel(raw)).isEqualTo(ErrorType.CONNECTION_POOL_EXHAUSTED);
    }

    @Test
    void deserialisesThroughJacksonTheSameWay() throws Exception {
        assertThat(objectMapper.readValue("\"OUT_OF_MEMORY\"", ErrorType.class))
                .isEqualTo(ErrorType.OUT_OF_MEMORY);
        assertThat(objectMapper.readValue("\"OutOfMemory\"", ErrorType.class))
                .isEqualTo(ErrorType.OUT_OF_MEMORY);
    }

    @Test
    void mapsAnythingOutsideTheVocabularyToOther() {
        // The exact drift that prompted the enum. Neither spelling is in the vocabulary, and
        // leniency deliberately does NOT invent a mapping onto CACHE_UNAVAILABLE - that
        // would be a synonym table, and it would hide the fact that a constant is missing.
        assertThat(ErrorType.fromModel("RedisConnectionLoss")).isEqualTo(ErrorType.OTHER);
        assertThat(ErrorType.fromModel("RedisConnectionFailure")).isEqualTo(ErrorType.OTHER);
    }

    @Test
    void treatsAMissingValueAsOtherRatherThanFailing() {
        assertThat(ErrorType.fromModel(null)).isEqualTo(ErrorType.OTHER);
        assertThat(ErrorType.fromModel("   ")).isEqualTo(ErrorType.OTHER);
    }

    @Test
    void everyConstantRoundTrips() throws Exception {
        for (ErrorType type : ErrorType.values()) {
            String json = objectMapper.writeValueAsString(type);
            assertThat(objectMapper.readValue(json, ErrorType.class))
                    .as("round trip of %s via %s", type, json)
                    .isEqualTo(type);
            assertThat(ErrorType.fromModel(type.name()))
                    .as("model may answer with name() instead of the json value")
                    .isEqualTo(type);
        }
    }
}
