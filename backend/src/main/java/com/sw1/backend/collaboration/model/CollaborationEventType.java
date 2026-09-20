package com.sw1.backend.collaboration.model;

public enum CollaborationEventType {
    USER_JOINED, USER_LEFT, PING,
    NODE_CREATED, NODE_MOVED, NODE_UPDATED, NODE_DELETED,
    EDGE_CREATED, EDGE_UPDATED, EDGE_DELETED,
    DIAGRAM_BATCH_APPLIED,
    DIAGRAM_SAVED
}
