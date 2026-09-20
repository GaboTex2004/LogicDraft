package com.sw1.backend.generator.spring.render;

import com.sw1.backend.generator.schema.ApplicationEntity;
import com.sw1.backend.generator.spring.SpringNames;
import com.sw1.backend.generator.spring.SpringTypeMapper;

public final class ControllerRenderer {
    private ControllerRenderer() {
    }

    public static String render(String basePackage, ApplicationEntity entity) {
        String type = entity.technicalName();
        String service = SpringNames.lowerFirst(type) + "Service";
        String idType = SpringTypeMapper.map(DtoRenderer.primaryKey(entity).type()).javaType();
        JavaSourceWriter out = new JavaSourceWriter().line("package " + basePackage + ".controller;").line("")
                .line("import " + basePackage + ".dto.*;")
                .line("import " + basePackage + ".service." + type + "Service;")
                .line("import jakarta.validation.Valid;")
                .line("import java.util.List;")
                .line("import org.springframework.http.ResponseEntity;")
                .line("import org.springframework.web.bind.annotation.*;");
        String typeImport = SpringTypeMapper.map(DtoRenderer.primaryKey(entity).type()).javaImport();
        if (typeImport != null) out.line("import " + typeImport + ";");
        out.line("").line("@RestController").line("@RequestMapping(\"" + SpringNames.restRoute(type) + "\")")
                .open("public class " + type + "Controller")
                .line("private final " + type + "Service " + service + ";").line("")
                .open("public " + type + "Controller(" + type + "Service " + service + ")")
                .line("this." + service + " = " + service + ";").close().line("")
                .line("@GetMapping").open("public List<" + type + "Response> listar()")
                .line("return " + service + ".listar();").close().line("")
                .line("@GetMapping(\"/{id}\")").open("public " + type + "Response obtener(@PathVariable " + idType + " id)")
                .line("return " + service + ".obtenerPorId(id);").close().line("")
                .line("@PostMapping").open("public " + type + "Response crear(@Valid @RequestBody " + type + "CreateRequest request)")
                .line("return " + service + ".crear(request);").close().line("")
                .line("@PutMapping(\"/{id}\")").open("public " + type + "Response actualizar(@PathVariable " + idType
                        + " id, @Valid @RequestBody " + type + "UpdateRequest request)")
                .line("return " + service + ".actualizar(id, request);").close().line("")
                .line("@DeleteMapping(\"/{id}\")").open("public ResponseEntity<Void> eliminar(@PathVariable " + idType + " id)")
                .line(service + ".eliminar(id);").line("return ResponseEntity.noContent().build();").close();
        return out.close().toString();
    }
}
