package com.sw1.backend.generator.ea;

import com.sw1.backend.ai.diagram.validation.DiagramContextMapper;
import com.sw1.backend.diagrama.dto.request.GuardarDiagramaRequest;
import com.sw1.backend.diagrama.service.DiagramaService;
import com.sw1.backend.proyecto.dto.request.CrearProyectoRequest;
import com.sw1.backend.proyecto.dto.response.ProyectoResponse;
import com.sw1.backend.proyecto.service.ProyectoService;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EnterpriseArchitectImportService {

    private final EnterpriseArchitectXmiReader reader;
    private final ProyectoService proyectoService;
    private final DiagramaService diagramaService;

    public EnterpriseArchitectImportService(
            EnterpriseArchitectXmiReader reader,
            ProyectoService proyectoService,
            DiagramaService diagramaService
    ) {
        this.reader = reader;
        this.proyectoService = proyectoService;
        this.diagramaService = diagramaService;
    }

    @Transactional
    public ProyectoResponse importar(Long workspaceId, byte[] xmi) {

        if (workspaceId == null || workspaceId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El workspaceId no es válido."
            );
        }

        // Primero interpretamos el archivo, sin guardar nada.
        EnterpriseArchitectImportPreview preview = reader.read(xmi);

        String nombre = preview.projectName();

        if (nombre == null
                || nombre.isBlank()
                || nombre.length() > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El nombre del proyecto importado no es válido."
            );
        }

        // No confirmar importaciones que puedan perder información.
        if (preview.warnings() != null
                && !preview.warnings().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "La importación contiene advertencias. "
                            + "Revisa el XMI antes de continuar."
            );
        }

        GuardarDiagramaRequest documento =
                new GuardarDiagramaRequest();

        documento.setVersion(preview.version());
        documento.setNodes(preview.nodes());
        documento.setEdges(preview.edges());

        if (!documento.isDocumentoValido()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "El diagrama importado no cumple "
                            + "las restricciones de LogicDraft."
            );
        }

        // Verificamos que los datos puedan interpretarse
        // antes de crear cualquier registro.
        Map<String, Object> contenido = new LinkedHashMap<>();
        contenido.put("version", preview.version());
        contenido.put("nodes", preview.nodes());
        contenido.put("edges", preview.edges());

        DiagramContextMapper.fromDocument(contenido);

        // ProyectoService comprueba los permisos OWNER/EDITOR.
        CrearProyectoRequest solicitud =
                new CrearProyectoRequest();

        solicitud.setNombre(nombre);
        solicitud.setDescripcion(
                "Proyecto importado desde Enterprise Architect."
        );
        solicitud.setWorkspaceId(workspaceId);

        ProyectoResponse proyecto =
                proyectoService.crear(solicitud);

        // Se guarda dentro de la misma transacción.
        // Si falla, también se revierte la creación del proyecto.
        diagramaService.guardar(proyecto.getId(), documento);

        return proyecto;
    }
}