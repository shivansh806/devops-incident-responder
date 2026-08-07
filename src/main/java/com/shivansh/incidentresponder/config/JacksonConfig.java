package com.shivansh.incidentresponder.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class JacksonConfig {

    /**
     * Makes silently-dropped JSON properties visible without making them fatal.
     * <p>
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} stays off deliberately: week 3 Kafka events will
     * carry fields this application does not model, and strict parsing would turn every
     * upstream schema addition into a hard failure. The cost of that leniency is that a
     * typo in a field name looks identical to success - a request sending
     * {@code service_name} instead of {@code serviceName} gets a 200 and quietly ignores it.
     * <p>
     * Jackson consults registered problem handlers <em>before</em> checking
     * {@code FAIL_ON_UNKNOWN_PROPERTIES}, so this handler sees every unknown property
     * regardless of that setting. Returning {@code false} means "not handled, carry on with
     * the default" - which, with the feature off, is to skip the value and continue. The
     * log line is the only change in behaviour.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer warnOnUnknownJsonProperties() {
        return builder -> builder.postConfigurer(mapper -> mapper.addHandler(new DeserializationProblemHandler() {

            @Override
            public boolean handleUnknownProperty(DeserializationContext ctxt, JsonParser parser,
                                                 JsonDeserializer<?> deserializer,
                                                 Object beanOrClass, String propertyName) {
                log.warn("Ignoring unknown JSON property '{}' while deserialising {} - check for a typo",
                        propertyName, targetTypeName(beanOrClass));
                return false;
            }
        }));
    }

    /** {@code beanOrClass} is the instance being built, or the target {@link Class} for records. */
    private static String targetTypeName(Object beanOrClass) {
        if (beanOrClass == null) {
            return "an unknown type";
        }
        Class<?> type = beanOrClass instanceof Class<?> clazz ? clazz : beanOrClass.getClass();
        return type.getSimpleName();
    }
}
