package com.shivansh.incidentresponder.embedding;

import com.shivansh.incidentresponder.model.ErrorType;
import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;
import com.shivansh.incidentresponder.model.Severity;
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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.convert.MongoConverter;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * Offline: no Atlas, no network, no model. What is pinned here is the shape of the request -
 * which index, which search type, what text got embedded - because those are the parts that
 * fail silently. A wrong index name throws at query time and is obvious; a query built from
 * the wrong text returns confident, plausible, wrong neighbours.
 * <p>
 * This deliberately asserts nothing about ranking quality. That is measured against the real
 * corpus and written up in {@code docs/retrieval.md}; it cannot be faked with three mock
 * documents, and a test that pretended otherwise would only pin the mock.
 */
@ExtendWith(MockitoExtension.class)
class SimilarIncidentSearchTest {

    private static final LogAnalysis QUERY = new LogAnalysis(
            ErrorType.CONNECTION_POOL_EXHAUSTED, "checkout-service", Severity.CRITICAL,
            Instant.parse("2026-08-14T09:12:00Z"),
            List.of("HikariPool-1 - Connection is not available, request timed out after 15000ms"),
            0.86);

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private MongoConverter mongoConverter;

    @Captor
    private ArgumentCaptor<Aggregation> aggregation;

    @Captor
    private ArgumentCaptor<String> embeddedText;

    @Test
    void embedsTheQueryWithTheSameRecipeThatBuiltTheStoredVectors() {
        // The one invariant the whole feature rests on. If the query text is built any other
        // way, every score is measuring the difference between two recipes.
        givenEmbedding();
        givenHits();

        search().findSimilar(QUERY, 3);

        then(embeddingModel).should().embed(embeddedText.capture());
        assertThat(embeddedText.getValue()).isEqualTo(IncidentEmbeddingText.of(QUERY));
        assertThat(embeddedText.getValue()).startsWith("connection pool exhausted on checkout-service");
    }

    @Test
    void asksAtlasForAnExactSearchOnTheNamedIndex() {
        givenEmbedding();
        givenHits();

        search().findSimilar(QUERY, 3);

        Document stage = firstStage();
        assertThat(stage.get("index")).isEqualTo(SimilarIncidentSearch.INDEX_NAME);
        assertThat(stage.get("path")).isEqualTo("embedding");
        assertThat(stage.get("limit")).isEqualTo(3);
        // Exact nearest neighbour. If this ever flips to approximate, numCandidates starts
        // mattering and rankings become tunable - which would quietly invalidate the numbers
        // recorded in docs/retrieval.md.
        assertThat(stage.get("exact")).isEqualTo(true);
        assertThat(stage).doesNotContainKey("numCandidates");
    }

    @Test
    void keepsTheStoredVectorsOutOfTheResults() {
        givenEmbedding();
        givenHits();

        search().findSimilar(QUERY, 3);

        assertThat(pipeline()).anySatisfy(stage ->
                assertThat(stage.get("$project", Document.class)).containsEntry("embedding", 0));
    }

    @Test
    void pairsEachPastIncidentWithItsScore() {
        givenEmbedding();
        Document hit = new Document("_id", "INC-2103").append("score", 0.9602);
        given(mongoTemplate.aggregate(any(Aggregation.class), eq(Incident.class), eq(Document.class)))
                .willReturn(new AggregationResults<>(List.of(hit), new Document()));
        given(mongoTemplate.getConverter()).willReturn(mongoConverter);
        given(mongoConverter.read(Incident.class, hit)).willReturn(incident("INC-2103"));

        List<SimilarIncident> matches = search().findSimilar(QUERY, 3);

        assertThat(matches).singleElement().satisfies(match -> {
            assertThat(match.incident().id()).isEqualTo("INC-2103");
            assertThat(match.score()).isEqualTo(0.9602);
        });
    }

    @Test
    void returnsNothingRatherThanFailingWhenNothingHasBeenEmbedded() {
        // The state of the database between deploying this and running the backfill. An
        // un-embedded collection has no matches; it is not an error.
        givenEmbedding();
        given(mongoTemplate.aggregate(any(Aggregation.class), eq(Incident.class), eq(Document.class)))
                .willReturn(new AggregationResults<>(List.of(), new Document()));

        assertThat(search().findSimilar(QUERY, 3)).isEmpty();
    }

    private SimilarIncidentSearch search() {
        return new SimilarIncidentSearch(mongoTemplate, embeddingModel);
    }

    private void givenEmbedding() {
        given(embeddingModel.embed(anyString()))
                .willReturn(Response.from(Embedding.from(new float[] {0.1f, 0.2f, 0.3f})));
    }

    private void givenHits() {
        given(mongoTemplate.aggregate(any(Aggregation.class), eq(Incident.class), eq(Document.class)))
                .willReturn(new AggregationResults<>(List.of(), new Document()));
    }

    private List<Document> pipeline() {
        then(mongoTemplate).should()
                .aggregate(aggregation.capture(), eq(Incident.class), eq(Document.class));
        return aggregation.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT);
    }

    private Document firstStage() {
        return pipeline().getFirst().get("$vectorSearch", Document.class);
    }

    private static Incident incident(String id) {
        return new Incident(id, ErrorType.CONNECTION_POOL_EXHAUSTED, "payment-service",
                Severity.CRITICAL, Instant.parse("2026-03-09T14:22:07Z"),
                List.of("HikariPool-1 - Connection is not available"), 0.89,
                "Moved the gateway call out of the transaction",
                Instant.parse("2026-03-09T14:58:31Z"), null);
    }
}
