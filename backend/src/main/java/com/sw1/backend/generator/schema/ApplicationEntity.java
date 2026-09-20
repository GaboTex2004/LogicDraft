package com.sw1.backend.generator.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicationEntity(
        String id,
        String name,
        String technicalName,
        List<ApplicationField> fields,
        ApplicationAssociation association) {
    public ApplicationEntity(String id, String name, String technicalName, List<ApplicationField> fields) {
        this(id, name, technicalName, fields, null);
    }
}
