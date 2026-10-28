package com.sw1.backend.ai.conversation.service;

import com.sw1.backend.ai.agent.dto.AgentAskRequest;
import com.sw1.backend.ai.agent.dto.AgentAskResponse;
import com.sw1.backend.ai.agent.dto.AgentConversationMessage;
import com.sw1.backend.ai.agent.service.ContextualAgentService;
import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.conversation.dto.ConversationMessageResponse;
import com.sw1.backend.ai.conversation.dto.ConversationMessagesPageResponse;
import com.sw1.backend.ai.conversation.dto.ConversationSummaryResponse;
import com.sw1.backend.ai.conversation.dto.CreateConversationRequest;
import com.sw1.backend.ai.conversation.dto.ProjectConversationsListResponse;
import com.sw1.backend.ai.conversation.dto.ProposalDecisionRequest;
import com.sw1.backend.ai.conversation.dto.SendConversationMessageRequest;
import com.sw1.backend.ai.conversation.dto.SendMessageResultResponse;
import com.sw1.backend.ai.conversation.dto.SharedProposalResponse;
import com.sw1.backend.ai.conversation.dto.UpdateConversationRequest;
import com.sw1.backend.ai.conversation.exception.ConversationConflictException;
import com.sw1.backend.ai.conversation.exception.ConversationValidationException;
import com.sw1.backend.ai.conversation.model.AgentProposalState;
import com.sw1.backend.ai.conversation.model.ConversacionAgente;
import com.sw1.backend.ai.conversation.model.ConversationMessageStatus;
import com.sw1.backend.ai.conversation.model.ConversationState;
import com.sw1.backend.ai.conversation.model.MensajeConversacionAgente;
import com.sw1.backend.ai.conversation.model.MessageAuthorType;
import com.sw1.backend.ai.conversation.model.PropuestaUmlAgente;
import com.sw1.backend.ai.conversation.repository.ConversacionAgenteRepository;
import com.sw1.backend.ai.conversation.repository.MensajeConversacionAgenteRepository;
import com.sw1.backend.ai.conversation.repository.PropuestaUmlAgenteRepository;
import com.sw1.backend.ai.diagram.dto.DiagramOperation;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper;
import com.sw1.backend.ai.diagram.validation.SemanticOperationValidator;
import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.collaboration.service.DiagramCommitPublisher;
import com.sw1.backend.collaboration.service.ProjectElementLockService;
import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.diagrama.dto.response.DiagramaResponse;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.diagrama.service.DiagramRevisionHistory;
import com.sw1.backend.diagrama.validation.DiagramValidationException;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.usuario.model.Usuario;
import com.sw1.backend.workspace.model.MiembroWorkspace;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class SharedAgentConversationService {
    private static final Logger log = LoggerFactory.getLogger(SharedAgentConversationService.class);

    public static final String AGENT_DISPLAY_NAME = "LogicDraft IA";
    static final int DEFAULT_PAGE_LIMIT = 30;
    static final int MAX_PAGE_LIMIT = 100;
    static final int MAX_CONVERSATIONS_PER_PROJECT = 200;
    static final int MAX_TITLE_LENGTH = 140;
    static final int MAX_MESSAGE_LENGTH = 4000;
    static final int RECENT_CONTEXT_SCAN_LIMIT = 25;
    private static final Pattern SAFE_TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_.:-]{1,100}$");

    private final ConversacionAgenteRepository conversacionRepository;
    private final MensajeConversacionAgenteRepository mensajeRepository;
    private final PropuestaUmlAgenteRepository propuestaRepository;
    private final ProyectoRepository proyectoRepository;
    private final DiagramaRepository diagramaRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final UsuarioActualService usuarioActualService;
    private final ContextualAgentService contextualAgentService;
    private final ProjectElementLockService elementLockService;
    private final DiagramCommitPublisher diagramCommitPublisher;
    private final DiagramRevisionHistory revisionHistory;
    private final AgentConversationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public SharedAgentConversationService(
            ConversacionAgenteRepository conversacionRepository,
            MensajeConversacionAgenteRepository mensajeRepository,
            PropuestaUmlAgenteRepository propuestaRepository,
            ProyectoRepository proyectoRepository,
            DiagramaRepository diagramaRepository,
            WorkspaceAccessService workspaceAccessService,
            UsuarioActualService usuarioActualService,
            ContextualAgentService contextualAgentService,
            ProjectElementLockService elementLockService,
            DiagramCommitPublisher diagramCommitPublisher,
            DiagramRevisionHistory revisionHistory,
            AgentConversationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.conversacionRepository = conversacionRepository;
        this.mensajeRepository = mensajeRepository;
        this.propuestaRepository = propuestaRepository;
        this.proyectoRepository = proyectoRepository;
        this.diagramaRepository = diagramaRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.usuarioActualService = usuarioActualService;
        this.contextualAgentService = contextualAgentService;
        this.elementLockService = elementLockService;
        this.diagramCommitPublisher = diagramCommitPublisher;
        this.revisionHistory = revisionHistory;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public SharedAgentConversationService(
            ConversacionAgenteRepository conversacionRepository,
            MensajeConversacionAgenteRepository mensajeRepository,
            PropuestaUmlAgenteRepository propuestaRepository,
            ProyectoRepository proyectoRepository,
            DiagramaRepository diagramaRepository,
            WorkspaceAccessService workspaceAccessService,
            UsuarioActualService usuarioActualService,
            ContextualAgentService contextualAgentService,
            AgentConversationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this(
                conversacionRepository,
                mensajeRepository,
                propuestaRepository,
                proyectoRepository,
                diagramaRepository,
                workspaceAccessService,
                usuarioActualService,
                contextualAgentService,
                null,
                null,
                null,
                eventPublisher,
                transactionManager);
    }

    public SharedAgentConversationService(
            ConversacionAgenteRepository conversacionRepository,
            MensajeConversacionAgenteRepository mensajeRepository,
            PropuestaUmlAgenteRepository propuestaRepository,
            ProyectoRepository proyectoRepository,
            WorkspaceAccessService workspaceAccessService,
            UsuarioActualService usuarioActualService,
            AgentConversationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this(
                conversacionRepository,
                mensajeRepository,
                propuestaRepository,
                proyectoRepository,
                null,
                workspaceAccessService,
                usuarioActualService,
                null,
                null,
                null,
                null,
                eventPublisher,
                transactionManager);
    }

    public ProjectConversationsListResponse listarConversaciones(Long projectId, boolean includeArchived) {
        return transactionTemplate.execute(status -> {
            Proyecto proyecto = obtenerProyecto(projectId);
            MiembroWorkspace membresia = workspaceAccessService.obtenerMembresia(proyecto.getWorkspace().getId());
            boolean canEdit = membresia.getRol() == RolWorkspace.OWNER || membresia.getRol() == RolWorkspace.EDITOR;
            List<ConversacionAgente> conversaciones = includeArchived
                    ? conversacionRepository.findByProyectoIdOrderByFechaActualizacionDescIdDesc(projectId)
                    : conversacionRepository.findByProyectoIdAndEstadoOrderByFechaActualizacionDescIdDesc(
                            projectId, ConversationState.ACTIVE);
            return new ProjectConversationsListResponse(
                    projectId,
                    canEdit,
                    conversaciones.stream().map(this::toSummaryResponse).toList());
        });
    }

    public ConversationSummaryResponse crearConversacion(Long projectId, CreateConversationRequest request) {
        String titulo = normalizarTitulo(request == null ? null : request.titulo());
        String clientId = normalizarTokenOpcional(request == null ? null : request.clientId(), "clientId");
        return transactionTemplate.execute(status -> {
            Proyecto proyecto = obtenerProyectoParaEdicion(projectId);
            Usuario actual = usuarioActualService.obtenerUsuarioActual();
            if (conversacionRepository.countByProyectoId(projectId) >= MAX_CONVERSATIONS_PER_PROJECT) {
                throw new ConversationValidationException(
                        "El proyecto alcanzó el límite máximo de conversaciones permitidas");
            }
            LocalDateTime ahora = LocalDateTime.now();
            ConversacionAgente conversacion = new ConversacionAgente();
            conversacion.setProyecto(proyecto);
            conversacion.setTitulo(titulo);
            conversacion.setEstado(ConversationState.ACTIVE);
            conversacion.setCreadoPor(actual);
            conversacion.setCreadoPorNombre(actual.getNombre());
            conversacion.setFechaCreacion(ahora);
            conversacion.setFechaActualizacion(ahora);
            ConversacionAgente guardada = conversacionRepository.saveAndFlush(conversacion);
            ConversationSummaryResponse response = toSummaryResponse(guardada);
            eventPublisher.scheduleConversationCreated(response, actual, clientId);
            return response;
        });
    }

    public ConversationSummaryResponse actualizarConversacion(
            Long projectId,
            Long conversationId,
            UpdateConversationRequest request) {
        if (request == null || (request.titulo() == null && request.estado() == null)) {
            throw new ConversationValidationException("Indica un nuevo título o estado para la conversación");
        }
        String nuevoTitulo = request.titulo() != null ? normalizarTitulo(request.titulo()) : null;
        String clientId = normalizarTokenOpcional(request.clientId(), "clientId");
        return transactionTemplate.execute(status -> {
            obtenerProyectoParaEdicion(projectId);
            Usuario actual = usuarioActualService.obtenerUsuarioActual();
            ConversacionAgente conversacion = obtenerConversacionDelProyecto(projectId, conversationId);
            if (nuevoTitulo != null) {
                conversacion.setTitulo(nuevoTitulo);
            }
            if (request.estado() != null) {
                conversacion.setEstado(request.estado());
            }
            conversacion.setFechaActualizacion(LocalDateTime.now());
            ConversacionAgente guardada = conversacionRepository.saveAndFlush(conversacion);
            ConversationSummaryResponse response = toSummaryResponse(guardada);
            eventPublisher.scheduleConversationUpdated(response, actual, clientId);
            return response;
        });
    }

    public ConversationMessagesPageResponse listarMensajes(
            Long projectId,
            Long conversationId,
            Long beforeId,
            Integer limit) {
        int resolvedLimit = validarLimitePagina(limit);
        if (beforeId != null && beforeId <= 0) {
            throw new ConversationValidationException("El cursor beforeId debe ser positivo");
        }
        return transactionTemplate.execute(status -> {
            Proyecto proyecto = obtenerProyecto(projectId);
            workspaceAccessService.verificarAcceso(proyecto.getWorkspace().getId());
            obtenerConversacionDelProyecto(projectId, conversationId);

            PageRequest pageRequest = PageRequest.of(0, resolvedLimit + 1);
            List<MensajeConversacionAgente> rawDesc = beforeId == null
                    ? mensajeRepository.findByConversacionIdOrderByIdDesc(conversationId, pageRequest)
                    : mensajeRepository.findByConversacionIdAndIdLessThanOrderByIdDesc(
                            conversationId, beforeId, pageRequest);

            boolean hasMore = rawDesc.size() > resolvedLimit;
            List<MensajeConversacionAgente> sliceDesc = hasMore
                    ? rawDesc.subList(0, resolvedLimit)
                    : rawDesc;

            List<MensajeConversacionAgente> chronological = new ArrayList<>(sliceDesc);
            Collections.reverse(chronological);

            Map<Long, SharedProposalResponse> proposalsByMessageId = cargarPropuestasPorMensaje(chronological);
            List<ConversationMessageResponse> responses = chronological.stream()
                    .map(msg -> toMessageResponse(msg, proposalsByMessageId.get(msg.getId())))
                    .toList();

            Long nextBeforeId = responses.isEmpty() ? null : responses.getFirst().id();
            return new ConversationMessagesPageResponse(conversationId, hasMore, nextBeforeId, responses);
        });
    }

    public SendMessageResultResponse enviarMensaje(
            Long projectId,
            Long conversationId,
            SendConversationMessageRequest request) {
        String contenido = normalizarContenidoMensaje(request == null ? null : request.contenido());
        String clientMessageId = normalizarTokenOpcional(
                request == null ? null : request.clientMessageId(), "clientMessageId");
        String clientId = normalizarTokenOpcional(request == null ? null : request.clientId(), "clientId");
        boolean invokeAgent = AgentMentionParser.shouldInvokeAgent(
                contenido, request != null && request.invokeAgent());
        String promptForAgent = invokeAgent ? AgentMentionParser.extractPromptForAgent(contenido) : null;

        HumanTurnPersistenceResult humanTurn;
        try {
            humanTurn = persistirTurnoHumano(
                    projectId, conversationId, contenido, clientMessageId, clientId, invokeAgent);
        } catch (DataIntegrityViolationException duplicateRace) {
            if (clientMessageId != null) {
                return recuperarResultadoExistentePorIdempotencia(projectId, conversationId, clientMessageId);
            }
            throw duplicateRace;
        }

        if (!humanTurn.needsAgentExecution()) {
            return humanTurn.immediateResult();
        }

        return ejecutarParticipacionAgente(
                projectId,
                conversationId,
                humanTurn.userEntity(),
                humanTurn.userResponse(),
                humanTurn.requester(),
                promptForAgent,
                request,
                clientId);
    }

    public SharedProposalResponse aceptarPropuesta(
            Long projectId,
            Long conversationId,
            Long proposalId,
            ProposalDecisionRequest request) {
        String clientId = normalizarTokenOpcional(request == null ? null : request.clientId(), "clientId");
        if (diagramaRepository == null) {
            throw new ConversationConflictException(
                    "DIAGRAM_REPOSITORY_UNAVAILABLE",
                    "El repositorio de diagramas no está disponible.");
        }

        AcceptanceOutcome outcome;
        try {
            outcome = transactionTemplate.execute(status -> ejecutarAceptacionTransaccional(
                    projectId, conversationId, proposalId, clientId));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException concurrentRace) {
            throw new ConversationConflictException(
                    "PROPOSAL_CONCURRENT_CONFLICT",
                    "La propuesta o el diagrama fue modificado simultáneamente por otro colaborador.");
        }

        if (outcome.staleConflict() != null) {
            throw outcome.staleConflict();
        }
        if (outcome.validationFailure() != null) {
            throw outcome.validationFailure();
        }
        return outcome.acceptedProposal();
    }

    public SharedProposalResponse rechazarPropuesta(
            Long projectId,
            Long conversationId,
            Long proposalId,
            ProposalDecisionRequest request) {
        String clientId = normalizarTokenOpcional(request == null ? null : request.clientId(), "clientId");
        String comentario = normalizarComentarioOpcional(request == null ? null : request.comentario());
        try {
            return transactionTemplate.execute(status -> {
                obtenerProyectoParaEdicion(projectId);
                Usuario actual = usuarioActualService.obtenerUsuarioActual();
                obtenerConversacionDelProyecto(projectId, conversationId);
                PropuestaUmlAgente propuesta = obtenerPropuestaDeConversacion(
                        projectId, conversationId, proposalId);

                if (propuesta.getEstado() != AgentProposalState.PENDING) {
                    throw new ConversationConflictException(
                            "PROPOSAL_NOT_PENDING",
                            "La propuesta ya fue procesada (" + propuesta.getEstado() + ").");
                }

                propuesta.setEstado(AgentProposalState.REJECTED);
                propuesta.setDecididoPor(actual);
                propuesta.setDecididoPorNombre(actual.getNombre());
                propuesta.setFechaDecision(LocalDateTime.now());
                propuesta.setResultadoAplicacion(
                        comentario != null ? comentario : ("Propuesta rechazada por " + actual.getNombre()));

                PropuestaUmlAgente guardada = propuestaRepository.saveAndFlush(propuesta);
                SharedProposalResponse response = toProposalResponse(guardada);
                eventPublisher.scheduleProposalUpdated(
                        response, guardada.getVersionLock(), actual, clientId);
                return response;
            });
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException concurrentRace) {
            throw new ConversationConflictException(
                    "PROPOSAL_CONCURRENT_CONFLICT",
                    "Otro colaborador ya tomó una decisión sobre esta propuesta.");
        }
    }

    private AcceptanceOutcome ejecutarAceptacionTransaccional(
            Long projectId,
            Long conversationId,
            Long proposalId,
            String clientId) {
        Proyecto proyecto = obtenerProyectoParaEdicion(projectId);
        Usuario actual = usuarioActualService.obtenerUsuarioActual();
        obtenerConversacionDelProyecto(projectId, conversationId);
        PropuestaUmlAgente propuesta = obtenerPropuestaDeConversacion(
                projectId, conversationId, proposalId);

        if (propuesta.getEstado() != AgentProposalState.PENDING) {
            throw new ConversationConflictException(
                    "PROPOSAL_NOT_PENDING",
                    "La propuesta ya fue procesada (" + propuesta.getEstado() + ").");
        }

        Diagrama diagrama = diagramaRepository.findByProyectoId(projectId).orElse(null);
        Long currentRevision = diagrama != null ? diagrama.getRevision() : null;
        if (diagrama != null && currentRevision != null && diagrama.getContenido() != null && revisionHistory != null) {
            revisionHistory.record(projectId, currentRevision, diagrama.getContenido());
        }

        if (!Objects.equals(currentRevision, propuesta.getBaseRevision())) {
            String staleMessage = "El diagrama cambió desde la revisión base de esta propuesta. Solicita una propuesta actualizada.";
            propuesta.setEstado(AgentProposalState.STALE);
            propuesta.setDecididoPor(actual);
            propuesta.setDecididoPorNombre(actual.getNombre());
            propuesta.setFechaDecision(LocalDateTime.now());
            propuesta.setResultadoAplicacion(staleMessage);
            PropuestaUmlAgente savedStale = propuestaRepository.saveAndFlush(propuesta);
            SharedProposalResponse staleResponse = toProposalResponse(savedStale);
            eventPublisher.scheduleProposalUpdated(
                    staleResponse, savedStale.getVersionLock(), actual, clientId);
            return AcceptanceOutcome.stale(
                    new ConversationConflictException("PROPOSAL_STALE", staleMessage));
        }

        List<DiagramOperation> operations = extractOperations(propuesta.getOperacionesJson());
        Map<String, Object> baseDocument = (diagrama != null && diagrama.getContenido() != null)
                ? diagrama.getContenido()
                : ServerDiagramOperationApplier.emptyDocument();

        ServerDiagramOperationApplier.ApplierResult applied;
        try {
            // Also protect PENDING proposals persisted before the semantic guard existed.
            // The immutable human origin, not the agent summary or accepting user, authorizes the plan.
            var origin = propuesta.getMensaje() == null ? null : propuesta.getMensaje().getMensajeOrigen();
            if (origin == null) {
                throw new DiagramValidationException("La propuesta no tiene una solicitud de origen verificable; solicita una nueva.");
            }
            try {
                SemanticOperationValidator.validate(origin.getContenido(),
                        DiagramContextMapper.fromDocument(baseDocument), operations);
            } catch (AiServiceException unsafe) {
                throw new DiagramValidationException(unsafe.getMessage());
            }
            applied = ServerDiagramOperationApplier.apply(baseDocument, operations);
        } catch (DiagramValidationException invalid) {
            propuesta.setEstado(AgentProposalState.INVALID);
            propuesta.setDecididoPor(actual);
            propuesta.setDecididoPorNombre(actual.getNombre());
            propuesta.setFechaDecision(LocalDateTime.now());
            propuesta.setResultadoAplicacion(invalid.getMessage());
            PropuestaUmlAgente savedInvalid = propuestaRepository.saveAndFlush(propuesta);
            SharedProposalResponse invalidResponse = toProposalResponse(savedInvalid);
            eventPublisher.scheduleProposalUpdated(
                    invalidResponse, savedInvalid.getVersionLock(), actual, clientId);
            return AcceptanceOutcome.invalid(invalid);
        }

        if (elementLockService != null && !applied.lockProtectedElementIds().isEmpty()) {
            elementLockService.verifyCanModify(
                    projectId, applied.lockProtectedElementIds(), actual.getId(), clientId);
        }

        if (diagrama == null) {
            diagrama = new Diagrama();
            diagrama.setProyecto(proyecto);
        }
        diagrama.setVersion(applied.version());
        diagrama.setContenido(applied.document());
        Diagrama savedDiagram = diagramaRepository.saveAndFlush(diagrama);

        LocalDateTime ahora = LocalDateTime.now();
        propuesta.setEstado(AgentProposalState.ACCEPTED);
        propuesta.setAplicadaRevision(savedDiagram.getRevision());
        propuesta.setElementosAfectadosJson(ProposalJsonCodec.encodeAffectedIds(applied.affectedElementIds()));
        propuesta.setDecididoPor(actual);
        propuesta.setDecididoPorNombre(actual.getNombre());
        propuesta.setFechaDecision(ahora);
        propuesta.setResultadoAplicacion(
                "Propuesta aplicada en la revisión " + savedDiagram.getRevision());

        PropuestaUmlAgente savedPropuesta = propuestaRepository.saveAndFlush(propuesta);

        if (revisionHistory != null) {
            revisionHistory.record(projectId, savedDiagram.getRevision(), savedDiagram.getContenido());
        }
        if (diagramCommitPublisher != null) {
            diagramCommitPublisher.schedule(toDiagramaResponse(savedDiagram), clientId);
        }

        SharedProposalResponse acceptedResponse = toProposalResponse(savedPropuesta);
        eventPublisher.scheduleProposalUpdated(
                acceptedResponse, savedPropuesta.getVersionLock(), actual, clientId);
        return AcceptanceOutcome.accepted(acceptedResponse);
    }

    private HumanTurnPersistenceResult persistirTurnoHumano(
            Long projectId,
            Long conversationId,
            String contenido,
            String clientMessageId,
            String clientId,
            boolean invokeAgent) {
        return transactionTemplate.execute(status -> {
            Proyecto proyecto = obtenerProyectoParaEdicion(projectId);
            Usuario actual = usuarioActualService.obtenerUsuarioActual();
            ConversacionAgente conversacion = obtenerConversacionDelProyecto(projectId, conversationId);

            if (clientMessageId != null) {
                Optional<MensajeConversacionAgente> existenteOpt =
                        mensajeRepository.findByConversacionIdAndClientMessageId(conversationId, clientMessageId);
                if (existenteOpt.isPresent()) {
                    MensajeConversacionAgente existente = existenteOpt.get();
                    Optional<MensajeConversacionAgente> respuestaPrevia = mensajeRepository
                            .findFirstByConversacionIdAndMensajeOrigenIdAndAutorTipoOrderByIdDesc(
                                    conversationId, existente.getId(), MessageAuthorType.AGENT);
                    boolean tieneRespuestaExitosa = respuestaPrevia.isPresent()
                            && respuestaPrevia.get().getEstado() == ConversationMessageStatus.SENT;
                    if (!invokeAgent || tieneRespuestaExitosa) {
                        return new HumanTurnPersistenceResult(
                                existente,
                                toMessageResponse(existente, null),
                                actual,
                                false,
                                construirResultadoDesdeExistente(conversationId, existente));
                    }
                    if (conversacion.getEstado() == ConversationState.ARCHIVED) {
                        throw new ConversationConflictException(
                                "CONVERSATION_ARCHIVED",
                                "La conversación está archivada. Reactívala para consultar al agente.");
                    }
                    eventPublisher.scheduleAiStatus(
                            projectId, conversationId, true, actual, null, clientId);
                    return new HumanTurnPersistenceResult(
                            existente,
                            toMessageResponse(existente, null),
                            actual,
                            true,
                            null);
                }
            }

            if (conversacion.getEstado() == ConversationState.ARCHIVED) {
                throw new ConversationConflictException(
                        "CONVERSATION_ARCHIVED",
                        "La conversación está archivada. Reactívala para enviar mensajes.");
            }

            LocalDateTime ahora = LocalDateTime.now();
            MensajeConversacionAgente mensaje = new MensajeConversacionAgente();
            mensaje.setConversacion(conversacion);
            mensaje.setProyecto(proyecto);
            mensaje.setAutorTipo(MessageAuthorType.USER);
            mensaje.setAutorUsuario(actual);
            mensaje.setAutorNombre(actual.getNombre());
            mensaje.setContenido(contenido);
            mensaje.setEstado(ConversationMessageStatus.SENT);
            mensaje.setClientMessageId(clientMessageId);
            mensaje.setFechaCreacion(ahora);

            MensajeConversacionAgente guardado = mensajeRepository.saveAndFlush(mensaje);
            conversacion.setFechaActualizacion(ahora);
            conversacionRepository.saveAndFlush(conversacion);

            ConversationMessageResponse userResponse = toMessageResponse(guardado, null);
            eventPublisher.scheduleMessageCreated(
                    userResponse, actual.getId(), actual.getNombre(), clientId);
            if (invokeAgent) {
                eventPublisher.scheduleAiStatus(
                        projectId, conversationId, true, actual, null, clientId);
            }
            return new HumanTurnPersistenceResult(
                    guardado,
                    userResponse,
                    actual,
                    invokeAgent,
                    invokeAgent ? null : new SendMessageResultResponse(userResponse, null, null));
        });
    }

    private SendMessageResultResponse ejecutarParticipacionAgente(
            Long projectId,
            Long conversationId,
            MensajeConversacionAgente userMessage,
            ConversationMessageResponse userResponse,
            Usuario requester,
            String promptForAgent,
            SendConversationMessageRequest request,
            String clientId) {
        if (contextualAgentService == null) {
            return persistirFalloAgente(
                    projectId,
                    conversationId,
                    userMessage,
                    userResponse,
                    requester,
                    "El servicio del agente IA no está configurado en este entorno.",
                    clientId);
        }

        AgentInvocationSnapshot snapshot = cargarSnapshotParaAgente(
                projectId, conversationId, userMessage.getId());
        AgentAskRequest askRequest = new AgentAskRequest(
                promptForAgent,
                request == null ? null : request.selectedNodeId(),
                request == null ? null : request.selectedEdgeId(),
                request == null ? List.of() : request.recentEvents(),
                snapshot.boundedConversation());

        AgentAskResponse aiResponse;
        try {
            aiResponse = contextualAgentService.ask(projectId, askRequest);
        } catch (AiServiceException failure) {
            log.warn("Shared agent invocation failed projectId={} conversationId={} userMessageId={} category={} status={} detail={}",
                    projectId,
                    conversationId,
                    userMessage.getId(),
                    failure.getCategory(),
                    failure.getStatus() != null ? failure.getStatus().value() : null,
                    failure.getTechnicalDetail());
            String friendly = traducirErrorProveedorIa(failure);
            return persistirFalloAgente(
                    projectId, conversationId, userMessage, userResponse, requester, friendly, clientId);
        } catch (AccesoDenegadoException | RecursoNoEncontradoException failure) {
            log.warn("Shared agent access/resource failure projectId={} conversationId={} userMessageId={} type={}",
                    projectId, conversationId, userMessage.getId(), failure.getClass().getSimpleName());
            String friendly = traducirErrorProveedorIa(failure);
            return persistirFalloAgente(
                    projectId, conversationId, userMessage, userResponse, requester, friendly, clientId);
        } catch (RuntimeException unexpected) {
            log.warn("Unexpected failure invoking shared agent for project {} conversation {}",
                    projectId, conversationId, unexpected);
            return persistirFalloAgente(
                    projectId,
                    conversationId,
                    userMessage,
                    userResponse,
                    requester,
                    "El agente no pudo responder en este momento. Tu mensaje quedó guardado; vuelve a intentarlo.",
                    clientId);
        }

        return persistirRespuestaExitosaAgente(
                projectId,
                conversationId,
                userMessage,
                userResponse,
                requester,
                aiResponse,
                snapshot.baseRevision(),
                clientId);
    }

    private AgentInvocationSnapshot cargarSnapshotParaAgente(
            Long projectId,
            Long conversationId,
            Long beforeMessageId) {
        return transactionTemplate.execute(status -> {
            Long currentRevision = null;
            if (diagramaRepository != null) {
                currentRevision = diagramaRepository.findByProyectoId(projectId)
                        .map(Diagrama::getRevision)
                        .orElse(null);
            }
            List<MensajeConversacionAgente> recentDesc =
                    mensajeRepository.findByConversacionIdAndIdLessThanOrderByIdDesc(
                            conversationId,
                            beforeMessageId,
                            PageRequest.of(0, RECENT_CONTEXT_SCAN_LIMIT));
            List<AgentConversationMessage> bounded =
                    ConversationContextSelector.selectBoundedContext(recentDesc);
            return new AgentInvocationSnapshot(currentRevision, bounded);
        });
    }

    private SendMessageResultResponse persistirRespuestaExitosaAgente(
            Long projectId,
            Long conversationId,
            MensajeConversacionAgente userMessage,
            ConversationMessageResponse userResponse,
            Usuario requester,
            AgentAskResponse aiResponse,
            Long baseRevision,
            String clientId) {
        return transactionTemplate.execute(status -> {
            Proyecto proyecto = obtenerProyecto(projectId);
            ConversacionAgente conversacion = obtenerConversacionDelProyecto(projectId, conversationId);

            Optional<MensajeConversacionAgente> yaRespondido = mensajeRepository
                    .findFirstByConversacionIdAndMensajeOrigenIdAndAutorTipoOrderByIdDesc(
                            conversationId, userMessage.getId(), MessageAuthorType.AGENT);
            if (yaRespondido.isPresent()
                    && yaRespondido.get().getEstado() == ConversationMessageStatus.SENT) {
                MensajeConversacionAgente prev = yaRespondido.get();
                SharedProposalResponse prevProposal = propuestaRepository.findByMensajeId(prev.getId())
                        .map(this::toProposalResponse)
                        .orElse(null);
                eventPublisher.scheduleAiStatus(projectId, conversationId, false, requester, null, clientId);
                return new SendMessageResultResponse(userResponse, toMessageResponse(prev, prevProposal), null);
            }

            LocalDateTime ahora = LocalDateTime.now();
            String answerText = (aiResponse.answer() == null || aiResponse.answer().isBlank())
                    ? "He analizado tu solicitud."
                    : aiResponse.answer().strip();

            MensajeConversacionAgente agentMsg = new MensajeConversacionAgente();
            agentMsg.setConversacion(conversacion);
            agentMsg.setProyecto(proyecto);
            agentMsg.setAutorTipo(MessageAuthorType.AGENT);
            agentMsg.setAutorUsuario(null);
            agentMsg.setAutorNombre(AGENT_DISPLAY_NAME);
            agentMsg.setSolicitadoPor(requester);
            agentMsg.setSolicitadoPorNombre(requester.getNombre());
            agentMsg.setContenido(answerText);
            agentMsg.setEstado(ConversationMessageStatus.SENT);
            agentMsg.setMensajeOrigen(userMessage);
            agentMsg.setFechaCreacion(ahora);

            MensajeConversacionAgente savedAgentMsg = mensajeRepository.saveAndFlush(agentMsg);
            conversacion.setFechaActualizacion(ahora);
            conversacionRepository.saveAndFlush(conversacion);

            SharedProposalResponse proposalResponse = crearPropuestaSiAplica(
                    proyecto, conversacion, savedAgentMsg, requester, aiResponse, baseRevision, ahora);

            ConversationMessageResponse agentResponse = toMessageResponse(savedAgentMsg, proposalResponse);
            eventPublisher.scheduleAiStatus(projectId, conversationId, false, requester, null, clientId);
            eventPublisher.scheduleMessageCreated(
                    agentResponse, requester.getId(), AGENT_DISPLAY_NAME, clientId);
            return new SendMessageResultResponse(userResponse, agentResponse, null);
        });
    }

    SharedProposalResponse crearPropuestaSiAplica(
            Proyecto proyecto,
            ConversacionAgente conversacion,
            MensajeConversacionAgente savedAgentMsg,
            Usuario requester,
            AgentAskResponse aiResponse,
            Long snapshotRevision,
            LocalDateTime ahora) {
        if (aiResponse.operations() == null || aiResponse.operations().isEmpty()) {
            return null;
        }
        Diagrama currentDiagram = diagramaRepository != null
                ? diagramaRepository.findByProyectoId(proyecto.getId()).orElse(null)
                : null;
        Long currentRevision = currentDiagram != null ? currentDiagram.getRevision() : null;
        Map<String, Object> baseDoc = (currentDiagram != null && currentDiagram.getContenido() != null)
                ? currentDiagram.getContenido()
                : ServerDiagramOperationApplier.emptyDocument();

        PropuestaUmlAgente propuesta = new PropuestaUmlAgente();
        propuesta.setVersionLock(0L);
        propuesta.setConversacion(conversacion);
        propuesta.setMensaje(savedAgentMsg);
        propuesta.setProyecto(proyecto);
        propuesta.setSolicitadoPor(requester);
        propuesta.setSolicitadoPorNombre(requester.getNombre());
        propuesta.setBaseRevision(snapshotRevision != null ? snapshotRevision : currentRevision);
        propuesta.setOperacionesJson(ProposalJsonCodec.encodeOperations(aiResponse.operations()));
        propuesta.setFechaCreacion(ahora);

        try {
            ServerDiagramOperationApplier.ApplierResult preview =
                    ServerDiagramOperationApplier.apply(baseDoc, aiResponse.operations());
            if (!preview.changed()) {
                return null;
            }
            propuesta.setElementosAfectadosJson(
                    ProposalJsonCodec.encodeAffectedIds(preview.affectedElementIds()));
            propuesta.setResumen(preview.summary());
            if (!Objects.equals(currentRevision, snapshotRevision)) {
                propuesta.setEstado(AgentProposalState.STALE);
                propuesta.setResultadoAplicacion(
                        "El diagrama cambió mientras el agente generaba la propuesta.");
            } else {
                propuesta.setEstado(AgentProposalState.PENDING);
            }
        } catch (DiagramValidationException invalid) {
            propuesta.setEstado(AgentProposalState.INVALID);
            propuesta.setElementosAfectadosJson(ProposalJsonCodec.encodeAffectedIds(List.of()));
            propuesta.setResumen(ServerDiagramOperationApplier.summarizeOperations(aiResponse.operations()));
            propuesta.setResultadoAplicacion(invalid.getMessage());
            propuesta.setFechaDecision(ahora);
        }

        PropuestaUmlAgente savedProposal = propuestaRepository.saveAndFlush(propuesta);
        return toProposalResponse(savedProposal);
    }

    private SendMessageResultResponse persistirFalloAgente(
            Long projectId,
            Long conversationId,
            MensajeConversacionAgente userMessage,
            ConversationMessageResponse userResponse,
            Usuario requester,
            String friendlyError,
            String clientId) {
        return transactionTemplate.execute(status -> {
            Proyecto proyecto = obtenerProyecto(projectId);
            ConversacionAgente conversacion = obtenerConversacionDelProyecto(projectId, conversationId);

            LocalDateTime ahora = LocalDateTime.now();
            MensajeConversacionAgente failedMsg = new MensajeConversacionAgente();
            failedMsg.setConversacion(conversacion);
            failedMsg.setProyecto(proyecto);
            failedMsg.setAutorTipo(MessageAuthorType.AGENT);
            failedMsg.setAutorUsuario(null);
            failedMsg.setAutorNombre(AGENT_DISPLAY_NAME);
            failedMsg.setSolicitadoPor(requester);
            failedMsg.setSolicitadoPorNombre(requester.getNombre());
            failedMsg.setContenido(friendlyError);
            failedMsg.setEstado(ConversationMessageStatus.FAILED);
            failedMsg.setMensajeOrigen(userMessage);
            failedMsg.setFechaCreacion(ahora);

            MensajeConversacionAgente savedFailed = mensajeRepository.saveAndFlush(failedMsg);
            conversacion.setFechaActualizacion(ahora);
            conversacionRepository.saveAndFlush(conversacion);

            ConversationMessageResponse failedResponse = toMessageResponse(savedFailed, null);
            eventPublisher.scheduleAiStatus(
                    projectId, conversationId, false, requester, friendlyError, clientId);
            eventPublisher.scheduleMessageCreated(
                    failedResponse, requester.getId(), AGENT_DISPLAY_NAME, clientId);
            return new SendMessageResultResponse(userResponse, failedResponse, friendlyError);
        });
    }

    static String traducirErrorProveedorIa(RuntimeException exception) {
        if (exception instanceof AccesoDenegadoException denied) {
            return denied.getMessage();
        }
        if (exception instanceof AiServiceException aiError) {
            HttpStatus status = aiError.getStatus();
            AiServiceException.ErrorCategory category = aiError.getCategory();
            if (category == AiServiceException.ErrorCategory.INVALID_UML_DOCUMENT) {
                return "El documento UML guardado presenta inconsistencias estructurales y no pudo interpretarse. "
                        + "Tu mensaje se conservó; revisa el diagrama e inténtalo de nuevo.";
            }
            if (category == AiServiceException.ErrorCategory.SEMANTIC_CONVERTER_ERROR) {
                return "Ocurrió un error interno al preparar el contexto UML del diagrama. "
                        + "Tu mensaje se conservó; vuelve a intentarlo.";
            }
            if (category == AiServiceException.ErrorCategory.FASTAPI_COMMUNICATION_ERROR) {
                if (status == HttpStatus.GATEWAY_TIMEOUT) {
                    return "La comunicación con el servicio de IA (FastAPI) agotó el tiempo de espera. "
                            + "Tu mensaje se conservó; vuelve a intentarlo.";
                }
                return "No fue posible comunicarse con el servicio de IA (FastAPI). "
                        + "Tu mensaje se conservó; verifica que ai-service esté activo e inténtalo de nuevo.";
            }
            if (category == AiServiceException.ErrorCategory.AI_PROVIDER_ERROR) {
                if (status == HttpStatus.GATEWAY_TIMEOUT) {
                    return "El proveedor de IA tardó demasiado en responder. Tu mensaje se conservó; vuelve a intentarlo.";
                }
                return "El proveedor de IA no está disponible en este momento. Tu mensaje se conservó; vuelve a intentarlo.";
            }
            if (category == AiServiceException.ErrorCategory.INVALID_MODEL_RESPONSE) {
                if (status == HttpStatus.UNPROCESSABLE_ENTITY
                        && aiError.getMessage() != null && !aiError.getMessage().isBlank()) {
                    return aiError.getMessage();
                }
                return "El proveedor de IA devolvió una respuesta no válida. Tu mensaje se conservó; vuelve a intentarlo.";
            }
            if (aiError.getMessage() != null && !aiError.getMessage().isBlank()) {
                return aiError.getMessage();
            }
        }
        return "El agente no pudo responder en este momento. Tu mensaje quedó guardado; vuelve a intentarlo.";
    }

    private SendMessageResultResponse recuperarResultadoExistentePorIdempotencia(
            Long projectId,
            Long conversationId,
            String clientMessageId) {
        return transactionTemplate.execute(status -> {
            obtenerProyectoParaEdicion(projectId);
            obtenerConversacionDelProyecto(projectId, conversationId);
            MensajeConversacionAgente existente = mensajeRepository
                    .findByConversacionIdAndClientMessageId(conversationId, clientMessageId)
                    .orElseThrow(() -> new ConversationConflictException(
                            "MESSAGE_CONFLICT", "No se pudo confirmar el mensaje duplicado"));
            return construirResultadoDesdeExistente(conversationId, existente);
        });
    }

    private SendMessageResultResponse construirResultadoDesdeExistente(
            Long conversationId,
            MensajeConversacionAgente userMsg) {
        ConversationMessageResponse userResponse = toMessageResponse(userMsg, null);
        Optional<MensajeConversacionAgente> agentReply = mensajeRepository
                .findFirstByConversacionIdAndMensajeOrigenIdAndAutorTipoOrderByIdDesc(
                        conversationId, userMsg.getId(), MessageAuthorType.AGENT);
        if (agentReply.isEmpty()) {
            return new SendMessageResultResponse(userResponse, null, null);
        }
        MensajeConversacionAgente reply = agentReply.get();
        SharedProposalResponse proposal = propuestaRepository.findByMensajeId(reply.getId())
                .map(this::toProposalResponse)
                .orElse(null);
        ConversationMessageResponse agentResponse = toMessageResponse(reply, proposal);
        String error = reply.getEstado() == ConversationMessageStatus.FAILED ? reply.getContenido() : null;
        return new SendMessageResultResponse(userResponse, agentResponse, error);
    }

    private Map<Long, SharedProposalResponse> cargarPropuestasPorMensaje(List<MensajeConversacionAgente> mensajes) {
        if (mensajes.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = mensajes.stream().map(MensajeConversacionAgente::getId).toList();
        List<PropuestaUmlAgente> propuestas = propuestaRepository.findByMensajeIdIn(ids);
        Map<Long, SharedProposalResponse> map = new HashMap<>();
        for (PropuestaUmlAgente propuesta : propuestas) {
            map.put(propuesta.getMensaje().getId(), toProposalResponse(propuesta));
        }
        return map;
    }

    ConversationSummaryResponse toSummaryResponse(ConversacionAgente conversacion) {
        return new ConversationSummaryResponse(
                conversacion.getId(),
                conversacion.getProyecto().getId(),
                conversacion.getTitulo(),
                conversacion.getEstado(),
                conversacion.getCreadoPor().getId(),
                conversacion.getCreadoPorNombre(),
                conversacion.getFechaCreacion(),
                conversacion.getFechaActualizacion());
    }

    ConversationMessageResponse toMessageResponse(
            MensajeConversacionAgente mensaje,
            SharedProposalResponse proposal) {
        return new ConversationMessageResponse(
                mensaje.getId(),
                mensaje.getConversacion().getId(),
                mensaje.getProyecto().getId(),
                mensaje.getAutorTipo(),
                mensaje.getAutorUsuario() != null ? mensaje.getAutorUsuario().getId() : null,
                mensaje.getAutorNombre(),
                mensaje.getSolicitadoPor() != null ? mensaje.getSolicitadoPor().getId() : null,
                mensaje.getSolicitadoPorNombre(),
                mensaje.getContenido(),
                mensaje.getEstado(),
                mensaje.getClientMessageId(),
                mensaje.getMensajeOrigen() != null ? mensaje.getMensajeOrigen().getId() : null,
                mensaje.getFechaCreacion(),
                proposal);
    }

    SharedProposalResponse toProposalResponse(PropuestaUmlAgente propuesta) {
        List<DiagramOperation> operations = extractOperations(propuesta.getOperacionesJson());
        List<String> affectedIds = extractAffectedIds(propuesta.getElementosAfectadosJson());
        return new SharedProposalResponse(
                propuesta.getId(),
                propuesta.getConversacion().getId(),
                propuesta.getMensaje().getId(),
                propuesta.getProyecto().getId(),
                propuesta.getSolicitadoPor().getId(),
                propuesta.getSolicitadoPorNombre(),
                propuesta.getEstado(),
                propuesta.getBaseRevision(),
                propuesta.getAplicadaRevision(),
                operations,
                affectedIds,
                propuesta.getResumen(),
                propuesta.getDecididoPor() != null ? propuesta.getDecididoPor().getId() : null,
                propuesta.getDecididoPorNombre(),
                propuesta.getFechaDecision(),
                propuesta.getResultadoAplicacion(),
                propuesta.getFechaCreacion());
    }

    private static DiagramaResponse toDiagramaResponse(Diagrama diagrama) {
        return new DiagramaResponse(
                diagrama.getId(),
                diagrama.getProyecto().getId(),
                diagrama.getVersion(),
                diagrama.getRevision(),
                diagrama.getContenido(),
                diagrama.getFechaActualizacion());
    }

    List<DiagramOperation> extractOperations(Map<String, Object> raw) {
        return ProposalJsonCodec.decodeOperations(raw);
    }

    List<String> extractAffectedIds(Map<String, Object> raw) {
        return ProposalJsonCodec.decodeAffectedIds(raw);
    }

    Proyecto obtenerProyectoParaEdicion(Long projectId) {
        Proyecto proyecto = obtenerProyecto(projectId);
        workspaceAccessService.verificarRol(
                proyecto.getWorkspace().getId(), RolWorkspace.OWNER, RolWorkspace.EDITOR);
        return proyecto;
    }

    Proyecto obtenerProyecto(Long projectId) {
        return proyectoRepository.findById(projectId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No existe un proyecto con el id " + projectId));
    }

    ConversacionAgente obtenerConversacionDelProyecto(Long projectId, Long conversationId) {
        return conversacionRepository.findByIdAndProyectoId(conversationId, projectId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No existe la conversación solicitada dentro del proyecto"));
    }

    PropuestaUmlAgente obtenerPropuestaDeConversacion(
            Long projectId,
            Long conversationId,
            Long proposalId) {
        return propuestaRepository.findByIdAndConversacionIdAndProyectoId(
                        proposalId, conversationId, projectId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No existe la propuesta solicitada dentro de la conversación"));
    }

    private static String normalizarTitulo(String raw) {
        if (raw == null) {
            throw new ConversationValidationException("El título de la conversación es obligatorio");
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TITLE_LENGTH) {
            throw new ConversationValidationException(
                    "El título debe tener entre 1 y " + MAX_TITLE_LENGTH + " caracteres");
        }
        return trimmed;
    }

    private static String normalizarContenidoMensaje(String raw) {
        if (raw == null) {
            throw new ConversationValidationException("El mensaje no puede estar vacío");
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_MESSAGE_LENGTH) {
            throw new ConversationValidationException(
                    "El mensaje debe tener entre 1 y " + MAX_MESSAGE_LENGTH + " caracteres");
        }
        return trimmed;
    }

    private static String normalizarComentarioOpcional(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.length() > 300) {
            throw new ConversationValidationException("El comentario no puede superar 300 caracteres");
        }
        return trimmed;
    }

    private static String normalizarTokenOpcional(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.strip();
        if (!SAFE_TOKEN_PATTERN.matcher(trimmed).matches()) {
            throw new ConversationValidationException("Identificador inválido en " + field);
        }
        return trimmed;
    }

    private static int validarLimitePagina(Integer limit) {
        if (limit == null) {
            return DEFAULT_PAGE_LIMIT;
        }
        if (limit < 1 || limit > MAX_PAGE_LIMIT) {
            throw new ConversationValidationException(
                    "El límite de paginación debe estar entre 1 y " + MAX_PAGE_LIMIT);
        }
        return limit;
    }

    private record HumanTurnPersistenceResult(
            MensajeConversacionAgente userEntity,
            ConversationMessageResponse userResponse,
            Usuario requester,
            boolean needsAgentExecution,
            SendMessageResultResponse immediateResult) {
    }

    private record AgentInvocationSnapshot(
            Long baseRevision,
            List<AgentConversationMessage> boundedConversation) {
    }

    private record AcceptanceOutcome(
            SharedProposalResponse acceptedProposal,
            ConversationConflictException staleConflict,
            DiagramValidationException validationFailure) {
        static AcceptanceOutcome accepted(SharedProposalResponse proposal) {
            return new AcceptanceOutcome(proposal, null, null);
        }

        static AcceptanceOutcome stale(ConversationConflictException conflict) {
            return new AcceptanceOutcome(null, conflict, null);
        }

        static AcceptanceOutcome invalid(DiagramValidationException failure) {
            return new AcceptanceOutcome(null, null, failure);
        }
    }
}
