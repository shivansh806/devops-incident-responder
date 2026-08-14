package com.shivansh.incidentresponder.repository;

import com.shivansh.incidentresponder.model.Incident;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Storage for analysed incidents.
 * <p>
 * Spring Data generates the implementation at startup from this interface -
 * {@code MongoRepository} already supplies {@code save}, {@code findById}, {@code findAll}
 * and the rest, so there is nothing to write. Query methods get added by declaring their
 * signature, as {@link #findByEmbeddingIsNull()} does.
 * <p>
 * The id type is {@code String} rather than {@code ObjectId}: it keeps the driver's types
 * out of the model and out of URLs, and Spring Data converts a valid hex string to an
 * {@code ObjectId} on the way in. A non-hex id is simply not found, which is the right
 * answer anyway.
 */
@Repository
public interface IncidentRepository extends MongoRepository<Incident, String> {

    /**
     * Incidents that have not been embedded yet - the backfill's work list.
     * <p>
     * Spring Data turns this into {@code {embedding: null}}, and in MongoDB that matches a
     * field explicitly set to null <em>and</em> a field that is absent entirely. Both cases
     * are real here: seeded documents have no {@code embedding} key at all, because Spring
     * Data omits nulls when writing, while a document could later be written with one. Asking
     * for null covers both, which is what makes re-running the backfill safe - anything
     * already embedded simply stops being returned.
     */
    List<Incident> findByEmbeddingIsNull();
}
