package com.shivansh.incidentresponder.repository;

import com.shivansh.incidentresponder.model.Incident;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

/**
 * Storage for analysed incidents.
 * <p>
 * Spring Data generates the implementation at startup from this interface -
 * {@code MongoRepository} already supplies {@code save}, {@code findById}, {@code findAll}
 * and the rest, so there is nothing to write. Query methods get added by declaring their
 * signature; none are needed yet.
 * <p>
 * The id type is {@code String} rather than {@code ObjectId}: it keeps the driver's types
 * out of the model and out of URLs, and Spring Data converts a valid hex string to an
 * {@code ObjectId} on the way in. A non-hex id is simply not found, which is the right
 * answer anyway.
 */
@Repository
public interface IncidentRepository extends MongoRepository<Incident, String> {
}
