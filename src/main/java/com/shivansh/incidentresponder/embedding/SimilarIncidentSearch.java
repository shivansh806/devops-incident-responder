package com.shivansh.incidentresponder.embedding;

import com.shivansh.incidentresponder.model.Incident;
import com.shivansh.incidentresponder.model.LogAnalysis;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.VectorSearchOperation;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Finds the past incidents that look most like a new one.
 * <p>
 * <b>Not a filter.</b> Matching on {@code errorType} would return the wrong three incidents
 * as confidently as the right ones: INC-2103, INC-2331 and INC-2464 share that field and
 * their fixes contradict each other. This ranks on what the failure looked like, which is
 * carried by the evidence lines, so incidents can match across error types and fail to match
 * within one.
 * <p>
 * <b>The query text comes from {@link IncidentEmbeddingText}</b> - the same class, the same
 * recipe, that produced the stored vectors. That is the whole reason similarity means
 * anything here, and it is why the query side must never be built anywhere else.
 * <p>
 * <b>Exact search, not approximate.</b> {@code ENN} scans every indexed vector rather than
 * using the approximate index. Over a corpus this small, approximation trades accuracy for a
 * speed saving that does not exist, and it would add a {@code numCandidates} knob whose
 * tuning would be indistinguishable from changes in retrieval quality. Revisit at a few
 * thousand documents, not before.
 * <p>
 * <b>A new incident cannot retrieve itself.</b> Incidents are stored with a null embedding
 * and vectorised later, and Atlas does not index a document whose vector field is missing.
 * So an incident saved moments earlier is invisible to this search until it is back filled -
 * which is the behaviour we want, arrived at for free rather than by a guard clause.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarIncidentSearch {

    /**
     * Must match the Atlas Vector Search index built over {@code incidents.embedding}. It is
     * a constant rather than a property because it is not a deployment choice: the index
     * definition, the 384 dimensions and this code are one unit, and a mismatch here fails at
     * query time rather than doing anything subtle.
     */
    static final String INDEX_NAME = "incident_embedding_index";

    private static final String SCORE_FIELD = "score";
    private static final String EMBEDDING_FIELD = "embedding";

    private final MongoTemplate mongoTemplate;
    private final EmbeddingModel embeddingModel;

    /**
     * @param analysis a fresh diagnosis, straight from the Analyzer
     * @param limit    how many past incidents to return
     * @return the closest past incidents, most similar first, each with its similarity score.
     *         Empty when nothing has been embedded yet - an un-backfilled database returns no
     *         matches rather than failing.
     */
    public List<SimilarIncident> findSimilar(LogAnalysis analysis, int limit) {
        String queryText = IncidentEmbeddingText.of(analysis);
        float[] queryVector = embeddingModel.embed(queryText).content().vector();

        VectorSearchOperation search = VectorSearchOperation.search(INDEX_NAME)
                .path(EMBEDDING_FIELD)
                .vector(queryVector)
                .limit(limit)
                .searchType(VectorSearchOperation.SearchType.ENN)
                .withSearchScore(SCORE_FIELD);

        // Reading into Document rather than straight into Incident because the score is not a
        // field of the document - it is produced by the search stage. Dropping the embedding
        // on the way out keeps 384 numbers per hit off the wire for a value nothing downstream
        // reads; the Resolver wants the resolution notes, not the vector.
        List<Document> hits = mongoTemplate.aggregate(
                        Aggregation.newAggregation(search, Aggregation.project().andExclude(EMBEDDING_FIELD)),
                        Incident.class,
                        Document.class)
                .getMappedResults();

        List<SimilarIncident> matches = hits.stream()
                .map(hit -> new SimilarIncident(
                        mongoTemplate.getConverter().read(Incident.class, hit),
                        hit.getDouble(SCORE_FIELD)))
                .toList();

        log.debug("Retrieved {} similar incidents for {}", matches.size(), analysis.errorType());
        return matches;
    }
}
