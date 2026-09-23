package com.sw1.backend.generator.spring;

import com.sw1.backend.generator.schema.ApplicationEntity;
import com.sw1.backend.generator.schema.ApplicationSchema;
import com.sw1.backend.generator.spring.render.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class SpringBootGenerator {
    public GeneratedProject generate(ApplicationSchema schema) {
        Map<String, List<OwnedRelation>> relations = RelationshipPlanner.plan(schema);
        SpringGenerationValidator.validate(schema, relations);
        String basePackage = SpringNames.basePackage(schema.technicalName());
        String javaRoot = "src/main/java/" + basePackage.replace('.', '/') + "/";
        String artifact = SpringNames.artifactName(schema.technicalName());
        String database = SpringNames.databaseName(schema.technicalName());
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pom.xml", SpringStaticTemplates.pom(artifact));
        files.put("src/main/resources/application.properties", SpringStaticTemplates.properties(database));
        files.put("README.md", SpringStaticTemplates.readme(schema, artifact, database));
        files.put("API.md", ApiDocumentationRenderer.render(schema, relations));
        files.put(".env.example", SpringStaticTemplates.envExample(database));
        files.put("docker-compose.yml", SpringStaticTemplates.compose(database));
        files.put(".gitignore", "target/\n.idea/\n*.iml\n");
        files.put(javaRoot + schema.technicalName() + "Application.java",
                InfrastructureRenderer.application(basePackage, schema.technicalName()));
        files.put(javaRoot + "config/CorsConfig.java", InfrastructureRenderer.cors(basePackage));
        files.put(javaRoot + "error/ResourceNotFoundException.java", InfrastructureRenderer.notFoundException(basePackage));
        files.put(javaRoot + "error/GlobalExceptionHandler.java", InfrastructureRenderer.errorHandler(basePackage));
        files.put(javaRoot + "ai/RuntimeAiCommandController.java", RuntimeAiRenderer.render(basePackage, schema, relations));
        for (ApplicationEntity entity : schema.entities()) {
            List<OwnedRelation> owned = relations.get(entity.id());
            String name = entity.technicalName();
            files.put(javaRoot + "entity/" + name + ".java", EntityRenderer.render(basePackage, entity, owned));
            files.put(javaRoot + "repository/" + name + "Repository.java", RepositoryRenderer.render(basePackage, entity));
            files.put(javaRoot + "dto/" + name + "CreateRequest.java", DtoRenderer.createRequest(basePackage, entity, owned));
            files.put(javaRoot + "dto/" + name + "UpdateRequest.java", DtoRenderer.updateRequest(basePackage, entity, owned));
            files.put(javaRoot + "dto/" + name + "Response.java", DtoRenderer.response(basePackage, entity, owned));
            files.put(javaRoot + "service/" + name + "Service.java", ServiceRenderer.render(basePackage, entity, owned));
            files.put(javaRoot + "controller/" + name + "Controller.java", ControllerRenderer.render(basePackage, entity));
        }
        return new GeneratedProject(artifact, artifact + ".zip", files);
    }
}
