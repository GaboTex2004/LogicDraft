package com.sw1.backend.generator.spring.render;

import com.sw1.backend.generator.schema.ApplicationEntity;
import com.sw1.backend.generator.schema.CanonicalType;
import com.sw1.backend.generator.spring.SpringTypeMapper;

public final class RepositoryRenderer {
    private RepositoryRenderer() {
    }

    public static String render(String basePackage, ApplicationEntity entity) {
        var pk = DtoRenderer.primaryKey(entity);
        JavaSourceWriter out = new JavaSourceWriter()
                .line("package " + basePackage + ".repository;").line("")
                .line("import " + basePackage + ".entity." + entity.technicalName() + ";")
                .line("import org.springframework.data.jpa.repository.JpaRepository;");
        String typeImport = SpringTypeMapper.map(pk.type()).javaImport();
        if (typeImport != null) out.line("import " + typeImport + ";");
        boolean searchableName = entity.fields().stream().anyMatch(field -> field.type() == CanonicalType.STRING
                && field.technicalName().equalsIgnoreCase("nombre"));
        if (searchableName) out.line("import java.util.List;");
        out.line("").open("public interface " + entity.technicalName() + "Repository extends JpaRepository<"
                + entity.technicalName() + ", " + SpringTypeMapper.map(pk.type()).javaType() + ">");
        if (searchableName) out.line("List<" + entity.technicalName() + "> findTop2ByNombreIgnoreCase(String nombre);");
        return out.close().toString();
    }
}
