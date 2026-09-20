package com.sw1.backend.ai.service;

import tools.jackson.databind.ObjectMapper;
import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.diagram.dto.DiagramInterpretResponse;
import com.sw1.backend.ai.diagram.validation.ContextualOperationValidator;
import com.sw1.backend.ai.diagram.validation.DiagramOperationValidator;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImageInterpretationService {

    private static final long MAX_IMAGE_BYTES = 5 * 1024 * 1024;

    private static final Set<String> EXTENSIONS =
            Set.of(".png", ".jpg", ".jpeg", ".webp");

    private final ProjectDiagramContextService context;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public ImageInterpretationService(
            ProjectDiagramContextService context,
            ObjectMapper objectMapper,
            @Value("${ai.service.url}") String aiServiceUrl
    ) {
        this.context = context;
        this.objectMapper = objectMapper;

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(120));

        this.client = RestClient.builder()
                .baseUrl(aiServiceUrl)
                .requestFactory(factory)
                .build();
    }

    public DiagramInterpretResponse interpret(
            Long projectId,
            MultipartFile image,
            String prompt
    ) {
        // Comprueba el proyecto y los permisos OWNER/EDITOR.
        var initial = context.load(projectId);

        if (image == null || image.isEmpty()) {
            throw new AiServiceException(
                    HttpStatus.BAD_REQUEST,
                    "Selecciona una imagen válida."
            );
        }

        if (image.getSize() > MAX_IMAGE_BYTES) {
            throw new AiServiceException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "La imagen supera el límite de 5 MB."
            );
        }

        String filename = image.getOriginalFilename();
        String extension = "";

        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0) {
                extension = filename.substring(dot).toLowerCase(Locale.ROOT);
            }
        }

        if (!EXTENSIONS.contains(extension)) {
            throw new AiServiceException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Formato no permitido. Utiliza PNG, JPG o WEBP."
            );
        }

        String instruction = prompt == null ? "" : prompt.trim();

        if (instruction.length() > 10000) {
            throw new AiServiceException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "La instrucción supera los 10000 caracteres."
            );
        }

        try {
            byte[] bytes = image.getBytes();

            if (bytes.length > MAX_IMAGE_BYTES) {
                throw new AiServiceException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "La imagen supera el límite de 5 MB."
                );
            }

            String mimeType = switch (extension) {
                case ".png" -> "image/png";
                case ".jpg", ".jpeg" -> "image/jpeg";
                case ".webp" -> "image/webp";
                default -> throw new IllegalStateException(
                        "Extensión de imagen inesperada"
                );
            };

            String safeName = "diagram" + extension;

            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return safeName;
                }
            };

            HttpHeaders partHeaders = new HttpHeaders();
            partHeaders.setContentType(MediaType.parseMediaType(mimeType));

            HttpEntity<ByteArrayResource> imagePart =
                    new HttpEntity<>(resource, partHeaders);

            // FastAPI no admite el campo associations en DiagramContext.
            String diagramJson = objectMapper.writeValueAsString(
                    Map.of(
                            "entities", initial.entities(),
                            "relationships", initial.relationships()
                    )
            );

            MultiValueMap<String, Object> body =
                    new LinkedMultiValueMap<>();

            body.add("image", imagePart);
            body.add("prompt", instruction);
            body.add("diagram", diagramJson);

            @SuppressWarnings("unchecked")
            Map<String, Object> raw = client.post()
                    .uri("/api/ai/image/interpret")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            // Primera validación: estructura y tipos de operaciones.
            DiagramInterpretResponse response =
                    DiagramOperationValidator.validate(raw);

            // Segunda validación: contexto actualizado y referencias.
            // Este método no guarda ni aplica cambios.
            return ContextualOperationValidator.validate(
                    context.load(projectId),
                    response
            );

        } catch (IOException exception) {
            throw new AiServiceException(
                    HttpStatus.BAD_REQUEST,
                    "No se pudo leer o preparar la imagen."
            );

        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();

            if (status == 413) {
                throw new AiServiceException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "La imagen supera el tamaño permitido."
                );
            }

            if (status == 415) {
                throw new AiServiceException(
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        "El formato de imagen no es válido."
                );
            }

            if (status == 422) {
                throw new AiServiceException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "No se pudo interpretar la imagen."
                );
            }

            if (status == 429 || status == 503) {
                throw new AiServiceException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Gemini no está disponible o alcanzó su cuota."
                );
            }

            if (status == 504) {
                throw new AiServiceException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "La interpretación excedió el tiempo de espera."
                );
            }

            throw new AiServiceException(
                    HttpStatus.BAD_GATEWAY,
                    "FastAPI no devolvió una interpretación válida."
            );

        } catch (ResourceAccessException exception) {
            throw new AiServiceException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo conectar con FastAPI."
            );
        }
    }
}