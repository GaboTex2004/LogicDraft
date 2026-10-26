package com.sw1.backend.generator.ea;

import com.sw1.backend.diagrama.validation.DiagramDocumentValidator;
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

    public EnterpriseArchitectImportService(EnterpriseArchitectXmiReader reader,
            ProyectoService proyectoService, DiagramaService diagramaService) {
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

        // 1. Interpretar el XMI.
        EnterpriseArchitectImportPreview preview = reader.read(xmi);

        String nombre = preview.projectName();

        if (nombre == null || nombre.isBlank() || nombre.length() > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "El nombre del proyecto importado no es válido."
            );
        }

        // 2. Construir el documento UML.
        GuardarDiagramaRequest documento = new GuardarDiagramaRequest();
        documento.setVersion(preview.version());
        documento.setNodes(preview.nodes());
        documento.setEdges(preview.edges());

        if (!documento.isDocumentoValido()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "El documento importado no cumple los requisitos básicos."
            );
        }

        Map<String, Object> contenido = new LinkedHashMap<>();
        contenido.put("version", preview.version());
        contenido.put("nodes", preview.nodes());
        contenido.put("edges", preview.edges());

        // 3. Validar con el contrato UML oficial.
        // No utilizar DiagramContextMapper:
        // ese componente prepara el contexto de IA,
        // no valida el formato UML general.
        DiagramDocumentValidator.validate(contenido);

        // 4. Crear el proyecto autorizado en el workspace.
        CrearProyectoRequest solicitud = new CrearProyectoRequest();
        solicitud.setNombre(nombre);
        solicitud.setDescripcion(
                "Proyecto importado desde Enterprise Architect."
        );
        solicitud.setWorkspaceId(workspaceId);

        ProyectoResponse proyecto = proyectoService.crear(solicitud);

        // 5. Guardar usando el servicio normal.
        // Este vuelve a validar antes de persistir.
        diagramaService.guardar(proyecto.getId(), documento);

        return proyecto;
    }

}
