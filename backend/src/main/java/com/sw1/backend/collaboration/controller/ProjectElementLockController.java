package com.sw1.backend.collaboration.controller;

import com.sw1.backend.collaboration.dto.ElementLockInfo;
import com.sw1.backend.collaboration.dto.ElementLockRequest;
import com.sw1.backend.collaboration.security.CollaborationIdentity;
import com.sw1.backend.collaboration.security.ProjectCollaborationAuthorizer;
import com.sw1.backend.collaboration.service.ProjectElementLockService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/proyectos/{proyectoId}/locks")
public class ProjectElementLockController {
    private final ProjectCollaborationAuthorizer authorizer;
    private final ProjectElementLockService lockService;

    public ProjectElementLockController(
            ProjectCollaborationAuthorizer authorizer,
            ProjectElementLockService lockService) {
        this.authorizer = authorizer;
        this.lockService = lockService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listar(
            @PathVariable Long proyectoId,
            Principal principal) {
        authorizer.authorize(proyectoId, principal);
        List<Map<String, Object>> locks = lockService.listActiveLocks(proyectoId).stream()
                .map(ProjectElementLockService::toPayloadMap)
                .toList();
        return ResponseEntity.ok(Map.of("locks", locks));
    }

    @PostMapping("/acquire")
    public ResponseEntity<Map<String, Object>> adquirir(
            @PathVariable Long proyectoId,
            @Valid @RequestBody ElementLockRequest request,
            Principal principal) {
        CollaborationIdentity identity = authorizer.authorizeEditor(proyectoId, principal);
        List<ElementLockInfo> granted = lockService.acquire(
                proyectoId,
                identity,
                request.getClientId(),
                null,
                request.getElementIds());
        return ResponseEntity.ok(Map.of(
                "granted", true,
                "locks", granted.stream().map(ProjectElementLockService::toPayloadMap).toList()));
    }

    @PostMapping("/renew")
    public ResponseEntity<Map<String, Object>> renovar(
            @PathVariable Long proyectoId,
            @Valid @RequestBody ElementLockRequest request,
            Principal principal) {
        CollaborationIdentity identity = authorizer.authorizeEditor(proyectoId, principal);
        List<ElementLockInfo> renewed = lockService.renew(
                proyectoId,
                identity,
                request.getClientId(),
                request.getElementIds());
        return ResponseEntity.ok(Map.of(
                "locks", renewed.stream().map(ProjectElementLockService::toPayloadMap).toList()));
    }

    @PostMapping("/release")
    public ResponseEntity<Map<String, Object>> liberar(
            @PathVariable Long proyectoId,
            @Valid @RequestBody ElementLockRequest request,
            Principal principal) {
        CollaborationIdentity identity = authorizer.authorizeEditor(proyectoId, principal);
        List<String> released = lockService.release(
                proyectoId,
                identity,
                request.getClientId(),
                request.getElementIds());
        return ResponseEntity.ok(Map.of("releasedElementIds", released));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> manejarSolicitudInvalida(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", 400,
                "error", "Bad Request",
                "mensaje", exception.getMessage()));
    }
}
