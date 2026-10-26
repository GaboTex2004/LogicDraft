package com.sw1.backend.generator.schema;

import java.util.List;

/** Non-persistent UML projection used only to add typed domain skeletons to generated projects. */
public record UmlStructuralModel(List<Classifier> classifiers, List<Relation> relations) {
    public static final UmlStructuralModel EMPTY = new UmlStructuralModel(List.of(), List.of());

    public record Classifier(String id, String name, String kind, boolean abstractType,
                             List<Member> attributes, List<Operation> operations, List<String> literals) {}
    public record Member(String name, String type, String visibility, boolean staticMember) {}
    public record Operation(String name, String returnType, String visibility, boolean staticMember,
                            boolean abstractMember, List<Parameter> parameters) {}
    public record Parameter(String name, String type) {}
    public record Relation(String id, String sourceId, String targetId, String kind) {}
}
