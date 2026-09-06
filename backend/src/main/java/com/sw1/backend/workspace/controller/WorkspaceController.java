package com.sw1.backend.workspace.controller;

import com.sw1.backend.workspace.dto.response.WorkspaceResponse;
import com.sw1.backend.workspace.service.WorkspaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    public WorkspaceController(
            WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping
    public ResponseEntity<List<WorkspaceResponse>>
    listarMisWorkspaces() {

        return ResponseEntity.ok(
                workspaceService.listarMisWorkspaces()
        );
    }
}