package com.sw1.backend.generator.spring;

import com.sw1.backend.generator.zip.ZipProject;
import java.util.LinkedHashMap;
import java.util.Map;

public record GeneratedProject(String rootDirectoryName, String downloadFileName, Map<String, String> files)
        implements ZipProject {
    public GeneratedProject {
        files = Map.copyOf(new LinkedHashMap<>(files));
    }
}
