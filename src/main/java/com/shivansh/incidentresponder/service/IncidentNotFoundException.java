package com.shivansh.incidentresponder.service;

/** No incident is stored under the requested id. Maps to a 404. */
public class IncidentNotFoundException extends RuntimeException {

    public IncidentNotFoundException(String id) {
        super("No incident found with id '%s'".formatted(id));
    }
}
