package com.sw1.backend.collaboration.service;

import com.sw1.backend.collaboration.dto.ElementLockInfo;

public class ElementLockedException extends RuntimeException {
    private final Long projectId;
    private final String elementId;
    private final Long ownerUserId;
    private final String ownerName;
    private final String ownerClientId;

    public ElementLockedException(ElementLockInfo lock) {
        super("Está siendo usado por " + lock.userName());
        this.projectId = lock.projectId();
        this.elementId = lock.elementId();
        this.ownerUserId = lock.userId();
        this.ownerName = lock.userName();
        this.ownerClientId = lock.clientId();
    }

    public Long getProjectId() {
        return projectId;
    }

    public String getElementId() {
        return elementId;
    }

    public Long getOwnerUserId() {
        return ownerUserId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getOwnerClientId() {
        return ownerClientId;
    }
}
