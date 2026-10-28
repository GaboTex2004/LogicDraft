package com.sw1.backend.diagrama.dto.request;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
public class GuardarDiagramaRequest {
    @NotNull(message = "La version del documento es obligatoria") private Integer version;
    @NotNull(message = "Los nodes son obligatorios") private List<Map<String, Object>> nodes;
    @NotNull(message = "Los edges son obligatorios") private List<Map<String, Object>> edges;
    @PositiveOrZero private Long baseRevision;
    @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") private String clientId;
    public GuardarDiagramaRequest() { }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public List<Map<String, Object>> getNodes() { return nodes; }
    public void setNodes(List<Map<String, Object>> nodes) { this.nodes = nodes; }
    public List<Map<String, Object>> getEdges() { return edges; }
    public void setEdges(List<Map<String, Object>> edges) { this.edges = edges; }
    public Long getBaseRevision() { return baseRevision; }
    public void setBaseRevision(Long baseRevision) { this.baseRevision = baseRevision; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    @AssertTrue(message = "El documento debe usar version 1 o 2 y contener arreglos nodes y edges de hasta 1000000 bytes")
    public boolean isDocumentoValido() {
        if (!(Integer.valueOf(1).equals(version) || Integer.valueOf(2).equals(version)) || nodes == null || edges == null) return false;
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", version);
        document.put("nodes", nodes);
        document.put("edges", edges);
        return document.toString().getBytes(StandardCharsets.UTF_8).length <= 1_000_000;
    }
}
