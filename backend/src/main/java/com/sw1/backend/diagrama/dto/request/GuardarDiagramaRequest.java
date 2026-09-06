package com.sw1.backend.diagrama.dto.request;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
public class GuardarDiagramaRequest {
    @NotNull(message = "La version del documento es obligatoria") private Integer version;
    @NotNull(message = "Los nodes son obligatorios") private List<Map<String, Object>> nodes;
    @NotNull(message = "Los edges son obligatorios") private List<Map<String, Object>> edges;
    public GuardarDiagramaRequest() { }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public List<Map<String, Object>> getNodes() { return nodes; }
    public void setNodes(List<Map<String, Object>> nodes) { this.nodes = nodes; }
    public List<Map<String, Object>> getEdges() { return edges; }
    public void setEdges(List<Map<String, Object>> edges) { this.edges = edges; }

    @AssertTrue(message = "El documento debe usar version 1 y contener arreglos nodes y edges de hasta 1000000 bytes")
    public boolean isDocumentoValido() {
        if (!Integer.valueOf(1).equals(version) || nodes == null || edges == null) return false;
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", version);
        document.put("nodes", nodes);
        document.put("edges", edges);
        return document.toString().getBytes(StandardCharsets.UTF_8).length <= 1_000_000;
    }
}
