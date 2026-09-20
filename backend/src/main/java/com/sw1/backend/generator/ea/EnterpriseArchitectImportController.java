package com.sw1.backend.generator.ea;

import com.sw1.backend.proyecto.dto.response.ProyectoResponse;

import java.io.IOException;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/import/enterprise-architect")
public class EnterpriseArchitectImportController {

    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;

    private final EnterpriseArchitectXmiReader reader;
    private final EnterpriseArchitectImportService importService;

    public EnterpriseArchitectImportController(
            EnterpriseArchitectXmiReader reader,
            EnterpriseArchitectImportService importService
    ) {
        this.reader = reader;
        this.importService = importService;
    }

    // PASO 1: Obtener vista previa sin guardar nada.
    @PostMapping(
            value = "/preview",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public EnterpriseArchitectImportPreview preview(
            @RequestParam("file") MultipartFile file
    ) {
        return reader.read(leerArchivo(file));
    }

    // PASO 2: Confirmar la importación.
    // Crea el proyecto y guarda el diagrama en una transacción.
    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.CREATED)
    public ProyectoResponse importar(
            @RequestParam("workspaceId") Long workspaceId,
            @RequestParam("file") MultipartFile file
    ) {
        return importService.importar(
                workspaceId,
                leerArchivo(file)
        );
    }

    private byte[] leerArchivo(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Debes seleccionar un archivo XMI."
            );
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "El archivo XMI supera el límite de 5 MB."
            );
        }

        String filename = file.getOriginalFilename();

        if (filename == null
                || !filename.toLowerCase(Locale.ROOT).endsWith(".xmi")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El archivo debe tener extensión .xmi."
            );
        }

        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "No se pudo leer el archivo XMI.",
                    exception
            );
        }
    }
}