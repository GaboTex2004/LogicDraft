package com.sw1.backend.ai.service;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AudioTranscriptionService {

    private static final long MAX_AUDIO_BYTES = 10 * 1024 * 1024;

    private static final Set<String> EXTENSIONS = Set.of(
            ".wav", ".mp3", ".m4a", ".ogg",
            ".webm", ".mp4", ".flac"
    );

    private final ProjectDiagramContextService context;
    private final RestClient client;

    public AudioTranscriptionService(
            ProjectDiagramContextService context,
            @Value("${ai.service.url}") String aiServiceUrl
    ) {
        this.context = context;

        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(90));

        this.client = RestClient.builder()
                .baseUrl(aiServiceUrl)
                .requestFactory(factory)
                .build();
    }

    public Map<String, String> transcribe(
            Long projectId,
            MultipartFile audio
    ) {
        // Comprueba que el proyecto existe y que el usuario es OWNER o EDITOR.
        context.load(projectId);

        if (audio.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El audio está vacío."
            );
        }

        if (audio.getSize() > MAX_AUDIO_BYTES) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "El audio supera el límite de 10 MB."
            );
        }

        String originalName = audio.getOriginalFilename();
        String extension = "";

        if (originalName != null) {
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0) {
                extension = originalName.substring(dot).toLowerCase();
            }
        }

        if (!EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Formato de audio no permitido."
            );
        }

        try {
            byte[] bytes = audio.getBytes();
            String safeName = "recording" + extension;

            ByteArrayResource resource = new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return safeName;
                }
            };

            HttpHeaders partHeaders = new HttpHeaders();
            partHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);

            HttpEntity<ByteArrayResource> audioPart =
                    new HttpEntity<>(resource, partHeaders);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("audio", audioPart);

            Map<?, ?> response = client.post()
                    .uri("/api/audio/transcribe")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null ||
                    !(response.get("text") instanceof String text) ||
                    text.isBlank()) {

                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "El servicio no devolvió una transcripción válida."
                );
            }

            return Map.of("text", text);

        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No se pudo leer el audio."
            );

        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();

            if (status == 413) {
                throw new ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "El audio supera el tamaño permitido."
                );
            }

            if (status == 415 || status == 422) {
                throw new ResponseStatusException(
                        exception.getStatusCode(),
                        "El audio no pudo procesarse."
                );
            }

            if (status == 504) {
                throw new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "La transcripción excedió el tiempo de espera."
                );
            }

            if (status == 503) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "El servicio de transcripción no está disponible."
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Error al transcribir el audio."
            );

        } catch (ResourceAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo conectar con FastAPI."
            );
        }
    }
}