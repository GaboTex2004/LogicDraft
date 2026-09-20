package com.sw1.backend.generator.flutter;

import java.util.LinkedHashMap;
import java.util.Map;

public record FlutterProject(String packageName, Map<String, String> files) {
    public FlutterProject {
        files = Map.copyOf(new LinkedHashMap<>(files));
    }
}
