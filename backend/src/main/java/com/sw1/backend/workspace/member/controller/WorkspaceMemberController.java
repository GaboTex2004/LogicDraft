package com.sw1.backend.workspace.member.controller;

import com.sw1.backend.workspace.member.dto.AgregarMiembroWorkspaceRequest;
import com.sw1.backend.workspace.member.dto.CambiarRolMiembroRequest;
import com.sw1.backend.workspace.member.dto.MiembroWorkspaceResponse;
import com.sw1.backend.workspace.member.service.WorkspaceMemberService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces/{workspaceId}/miembros")
public class WorkspaceMemberController {
    private final WorkspaceMemberService service;

    public WorkspaceMemberController(WorkspaceMemberService service) {
        this.service = service;
    }

    @GetMapping
    public List<MiembroWorkspaceResponse> list(@PathVariable Long workspaceId) {
        return service.list(workspaceId);
    }

    @PostMapping
    public ResponseEntity<MiembroWorkspaceResponse> add(
            @PathVariable Long workspaceId,
            @Valid @RequestBody AgregarMiembroWorkspaceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.add(workspaceId, request));
    }

    @PutMapping("/{userId}")
    public MiembroWorkspaceResponse changeRole(
            @PathVariable Long workspaceId,
            @PathVariable Long userId,
            @Valid @RequestBody CambiarRolMiembroRequest request) {
        return service.changeRole(workspaceId, userId, request);
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> remove(
            @PathVariable Long workspaceId,
            @PathVariable Long userId) {
        service.remove(workspaceId, userId);
        return ResponseEntity.noContent().build();
    }
}
