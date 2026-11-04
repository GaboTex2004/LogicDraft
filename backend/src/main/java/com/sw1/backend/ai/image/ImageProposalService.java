package com.sw1.backend.ai.image;

import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.diagrama.dto.request.GuardarDiagramaRequest;
import com.sw1.backend.diagrama.dto.response.DiagramaResponse;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.diagrama.service.DiagramaService;
import com.sw1.backend.diagrama.service.DiagramConflictException;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Ephemeral, bounded proposals. Only the server retains recognition and the candidate document. */
@Service
public class ImageProposalService {
    public record Snapshot(Long projectId, Long userId, Long revision, Map<String, Object> document) {}
    public record Proposal(String proposalId, Long baseRevision, List<String> matched, List<String> additions,
                           List<String> conflicts, List<String> warnings, boolean canApply) {}
    private record Pending(Snapshot snapshot, ImageDiagramReconciler.Result result, Instant expires) {}
    private final Map<String, Pending> pending = new HashMap<>();
    private final ProyectoRepository projects;
    private final DiagramaRepository diagrams;
    private final WorkspaceAccessService access;
    private final UsuarioActualService users;
    private final DiagramaService persistence;

    public ImageProposalService(ProyectoRepository projects, DiagramaRepository diagrams,
            WorkspaceAccessService access, UsuarioActualService users, DiagramaService persistence) {
        this.projects = projects; this.diagrams = diagrams; this.access = access;
        this.users = users; this.persistence = persistence;
    }

    @Transactional(readOnly = true)
    public Snapshot load(Long projectId) {
        var project = projects.findById(projectId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El proyecto no existe"));
        access.verificarRol(project.getWorkspace().getId(), RolWorkspace.OWNER, RolWorkspace.EDITOR);
        var diagram = diagrams.findByProyectoId(projectId).orElse(null);
        return new Snapshot(projectId, users.obtenerUsuarioActual().getId(), diagram == null ? null : diagram.getRevision(),
                diagram == null ? new LinkedHashMap<>(Map.of("version", 1, "nodes", List.of(), "edges", List.of()))
                        : ImageDiagramReconciler.copy(diagram.getContenido()));
    }

    public synchronized Proposal prepare(Snapshot snapshot, Map<String, Object> recognition) {
        var result = ImageDiagramReconciler.reconcile(snapshot.document(), recognition);
        pending.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(Instant.now()));
        if (pending.size() >= 100) throw new AiServiceException(HttpStatus.TOO_MANY_REQUESTS,
                "Hay demasiadas propuestas de imagen pendientes; espera unos minutos.");
        String id = UUID.randomUUID().toString();
        pending.put(id, new Pending(snapshot, result, Instant.now().plusSeconds(600)));
        return new Proposal(id, snapshot.revision(), result.matched(), result.additions(),
                result.conflicts(), result.warnings(), result.canApply());
    }

    @Transactional
    public DiagramaResponse accept(Long projectId, String proposalId, String clientId) {
        Snapshot current = load(projectId); // Reauthorize before revealing or accepting a token.
        Pending proposal;
        synchronized (this) { proposal = pending.get(proposalId); }
        if (proposal == null || proposal.expires().isBefore(Instant.now())
                || !Objects.equals(proposal.snapshot().projectId(), projectId)
                || !Objects.equals(proposal.snapshot().userId(), current.userId())) {
            throw new AiServiceException(HttpStatus.CONFLICT, "Propuesta no disponible o expirada. Vuelve a analizar la imagen.");
        }
        if (!Objects.equals(current.revision(), proposal.snapshot().revision()))
            throw new DiagramConflictException(projectId, current.revision());
        if (!proposal.result().canApply())
            throw new AiServiceException(HttpStatus.UNPROCESSABLE_ENTITY, "La propuesta contiene conflictos o no tiene altas.");
        // Never accept a client-supplied document, operation, revision or user identity.
        Map<String, Object> document = ImageDiagramReconciler.copy(proposal.result().document());
        var request = new GuardarDiagramaRequest();
        request.setVersion((Integer) document.get("version"));
        request.setNodes(ImageRecognitionValidator.objects(document.get("nodes"), 2000));
        request.setEdges(ImageRecognitionValidator.objects(document.get("edges"), 5000));
        request.setBaseRevision(proposal.snapshot().revision());
        request.setClientId(clientId);
        // Existing service validates the whole document, checks locks, flushes once and publishes AFTER_COMMIT.
        // Strict path must never use the ordinary editor's three-way merge for stale image proposals.
        return persistence.guardarSinRebase(projectId, request);
    }
}
