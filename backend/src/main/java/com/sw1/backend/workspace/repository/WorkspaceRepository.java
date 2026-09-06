package com.sw1.backend.workspace.repository;

import com.sw1.backend.workspace.model.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {
}
