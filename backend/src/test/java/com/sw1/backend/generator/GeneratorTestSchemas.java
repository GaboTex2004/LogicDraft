package com.sw1.backend.generator;

import com.sw1.backend.generator.schema.*;
import java.util.ArrayList;
import java.util.List;

final class GeneratorTestSchemas {
    private GeneratorTestSchemas() {}

    static ApplicationField field(String entity, String name, CanonicalType type, boolean pk, boolean nullable, boolean generated) {
        String technical = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        return new ApplicationField(entity + "-" + technical, name, technical, type, pk, nullable, generated);
    }

    static ApplicationEntity entity(String id, String name, CanonicalType pkType, boolean generated, ApplicationField... extra) {
        List<ApplicationField> fields = new ArrayList<>();
        fields.add(field(id, "Id", pkType, true, false, generated));
        fields.addAll(List.of(extra));
        return new ApplicationEntity(id, name, name, List.copyOf(fields));
    }

    static ApplicationSchema serviceSchema() {
        ApplicationEntity service = entity("service", "Servicio", CanonicalType.INTEGER, true,
                field("service", "Nombre", CanonicalType.STRING, false, false, false),
                field("service", "Precio", CanonicalType.DECIMAL, false, false, false));
        return schema("Servicios", List.of(service), List.of());
    }

    static ApplicationSchema mainSchema() {
        ApplicationEntity category = entity("category", "Categoria", CanonicalType.INTEGER, true,
                field("category", "Nombre", CanonicalType.STRING, false, false, false));
        ApplicationEntity cut = entity("cut", "Corte", CanonicalType.INTEGER, true,
                field("cut", "Nombre", CanonicalType.STRING, false, false, false),
                field("cut", "Precio", CanonicalType.DECIMAL, false, false, false));
        ApplicationEntity salon = entity("salon", "Peluqueria", CanonicalType.INTEGER, true,
                field("salon", "Nombre", CanonicalType.STRING, false, false, false),
                field("salon", "Ubicacion", CanonicalType.STRING, false, false, false));
        ApplicationEntity user = entity("user", "Usuario", CanonicalType.INTEGER, true,
                field("user", "Nombre", CanonicalType.STRING, false, false, false),
                field("user", "Telefono", CanonicalType.STRING, false, true, false),
                field("user", "Saldo", CanonicalType.DECIMAL, false, true, false));
        ApplicationRelationship relationship = new ApplicationRelationship("category-cut", "category", "cut",
                ApplicationCardinality.ONE_ONE, ApplicationCardinality.ZERO_MANY);
        return schema("Peluqueria", List.of(category, cut, salon, user), List.of(relationship));
    }

    static ApplicationSchema flutterAllTypesSchema() {
        ApplicationEntity event = entity("event", "Evento", CanonicalType.DATE, false,
                field("event", "Nombre", CanonicalType.STRING, false, false, false));
        ApplicationEntity record = entity("record", "Registro", CanonicalType.LONG, true,
                field("record", "Texto", CanonicalType.STRING, false, false, false),
                field("record", "Cantidad", CanonicalType.INTEGER, false, true, false),
                field("record", "Monto", CanonicalType.DECIMAL, false, false, false),
                field("record", "Activo", CanonicalType.BOOLEAN, false, false, false),
                field("record", "Fecha", CanonicalType.DATE, false, false, false),
                field("record", "Instante", CanonicalType.DATETIME, false, true, false));
        ApplicationEntity category = entity("category", "Categoria", CanonicalType.INTEGER, true,
                field("category", "Nombre", CanonicalType.STRING, false, false, false));
        ApplicationEntity cut = entity("cut", "Corte", CanonicalType.INTEGER, true,
                field("cut", "Nombre", CanonicalType.STRING, false, false, false),
                field("cut", "Precio", CanonicalType.DECIMAL, false, false, false));
        ApplicationRelationship manyToMany = new ApplicationRelationship("record-events", "record", "event",
                ApplicationCardinality.ZERO_MANY, ApplicationCardinality.ZERO_MANY);
        ApplicationRelationship oneToMany = new ApplicationRelationship("category-cut", "category", "cut",
                ApplicationCardinality.ONE_ONE, ApplicationCardinality.ZERO_MANY);
        return schema("Registros", List.of(record, event, category, cut), List.of(manyToMany, oneToMany));
    }

