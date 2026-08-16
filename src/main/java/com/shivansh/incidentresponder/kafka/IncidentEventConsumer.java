package com.shivansh.incidentresponder.kafka;

import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.service.IncidentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Reads incident events off Kafka and puts each one through the existing pipeline.
 * <p>
 * <b>There is no pipeline logic here, deliberately.</b> Analyse, retrieve precedent, resolve
 * and store is {@link IncidentService#analyzeAndRecord}, which the REST controller has been
 * calling since week 2 - and that method's javadoc has anticipated this consumer since it was
 * written. Two entry points that both diagnose incidents must not be able to diagnose them
 * differently, so this class does exactly three things: parse, delegate, log.
 * <p>
 * <b>Failure handling is not here either</b>, and that is also the point. What gets retried,
 * what goes to the dead-letter topic and what that does to offsets is one policy in
 * {@link KafkaConsumerConfig}; a try/catch in this method would be a second, invisible one.
 * The listener's job is to let exceptions out.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentEventConsumer {

    private final IncidentEventParser parser;
    private final IncidentService incidentService;

    /**
     * <b>{@code autoStartup} is a quota switch, not a feature flag.</b> Every event costs two
     * Groq calls, so being able to leave the container stopped means the simulator can fill a
     * topic for free and the events can be inspected before any of them are paid for. Set
     * {@code incident.kafka.consumer-enabled=false} to hold delivery; the offsets keep,
     * because the group is unchanged.
     */
    @KafkaListener(
            topics = "${incident.kafka.topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            autoStartup = "${incident.kafka.consumer-enabled:true}")
    public void onIncidentEvent(ConsumerRecord<String, String> record) {
        IncidentEvent event = parser.parse(record.value());

        log.info("Consuming incident event {} from {}-{}@{} (service={}, {} chars of logs)",
                event.eventId(), record.topic(), record.partition(), record.offset(),
                event.service() == null ? "to be inferred" : event.service(),
                event.logs().length());

        // service() is passed through as the known service: null means "infer it", a value
        // means the producer is asserting the origin and overrides the model. See
        // IncidentEvent.service and AnalyzeRequest.serviceName - one rule, two transports.
        Incident stored = incidentService.analyzeAndRecord(event.logs(), event.service());

        log.info("Incident event {} stored as {} ({} on {})",
                event.eventId(), stored.id(), stored.errorType(), stored.affectedService());
    }
}
