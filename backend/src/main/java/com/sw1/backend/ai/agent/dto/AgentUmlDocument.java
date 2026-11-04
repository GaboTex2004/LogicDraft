package com.sw1.backend.ai.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/** Authorized read-only UML context. IDs and semantic metadata never come from the chat client. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentUmlDocument(int version, List<Node> nodes, List<Edge> edges) {
    public static AgentUmlDocument from(Map<String, Object> document) {
        return new ObjectMapper().convertValue(document, AgentUmlDocument.class);
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Node(String id, Classifier data) {}
    @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Classifier(String name, String kind, @JsonProperty("abstract") Boolean isAbstract,
                             List<Attribute> attributes, List<Method> methods, List<String> literals,
                             Association association) {}
    @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attribute(String id, String name, String type, Boolean primaryKey, Boolean nullable,
                            String visibility, @JsonProperty("static") Boolean isStatic) {}
    @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Method(String id, String name, String returnType, String visibility,
                         @JsonProperty("static") Boolean isStatic, @JsonProperty("abstract") Boolean isAbstract,
                         List<Parameter> parameters) {}
    @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Parameter(String id, String name, String type) {}
    @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Edge(String id, String source, String target, Relationship data) {}
    @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Relationship(String relationKind, String relationshipType, String name, String joinTableName,
                               String sourceCardinality, String targetCardinality, String sourceMultiplicity,
                               String targetMultiplicity, String aggregationEnd) {}
    @JsonIgnoreProperties(ignoreUnknown = true) @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Association(String kind, String tableName, Boolean uniquePair, List<Endpoint> endpoints,
                              String sourceMultiplicity, String targetMultiplicity) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Endpoint(String role, String entityId, String relationshipId, String foreignKeyName) {}
}
