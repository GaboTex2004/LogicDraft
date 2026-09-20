package com.sw1.backend.generator;

import com.sw1.backend.generator.flutter.FlutterGenerator;
import com.sw1.backend.generator.spring.SpringBootGenerator;
import com.sw1.backend.generator.spring.SpringNames;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpringFlutterCompatibilityTest {
    @Test
    void usesSameRoutesPrimaryKeysAndRelationIdContract() {
        var schema = GeneratorTestSchemas.mainSchema();
        var spring = new SpringBootGenerator().generate(schema);
        var flutter = new FlutterGenerator().generate(schema);
        String springRoot = "src/main/java/com/logicdraft/generated/peluqueria/";

        for (var entity : schema.entities()) {
            String route = SpringNames.restRoute(entity.technicalName());
            String file = SpringNames.sqlName(entity.technicalName(), "test");
            assertTrue(spring.files().get(springRoot + "controller/" + entity.technicalName() + "Controller.java")
                    .contains("@RequestMapping(\"" + route + "\")"));
            assertTrue(flutter.files().get("lib/services/" + file + "_service.dart")
                    .contains("route = '" + route + "'"));
        }

        String springCreate = spring.files().get(springRoot + "dto/CorteCreateRequest.java");
        String springUpdate = spring.files().get(springRoot + "dto/CorteUpdateRequest.java");
        String springResponse = spring.files().get(springRoot + "dto/CorteResponse.java");
        String flutterModel = flutter.files().get("lib/models/corte.dart");
        assertTrue(springCreate.contains("Integer categoriaId"));
        assertTrue(springUpdate.contains("Integer categoriaId"));
        assertTrue(springResponse.contains("Integer categoriaId"));
        assertTrue(flutterModel.contains("final int categoriaId;"));
        assertTrue(flutterModel.contains("'categoriaId': categoriaId"));
        assertFalse(flutterModel.contains("Categoria categoria"));
        assertTrue(springResponse.contains("BigDecimal precio"));
        assertTrue(flutterModel.contains("final double precio;"));
        assertTrue(springResponse.contains("Integer id"));
        assertTrue(flutterModel.contains("final int id;"));
    }
}
