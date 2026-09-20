package com.sw1.backend.generator.schema;

import java.util.List;

public record ApplicationSchema(
        int schemaVersion,
        String projectName,
        String applicationName,
        String technicalName,
        List<ApplicationEntity> entities,
        List<ApplicationRelationship> relationships) {
}
