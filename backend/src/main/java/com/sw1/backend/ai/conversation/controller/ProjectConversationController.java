package com.sw1.backend.ai.conversation.controller;

import com.sw1.backend.ai.conversation.dto.ConversationMessagesPageResponse;
import com.sw1.backend.ai.conversation.dto.ConversationSummaryResponse;
import com.sw1.backend.ai.conversation.dto.CreateConversationRequest;
import com.sw1.backend.ai.conversation.dto.ProjectConversationsListResponse;
import com.sw1.backend.ai.conversation.dto.ProposalDecisionRequest;
import com.sw1.backend.ai.conversation.dto.SendConversationMessageRequest;
import com.sw1.backend.ai.conversation.dto.SendMessageResultResponse;
import com.sw1.backend.ai.conversation.dto.SharedProposalResponse;
import com.sw1.backend.ai.conversation.dto.UpdateConversationRequest;
import com.sw1.backend.ai.conversation.service.SharedAgentConversationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/proyectos/{projectId}/conversaciones")
public class ProjectConversationController {

    private final SharedAgentConversationService conversationService;

    public ProjectConversationController(SharedAgentConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping
    public ProjectConversationsListResponse listarConversaciones(
            @PathVariable Long projectId,
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return conversationService.listarConversaciones(projectId, includeArchived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationSummaryResponse crearConversacion(
            @PathVariable Long projectId,
            @Valid @RequestBody CreateConversationRequest request) {
        return conversationService.crearConversacion(projectId, request);
    }

    @PatchMapping("/{conversationId}")
    public ConversationSummaryResponse actualizarConversacion(
            @PathVariable Long projectId,
            @PathVariable Long conversationId,
            @Valid @RequestBody UpdateConversationRequest request) {
        return conversationService.actualizarConversacion(projectId, conversationId, request);
    }

    @GetMapping("/{conversationId}/mensajes")
    public ConversationMessagesPageResponse listarMensajes(
            @PathVariable Long projectId,
            @PathVariable Long conversationId,
            @RequestParam(name = "beforeId", required = false) Long beforeId,
            @RequestParam(name = "limit", required = false) Integer limit) {
        return conversationService.listarMensajes(projectId, conversationId, beforeId, limit);
    }

    @PostMapping("/{conversationId}/mensajes")
    @ResponseStatus(HttpStatus.CREATED)
    public SendMessageResultResponse enviarMensaje(
            @PathVariable Long projectId,
            @PathVariable Long conversationId,
            @Valid @RequestBody SendConversationMessageRequest request) {
        return conversationService.enviarMensaje(projectId, conversationId, request);
    }

    @PostMapping("/{conversationId}/propuestas/{proposalId}/aceptar")
    public SharedProposalResponse aceptarPropuesta(
            @PathVariable Long projectId,
            @PathVariable Long conversationId,
            @PathVariable Long proposalId,
            @Valid @RequestBody(required = false) ProposalDecisionRequest request) {
        return conversationService.aceptarPropuesta(projectId, conversationId, proposalId, request);
    }

    @PostMapping("/{conversationId}/propuestas/{proposalId}/rechazar")
    public SharedProposalResponse rechazarPropuesta(
            @PathVariable Long projectId,
            @PathVariable Long conversationId,
            @PathVariable Long proposalId,
            @Valid @RequestBody(required = false) ProposalDecisionRequest request) {
        return conversationService.rechazarPropuesta(projectId, conversationId, proposalId, request);
    }
}
