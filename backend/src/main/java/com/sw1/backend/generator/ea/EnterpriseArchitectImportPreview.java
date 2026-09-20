package com.sw1.backend.generator.ea;

import java.util.List;
import java.util.Map;

public record EnterpriseArchitectImportPreview(
        String projectName,
        Integer version,
        List<Map<String, Object>> nodes,
        List<Map<String, Object>> edges,
        List<String> warnings
) {
}