package com.sw1.backend.generator.export;

import com.sw1.backend.generator.zip.ZipProject;
import java.util.LinkedHashMap;
import java.util.Map;

public record FullStackProject(String rootDirectoryName, String downloadFileName, Map<String, String> files)
        implements ZipProject {
    public FullStackProject {
        files = Map.copyOf(new LinkedHashMap<>(files));
    }
}
