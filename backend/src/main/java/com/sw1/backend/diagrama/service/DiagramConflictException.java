package com.sw1.backend.diagrama.service;

public class DiagramConflictException extends RuntimeException {
    private final Long projectId;
    private final Long currentRevision;

    public DiagramConflictException(Long projectId, Long currentRevision) {
        super("El diagrama fue actualizado por otro usuario. Recarga el estado confirmado antes de guardar.");
        this.projectId = projectId;
        this.currentRevision = currentRevision;
    }

    public Long getProjectId() { return projectId; }
    public Long getCurrentRevision() { return currentRevision; }
}
