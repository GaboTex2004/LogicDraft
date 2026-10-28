package com.sw1.backend.diagrama.service;

import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.collaboration.service.DiagramCommitPublisher;
import com.sw1.backend.collaboration.service.ProjectElementLockService;
import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.diagrama.dto.request.GuardarDiagramaRequest;
import com.sw1.backend.diagrama.dto.response.DiagramaResponse;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.diagrama.validation.DiagramDocumentValidator;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional
public class DiagramaService {
    static final Set<Integer> VERSIONES_SOPORTADAS = Set.of(1, 2);
    static final int TAMANO_MAXIMO_BYTES = 1_000_000;

    private final DiagramaRepository diagramaRepository;
    private final ProyectoRepository proyectoRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final DiagramCommitPublisher commitPublisher;
    private final DiagramRevisionHistory revisionHistory;
    private final ProjectElementLockService elementLockService;
    private final UsuarioActualService usuarioActualService;

    @Autowired
    public DiagramaService(
            DiagramaRepository diagramaRepository,
            ProyectoRepository proyectoRepository,
            WorkspaceAccessService workspaceAccessService,
            DiagramCommitPublisher commitPublisher,
            DiagramRevisionHistory revisionHistory,
            ProjectElementLockService elementLockService,
            UsuarioActualService usuarioActualService) {
        this.diagramaRepository = diagramaRepository;
        this.proyectoRepository = proyectoRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.commitPublisher = commitPublisher;
        this.revisionHistory = revisionHistory != null ? revisionHistory : new DiagramRevisionHistory();
        this.elementLockService = elementLockService;
        this.usuarioActualService = usuarioActualService;
    }

    public DiagramaService(
            DiagramaRepository diagramaRepository,
            ProyectoRepository proyectoRepository,
            WorkspaceAccessService workspaceAccessService,
            DiagramCommitPublisher commitPublisher) {
        this(diagramaRepository, proyectoRepository, workspaceAccessService, commitPublisher,
                new DiagramRevisionHistory(), null, null);
    }

    @Transactional(readOnly = true)
    public DiagramaResponse obtenerPorProyecto(Long proyectoId) {
        Proyecto proyecto = obtenerProyecto(proyectoId);
        workspaceAccessService.verificarAcceso(proyecto.getWorkspace().getId());
        Diagrama diagrama = diagramaRepository.findByProyectoId(proyectoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El proyecto todavia no tiene un diagrama guardado"));
        revisionHistory.record(proyectoId, diagrama.getRevision(), diagrama.getContenido());
        return convertirAResponse(diagrama);
    }

    public DiagramaResponse guardar(Long proyectoId, GuardarDiagramaRequest request) {
        Proyecto proyecto = obtenerProyecto(proyectoId);
        workspaceAccessService.verificarRol(proyecto.getWorkspace().getId(), RolWorkspace.OWNER, RolWorkspace.EDITOR);
        Map<String, Object> incomingContenido = crearYValidarContenido(request);
        Diagrama diagrama = diagramaRepository.findByProyectoId(proyectoId).orElse(null);

        Map<String, Object> contenidoToPersist;
        if (diagrama == null) {
            if (request.getBaseRevision() != null) throw new DiagramConflictException(proyectoId, null);
            diagrama = new Diagrama();
            diagrama.setProyecto(proyecto);
            contenidoToPersist = incomingContenido;
        } else {
            Long currentRevision = diagrama.getRevision();
            Map<String, Object> currentContenido = diagrama.getContenido();
            if (currentRevision != null && currentContenido != null) {
                revisionHistory.record(proyectoId, currentRevision, currentContenido);
            }

            Long baseRevision = request.getBaseRevision();
            if (baseRevision == null) {
                throw new DiagramConflictException(proyectoId, currentRevision);
            } else if (baseRevision.equals(currentRevision)) {
                verifyLocksForDiff(proyectoId, currentContenido, incomingContenido, request.getClientId());
                contenidoToPersist = incomingContenido;
            } else if (currentRevision != null && baseRevision < currentRevision) {
                Optional<Map<String, Object>> baseSnapshot = revisionHistory.find(proyectoId, baseRevision);
                if (baseSnapshot.isEmpty()) {
                    throw new DiagramConflictException(proyectoId, currentRevision);
                }
                verifyLocksForDiff(proyectoId, baseSnapshot.get(), incomingContenido, request.getClientId());
                contenidoToPersist = DiagramThreeWayMerge.tryMerge(
                        baseSnapshot.get(),
                        currentContenido,
                        incomingContenido)
                        .orElseThrow(() -> new DiagramConflictException(proyectoId, currentRevision));
            } else {
                throw new DiagramConflictException(proyectoId, currentRevision);
            }
        }

        Integer versionToPersist = contenidoToPersist.get("version") instanceof Integer v
                ? v
                : request.getVersion();
        diagrama.setVersion(versionToPersist);
        diagrama.setContenido(contenidoToPersist);

        DiagramaResponse saved;
        try {
            saved = convertirAResponse(diagramaRepository.saveAndFlush(diagrama));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw new DiagramConflictException(proyectoId, null);
        }

        revisionHistory.record(proyectoId, saved.revision(), saved.contenido());
        commitPublisher.schedule(saved, request.getClientId());
        return saved;
    }

    private void verifyLocksForDiff(
            Long proyectoId,
            Map<String, Object> baseContenido,
            Map<String, Object> incomingContenido,
            String clientId) {
        if (elementLockService == null || usuarioActualService == null || baseContenido == null) {
            return;
        }
        Set<String> touchedIds = DiagramThreeWayMerge
                .diffElements(baseContenido, incomingContenido)
                .lockProtectedElementIds();
        if (touchedIds.isEmpty()) {
            return;
        }
        Long currentUserId = usuarioActualService.obtenerUsuarioActual().getId();
        elementLockService.verifyCanModify(proyectoId, touchedIds, currentUserId, clientId);
    }

    private Map<String, Object> crearYValidarContenido(GuardarDiagramaRequest request) {
        if (!VERSIONES_SOPORTADAS.contains(request.getVersion())) {
            throw new IllegalArgumentException("La version del documento no esta soportada");
        }
        Map<String, Object> contenido = new LinkedHashMap<>();
        contenido.put("version", request.getVersion());
        contenido.put("nodes", request.getNodes());
        contenido.put("edges", request.getEdges());
        if (contenido.toString().getBytes(StandardCharsets.UTF_8).length > TAMANO_MAXIMO_BYTES) {
            throw new IllegalArgumentException("El documento supera el tamano maximo permitido");
        }
        DiagramDocumentValidator.validate(contenido);
        return contenido;
    }

    private DiagramaResponse convertirAResponse(Diagrama diagrama) {
        return new DiagramaResponse(
                diagrama.getId(),
                diagrama.getProyecto().getId(),
                diagrama.getVersion(),
                diagrama.getRevision(),
                diagrama.getContenido(),
                diagrama.getFechaActualizacion());
    }

    private Proyecto obtenerProyecto(Long proyectoId) {
        return proyectoRepository.findById(proyectoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe un proyecto con el id " + proyectoId));
    }
}
