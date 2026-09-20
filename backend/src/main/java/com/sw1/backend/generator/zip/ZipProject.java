package com.sw1.backend.generator.zip;

import java.util.Map;

public interface ZipProject {
    String rootDirectoryName();
    String downloadFileName();
    Map<String, String> files();
}