    static ApplicationSchema flutterNullSafetySchema() {
        ApplicationEntity category = entity("category", "Categoria", CanonicalType.INTEGER, true,
                field("category", "Nombre", CanonicalType.STRING, false, true, false),
                field("category", "Cantidad", CanonicalType.INTEGER, false, true, false),
                field("category", "Total", CanonicalType.DECIMAL, false, true, false),
                field("category", "Activa", CanonicalType.BOOLEAN, false, true, false),
                field("category", "Fecha", CanonicalType.DATE, false, true, false),
                field("category", "ActualizadaEn", CanonicalType.DATETIME, false, true, false));
        ApplicationEntity cut = entity("cut", "Corte", CanonicalType.INTEGER, true,
                field("cut", "Nombre", CanonicalType.STRING, false, false, false),
                field("cut", "Precio", CanonicalType.DECIMAL, false, true, false));
        ApplicationRelationship optionalCategory = new ApplicationRelationship("category-cut", "category", "cut",
                ApplicationCardinality.ZERO_ONE, ApplicationCardinality.ZERO_MANY);
        return schema("Barbero null safety", List.of(category, cut), List.of(optionalCategory));
    }

    static ApplicationSchema manyToManySchema() {
        ApplicationEntity student = entity("student", "Alumno", CanonicalType.INTEGER, true,
                field("student", "Nombre", CanonicalType.STRING, false, false, false));
        ApplicationEntity subject = entity("subject", "Materia", CanonicalType.INTEGER, true,
                field("subject", "Nombre", CanonicalType.STRING, false, false, false));
        ApplicationRelationship enrollment = new ApplicationRelationship("student-subject", "student", "subject",
                ApplicationCardinality.ZERO_MANY, ApplicationCardinality.ZERO_MANY,
                "materias", "alumno_materia");
        return schema("Academia", List.of(student, subject), List.of(enrollment));
    }

    static ApplicationSchema associativeEntitySchema() {
        ApplicationEntity student = entity("student", "Alumno", CanonicalType.INTEGER, true,
                field("student", "Nombre", CanonicalType.STRING, false, false, false));
        ApplicationEntity subject = entity("subject", "Materia", CanonicalType.INTEGER, true,
                field("subject", "Nombre", CanonicalType.STRING, false, false, false));
        ApplicationEntity enrollment = entity("enrollment", "Inscripcion", CanonicalType.INTEGER, true,
                field("enrollment", "Fecha", CanonicalType.DATE, false, false, false),
                field("enrollment", "Nota", CanonicalType.DECIMAL, false, true, false));
        return schema("Academia Inscripciones", List.of(student, subject, enrollment), List.of(
                new ApplicationRelationship("student-enrollment", "student", "enrollment",
                        ApplicationCardinality.ONE_ONE, ApplicationCardinality.ZERO_MANY),
                new ApplicationRelationship("subject-enrollment", "subject", "enrollment",
                        ApplicationCardinality.ONE_ONE, ApplicationCardinality.ZERO_MANY)));
    }

    static ApplicationSchema associativeMetadataSchema() {
        return associativeMetadataSchema(CanonicalType.INTEGER, CanonicalType.LONG);
    }

    static ApplicationSchema associativeMetadataSchema(CanonicalType studentPk, CanonicalType subjectPk) {
        ApplicationEntity student = entity("student", "Alumno", studentPk, true,
                field("student", "Nombre", CanonicalType.STRING, false, false, false));
        ApplicationEntity subject = entity("subject", "Materia", subjectPk, true,
                field("subject", "Nombre", CanonicalType.STRING, false, false, false));
        ApplicationAssociation association = new ApplicationAssociation(
                AssociationKind.MANY_TO_MANY_ASSOCIATION,
                "alumno_materia",
                List.of(
                        new ApplicationAssociationEndpoint(AssociationEndpointRole.SOURCE,
                                "student", "student-enrollment", "alumno_ref"),
                        new ApplicationAssociationEndpoint(AssociationEndpointRole.TARGET,
                                "subject", "subject-enrollment", "materia_ref")),
                true);
        ApplicationEntity enrollmentBase = entity("enrollment", "Inscripcion", CanonicalType.INTEGER, true,
                field("enrollment", "Nota", CanonicalType.INTEGER, false, false, false),
                field("enrollment", "FechaInscripcion", CanonicalType.DATE, false, false, false));
        ApplicationEntity enrollment = new ApplicationEntity(enrollmentBase.id(), enrollmentBase.name(),
                enrollmentBase.technicalName(), enrollmentBase.fields(), association);
        return schema("Academia Asociativa", List.of(student, subject, enrollment), List.of(
                new ApplicationRelationship("student-enrollment", "student", "enrollment",
                        ApplicationCardinality.ONE_ONE, ApplicationCardinality.ZERO_MANY),
                new ApplicationRelationship("subject-enrollment", "subject", "enrollment",
                        ApplicationCardinality.ONE_ONE, ApplicationCardinality.ZERO_MANY)));
    }

    static ApplicationSchema schema(String name, List<ApplicationEntity> entities, List<ApplicationRelationship> relationships) {
        return new ApplicationSchema(1, name, name, name.replace(" ", ""), entities, relationships);
    }
}
