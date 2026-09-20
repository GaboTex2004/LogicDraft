package com.sw1.backend.generator.ea;

import com.sw1.backend.diagrama.dto.response.DiagramaResponse;
import com.sw1.backend.diagrama.service.DiagramaService;
import com.sw1.backend.proyecto.dto.response.ProyectoResponse;
import com.sw1.backend.proyecto.service.ProyectoService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnterpriseArchitectExportSourceService {

    private final ProyectoService proyectoService;
    private final DiagramaService diagramaService;

    public EnterpriseArchitectExportSourceService(
            ProyectoService proyectoService,
            DiagramaService diagramaService
    ) {
        this.proyectoService = proyectoService;
        this.diagramaService = diagramaService;
    }

    @Transactional(readOnly = true)
    public ExportSnapshot load(Long proyectoId) {

        // Reutiliza las verificaciones de acceso existentes.
        ProyectoResponse proyecto =
                proyectoService.buscarPorId(proyectoId);

        // Recupera el documento JSON guardado, no una propuesta pendiente.
        DiagramaResponse diagrama =
                diagramaService.obtenerPorProyecto(proyectoId);

        return new ExportSnapshot(proyecto, diagrama);
    }

    public record ExportSnapshot(
            ProyectoResponse proyecto,
            DiagramaResponse diagrama
    ) {}
}