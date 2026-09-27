package com.sw1.backend.generator.ea;

import org.springframework.stereotype.Component;

/** Public import facade: XMI parsing and editor-document mapping are deliberately separate. */
@Component
public class EnterpriseArchitectXmiReader {
    private final EnterpriseArchitectXmiParser parser;

    public EnterpriseArchitectXmiReader(EnterpriseArchitectXmiParser parser) {
        this.parser = parser;
    }

    public NormalizedDiagram readNormalized(byte[] bytes) {
        return parser.parse(bytes);
    }

    public EnterpriseArchitectImportPreview read(byte[] bytes) {
        return EnterpriseArchitectDiagramMapper.toPreview(readNormalized(bytes));
    }
}
