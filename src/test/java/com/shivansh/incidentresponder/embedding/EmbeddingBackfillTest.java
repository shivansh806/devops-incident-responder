package com.shivansh.incidentresponder.embedding;

import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.Severity;
import com.shivansh.incidentresponder.repository.IncidentRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * The model is mocked, so this runs offline and in milliseconds. What is under test is the
 * safety behaviour around the write, not the quality of the vectors - that is measured by
 * running the backfill for real and reading what it prints.
 */
@ExtendWith(MockitoExtension.class)
class EmbeddingBackfillTest {

    @Mock
    private IncidentRepository incidentRepository;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private MongoTemplate mongoTemplate;

    @Captor
    private ArgumentCaptor<Update> update;

    @Test
    void buildsEveryVectorButWritesNoneWithoutTheWriteFlag() {
        given(incidentRepository.findByEmbeddingIsNull()).willReturn(List.of(pending("INC-1"), pending("INC-2")));
        given(embeddingModel.embed(anyString())).willReturn(vector());

        backfill().run(new DefaultApplicationArguments());

        // The dry run is only useful if it does the real work first - a survey that skipped
        // embedding could not report a true token count.
        then(embeddingModel).should(times(2)).embed(anyString());
        then(mongoTemplate).shouldHaveNoInteractions();
    }

    @Test
    void writesOneTargetedUpdatePerIncidentWhenArmed() {
        given(incidentRepository.findByEmbeddingIsNull()).willReturn(List.of(pending("INC-1"), pending("INC-2")));
        given(embeddingModel.embed(anyString())).willReturn(vector());

        backfill().run(new DefaultApplicationArguments("--write"));

        then(mongoTemplate).should(times(2)).updateFirst(any(Query.class), update.capture(), any(Class.class));

        // $set of the embedding alone. A whole-document write here would be the mirror of the
        // upsert hazard IncidentSeeder avoids: it would carry an in-memory copy of every other
        // field back over whatever is currently stored.
        Document written = update.getValue().getUpdateObject();
        assertThat(written).containsOnlyKeys("$set");
        assertThat((Document) written.get("$set")).containsOnlyKeys("embedding");
    }

    @Test
    void doesNotTouchTheModelWhenEverythingIsAlreadyEmbedded() {
        given(incidentRepository.findByEmbeddingIsNull()).willReturn(List.of());

        backfill().run(new DefaultApplicationArguments("--write"));

        then(embeddingModel).shouldHaveNoInteractions();
        then(mongoTemplate).shouldHaveNoInteractions();
    }

    private EmbeddingBackfill backfill() {
        return new EmbeddingBackfill(incidentRepository, embeddingModel, mongoTemplate);
    }

    private static Response<Embedding> vector() {
        return Response.from(Embedding.from(new float[] {0.1f, 0.2f, 0.3f}));
    }

    private static Incident pending(String id) {
        return new Incident(id, ErrorType.CACHE_UNAVAILABLE, "profile-service", Severity.MEDIUM,
                Instant.parse("2026-08-05T02:14:33Z"), List.of("redis: connection refused"), 0.9,
                "Restarted the node", Instant.parse("2026-08-05T02:40:00Z"), null);
    }
}
