package com.sw1.backend.ai.conversation;

import com.sw1.backend.ai.agent.dto.AgentAskRequest;
import com.sw1.backend.ai.agent.dto.AgentAskResponse;
import com.sw1.backend.ai.agent.service.AgentIntentClassifier;
import com.sw1.backend.ai.agent.service.ContextualAgentService;
import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.conversation.dto.ConversationMessagesPageResponse;
import com.sw1.backend.ai.conversation.dto.ConversationSummaryResponse;
import com.sw1.backend.ai.conversation.dto.CreateConversationRequest;
import com.sw1.backend.ai.conversation.dto.SendConversationMessageRequest;
import com.sw1.backend.ai.conversation.dto.SendMessageResultResponse;
import com.sw1.backend.ai.conversation.exception.ConversationValidationException;
import com.sw1.backend.ai.conversation.model.ConversacionAgente;
import com.sw1.backend.ai.conversation.model.ConversationMessageStatus;
import com.sw1.backend.ai.conversation.model.MensajeConversacionAgente;
import com.sw1.backend.ai.conversation.model.MessageAuthorType;
import com.sw1.backend.ai.conversation.repository.ConversacionAgenteRepository;
import com.sw1.backend.ai.conversation.repository.MensajeConversacionAgenteRepository;
import com.sw1.backend.ai.conversation.repository.PropuestaUmlAgenteRepository;
import com.sw1.backend.ai.conversation.service.AgentConversationEventPublisher;
import com.sw1.backend.ai.conversation.service.AgentMentionParser;
import com.sw1.backend.ai.conversation.service.ConversationContextSelector;
import com.sw1.backend.ai.conversation.service.SharedAgentConversationService;
import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.collaboration.dto.CollaborationEvent;
import com.sw1.backend.collaboration.model.CollaborationEventType;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.tenant.model.MiembroTenant;
import com.sw1.backend.tenant.model.Tenant;
import com.sw1.backend.tenant.repository.MiembroTenantRepository;
import com.sw1.backend.tenant.service.TenantAccessService;
import com.sw1.backend.usuario.model.Usuario;
import com.sw1.backend.usuario.repository.UsuarioRepository;
import com.sw1.backend.workspace.model.MiembroWorkspace;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.model.Workspace;
import com.sw1.backend.workspace.repository.MiembroWorkspaceRepository;
import com.sw1.backend.workspace.repository.WorkspaceRepository;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SharedAgentConversationStageBTest {

    @Configuration
    @EnableTransactionManagement
    static class TxConfig {
    }

    private AnnotationConfigApplicationContext springContext;
    private SimpMessagingTemplate messaging;
    private ContextualAgentService contextualAgent;
    private SharedAgentConversationService service;

    private final AtomicLong convSeq = new AtomicLong(200);
    private final AtomicLong msgSeq = new AtomicLong(2000);
    private final Map<Long, ConversacionAgente> conversationsStore = new ConcurrentHashMap<>();
    private final Map<Long, MensajeConversacionAgente> messagesStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        conversationsStore.clear();
        messagesStore.clear();

        UsuarioRepository users = mock(UsuarioRepository.class);
        MiembroTenantRepository tenants = mock(MiembroTenantRepository.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        MiembroWorkspaceRepository members = mock(MiembroWorkspaceRepository.class);
        ProyectoRepository projects = mock(ProyectoRepository.class);
        DiagramaRepository diagrams = mock(DiagramaRepository.class);
        ConversacionAgenteRepository convRepo = mock(ConversacionAgenteRepository.class);
        MensajeConversacionAgenteRepository msgRepo = mock(MensajeConversacionAgenteRepository.class);
        PropuestaUmlAgenteRepository propRepo = mock(PropuestaUmlAgenteRepository.class);
        contextualAgent = mock(ContextualAgentService.class);
        messaging = mock(SimpMessagingTemplate.class);

        Tenant tenant = new Tenant();
        tenant.setId(1L);
        Workspace ws = new Workspace();
        ws.setId(10L);
        ws.setTenant(tenant);
        Proyecto project = new Proyecto();
        project.setId(101L);
        project.setNombre("Sistema Ventas");
        project.setWorkspace(ws);

        Diagrama diagram = new Diagrama();
        diagram.setId(50L);
        diagram.setProyecto(project);
        diagram.setRevision(4L);
        diagram.setVersion(1);
        diagram.setContenido(Map.of("version", 1, "nodes", List.of(), "edges", List.of()));

        when(workspaces.findById(10L)).thenReturn(Optional.of(ws));
        when(projects.findById(101L)).thenReturn(Optional.of(project));
        when(diagrams.findByProyectoId(101L)).thenReturn(Optional.of(diagram));

        registerUser(users, tenants, members, 1L, "gabriel@logicdraft.test", "Gabriel", 1L, 10L, RolWorkspace.OWNER);
        registerUser(users, tenants, members, 2L, "carlos@logicdraft.test", "Carlos", 1L, 10L, RolWorkspace.EDITOR);

        when(convRepo.countByProyectoId(any())).thenReturn(0L);
        when(convRepo.saveAndFlush(any())).thenAnswer(call -> {
            ConversacionAgente c = call.getArgument(0);
            if (c.getId() == null) {
                c.setId(convSeq.incrementAndGet());
            }
            conversationsStore.put(c.getId(), c);
            return c;
        });
        when(convRepo.findByIdAndProyectoId(any(), any())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            Long pid = call.getArgument(1);
            ConversacionAgente found = conversationsStore.get(id);
            return (found != null && Objects.equals(found.getProyecto().getId(), pid))
                    ? Optional.of(found)
                    : Optional.empty();
        });

        when(msgRepo.saveAndFlush(any())).thenAnswer(call -> {
            MensajeConversacionAgente m = call.getArgument(0);
            if (m.getId() == null) {
                m.setId(msgSeq.incrementAndGet());
            }
            messagesStore.put(m.getId(), m);
            return m;
        });
        when(msgRepo.findByConversacionIdAndClientMessageId(any(), any())).thenAnswer(call -> {
            Long cid = call.getArgument(0);
            String clientMsgId = call.getArgument(1);
            return messagesStore.values().stream()
                    .filter(m -> Objects.equals(m.getConversacion().getId(), cid)
                            && Objects.equals(m.getClientMessageId(), clientMsgId))
                    .findFirst();
        });
        when(msgRepo.findFirstByConversacionIdAndMensajeOrigenIdAndAutorTipoOrderByIdDesc(any(), any(), any()))
                .thenAnswer(call -> {
                    Long cid = call.getArgument(0);
                    Long origenId = call.getArgument(1);
                    MessageAuthorType type = call.getArgument(2);
                    return messagesStore.values().stream()
                            .filter(m -> Objects.equals(m.getConversacion().getId(), cid)
                                    && m.getMensajeOrigen() != null
                                    && Objects.equals(m.getMensajeOrigen().getId(), origenId)
                                    && m.getAutorTipo() == type)
                            .max(Comparator.comparing(MensajeConversacionAgente::getId));
                });
        when(msgRepo.findByConversacionIdOrderByIdDesc(any(), any())).thenAnswer(call -> {
            Long cid = call.getArgument(0);
            Pageable pageable = call.getArgument(1);
            return messagesStore.values().stream()
                    .filter(m -> Objects.equals(m.getConversacion().getId(), cid))
                    .sorted(Comparator.comparing(MensajeConversacionAgente::getId).reversed())
                    .limit(pageable.getPageSize())
                    .toList();
        });
        when(msgRepo.findByConversacionIdAndIdLessThanOrderByIdDesc(any(), any(), any())).thenAnswer(call -> {
            Long cid = call.getArgument(0);
            Long beforeId = call.getArgument(1);
            Pageable pageable = call.getArgument(2);
            return messagesStore.values().stream()
                    .filter(m -> Objects.equals(m.getConversacion().getId(), cid) && m.getId() < beforeId)
                    .sorted(Comparator.comparing(MensajeConversacionAgente::getId).reversed())
                    .limit(pageable.getPageSize())
                    .toList();
        });
        when(propRepo.findByMensajeIdIn(any())).thenReturn(List.of());
        when(propRepo.findByMensajeId(any())).thenReturn(Optional.empty());

        UsuarioActualService currentUser = new UsuarioActualService(users);
        WorkspaceAccessService accessService = new WorkspaceAccessService(
                currentUser, new TenantAccessService(currentUser, tenants), workspaces, members);

        springContext = new AnnotationConfigApplicationContext();
        springContext.register(TxConfig.class);
        springContext.registerBean(SimpMessagingTemplate.class, () -> messaging);
        springContext.registerBean(AgentConversationEventPublisher.class);
        springContext.refresh();
        AgentConversationEventPublisher publisher = springContext.getBean(AgentConversationEventPublisher.class);

        service = new SharedAgentConversationService(
                convRepo,
                msgRepo,
                propRepo,
                projects,
                diagrams,
                accessService,
                currentUser,
                contextualAgent,
                publisher,
                new MemoryTransactions());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (springContext != null) {
            springContext.close();
        }
    }

    @Test
    void normalMessagesNeverInvokeModelWhileExplicitMentionOrActionInvokesAndPersistsReply() {
        ConversationSummaryResponse convA = as("gabriel@logicdraft.test", () ->
                service.crearConversacion(101L, new CreateConversationRequest("Diseño Ventas", "tab-g")));
        ConversationSummaryResponse convB = as("carlos@logicdraft.test", () ->
                service.crearConversacion(101L, new CreateConversationRequest("Otra conversación", "tab-c")));

        // Normal collaborator messages do NOT invoke the AI model
        as("gabriel@logicdraft.test", () -> service.enviarMensaje(
                101L, convA.id(),
                new SendConversationMessageRequest("He creado la entidad Producto.", "m-1", false, null, null, List.of(), "tab-g")));
        as("carlos@logicdraft.test", () -> service.enviarMensaje(
                101L, convA.id(),
                new SendConversationMessageRequest("Voy a revisar sus atributos.", "m-2", false, null, null, List.of(), "tab-c")));
        verifyNoInteractions(contextualAgent);

        // Verify @LogicDraft mention stripping preserves MODIFICATION intent classification!
        String strippedImperative = AgentMentionParser.extractPromptForAgent(
                "@LogicDraft, agrega una entidad Factura");
        assertEquals("agrega una entidad Factura", strippedImperative);
        assertEquals(AgentIntentClassifier.Intent.MODIFICATION,
                AgentIntentClassifier.classify(strippedImperative));
        assertThrows(ConversationValidationException.class,
                () -> AgentMentionParser.extractPromptForAgent("   @LogicDraft,   "));

        // Explicit @LogicDraft mention invokes AI, passes bounded attributed context, and links reply to convA
        when(contextualAgent.ask(eq(101L), any())).thenReturn(
                new AgentAskResponse("He revisado el diagrama y recomiendo relacionar Venta con Producto.", List.of()));
        reset(messaging);

        SendMessageResultResponse result = as("gabriel@logicdraft.test", () -> service.enviarMensaje(
                101L, convA.id(),
                new SendConversationMessageRequest(
                        "@LogicDraft, ¿puedes analizar si falta alguna relación?",
                        "m-3",
                        false,
                        "node-prod",
                        null,
                        List.of(),
                        "tab-g")));

        // Original human message stored verbatim with real author
        assertEquals("@LogicDraft, ¿puedes analizar si falta alguna relación?", result.userMessage().contenido());
        assertEquals("Gabriel", result.userMessage().autorNombre());
        assertNull(result.agentError());
        assertNotNull(result.agentMessage());
        assertEquals(convA.id(), result.agentMessage().conversationId());
        assertEquals(MessageAuthorType.AGENT, result.agentMessage().autorTipo());
        assertEquals("LogicDraft IA", result.agentMessage().autorNombre());
        assertEquals("Gabriel", result.agentMessage().solicitadoPorNombre());
        assertEquals(result.userMessage().id(), result.agentMessage().mensajeOrigenId());

        // Upstream request received clean prompt + attributed prior turns
        ArgumentCaptor<AgentAskRequest> askCaptor = ArgumentCaptor.forClass(AgentAskRequest.class);
        verify(contextualAgent, times(1)).ask(eq(101L), askCaptor.capture());
        assertEquals("¿puedes analizar si falta alguna relación?", askCaptor.getValue().message());
        assertEquals(2, askCaptor.getValue().conversation().size());
        assertEquals("[Gabriel]: He creado la entidad Producto.", askCaptor.getValue().conversation().get(0).text());
        assertEquals("[Carlos]: Voy a revisar sus atributos.", askCaptor.getValue().conversation().get(1).text());

        // STOMP emitted: human message -> AI status active=true -> AI status active=false -> AI reply
        ArgumentCaptor<CollaborationEvent> wsCaptor = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(messaging, times(4)).convertAndSend(eq("/topic/proyectos/101"), wsCaptor.capture());
        List<CollaborationEvent> events = wsCaptor.getAllValues();
        assertEquals(CollaborationEventType.CONVERSATION_MESSAGE_CREATED, events.get(0).type());
        assertEquals(CollaborationEventType.CONVERSATION_AI_STATUS, events.get(1).type());
        assertEquals(Boolean.TRUE, events.get(1).payload().get("active"));
        assertEquals(CollaborationEventType.CONVERSATION_AI_STATUS, events.get(2).type());
        assertEquals(Boolean.FALSE, events.get(2).payload().get("active"));
        assertEquals(CollaborationEventType.CONVERSATION_MESSAGE_CREATED, events.get(3).type());

        // convB stayed completely untouched
        assertTrue(as("carlos@logicdraft.test", () ->
                service.listarMensajes(101L, convB.id(), null, 20)).messages().isEmpty());

        // Duplicate retry with same clientMessageId ("m-3") does NOT re-invoke the AI model
        SendMessageResultResponse duplicateRetry = as("gabriel@logicdraft.test", () -> service.enviarMensaje(
                101L, convA.id(),
                new SendConversationMessageRequest(
                        "@LogicDraft, ¿puedes analizar si falta alguna relación?",
                        "m-3",
                        false,
                        null,
                        null,
                        List.of(),
                        "tab-g")));
        assertEquals(result.agentMessage().id(), duplicateRetry.agentMessage().id());
        verifyNoMoreInteractions(contextualAgent);
    }

    @Test
    void preservesUserMessageAndFullHistoryOnProviderFailureAndCapsUpstreamContextToTenMessages() {
        ConversationSummaryResponse conv = as("gabriel@logicdraft.test", () ->
                service.crearConversacion(101L, new CreateConversationRequest("Contexto Largo", "tab-g")));

        // Seed 14 normal messages in PostgreSQL
        for (int i = 1; i <= 14; i++) {
            final int n = i;
            as("gabriel@logicdraft.test", () -> service.enviarMensaje(
                    101L, conv.id(),
                    new SendConversationMessageRequest("Turno previo #" + n, "seed-" + n, false, null, null, List.of(), "tab-g")));
        }

        // Simulate Ollama 503 failure on explicit "Preguntar al agente" (invokeAgent=true)
        when(contextualAgent.ask(eq(101L), any()))
                .thenThrow(new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, "Ollama offline"));

        SendMessageResultResponse failedAttempt = as("carlos@logicdraft.test", () -> service.enviarMensaje(
                101L, conv.id(),
                new SendConversationMessageRequest(
                        "Analiza las claves primarias del diagrama",
                        "ask-fail-1",
                        true,
                        null,
                        null,
                        List.of(),
                        "tab-c")));

        // Carlos's human message was persisted and NEVER rolled back!
        assertEquals("Carlos", failedAttempt.userMessage().autorNombre());
        assertEquals(ConversationMessageStatus.SENT, failedAttempt.userMessage().estado());
        assertNotNull(failedAttempt.agentError());
        assertNotNull(failedAttempt.agentMessage());
        assertEquals(ConversationMessageStatus.FAILED, failedAttempt.agentMessage().estado());

        // Full history in PostgreSQL has all 14 + Carlos's message + failed status notice = 16 records
        ConversationMessagesPageResponse fullHistory = as("gabriel@logicdraft.test", () ->
                service.listarMensajes(101L, conv.id(), null, 50));
        assertEquals(16, fullHistory.messages().size());

        // Now provider recovers; a new invocation checks that upstream context is capped at 10 SENT messages
        // and excludes the FAILED message!
        reset(contextualAgent);
        when(contextualAgent.ask(eq(101L), any()))
                .thenReturn(new AgentAskResponse("Todas las entidades tienen PK definida.", List.of()));

        SendMessageResultResponse recovered = as("carlos@logicdraft.test", () -> service.enviarMensaje(
                101L, conv.id(),
                new SendConversationMessageRequest(
                        "Analiza nuevamente las claves primarias",
                        "ask-ok-2",
                        true,
                        null,
                        null,
                        List.of(),
                        "tab-c")));

        assertNull(recovered.agentError());
        assertEquals(ConversationMessageStatus.SENT, recovered.agentMessage().estado());

        ArgumentCaptor<AgentAskRequest> askCaptor = ArgumentCaptor.forClass(AgentAskRequest.class);
        verify(contextualAgent).ask(eq(101L), askCaptor.capture());
        assertEquals(ConversationContextSelector.MAX_UPSTREAM_MESSAGES, askCaptor.getValue().conversation().size());
        assertTrue(askCaptor.getValue().conversation().stream()
                .noneMatch(m -> m.text().contains("no está disponible")));
    }

    private static void registerUser(
            UsuarioRepository users,
            MiembroTenantRepository tenants,
            MiembroWorkspaceRepository members,
            Long id,
            String email,
            String nombre,
            Long tenantId,
            Long workspaceId,
            RolWorkspace rol) {
        Usuario u = new Usuario();
        u.setId(id);
        u.setEmail(email);
        u.setNombre(nombre);
        when(users.findByEmail(email)).thenReturn(Optional.of(u));
        when(tenants.findByUsuarioIdAndTenantId(id, tenantId)).thenReturn(Optional.of(new MiembroTenant()));
        MiembroWorkspace mw = new MiembroWorkspace();
        mw.setRol(rol);
        when(members.findByUsuarioIdAndWorkspaceId(id, workspaceId)).thenReturn(Optional.of(mw));
    }

    private static <T> T as(String email, Supplier<T> action) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, "N/A", List.of()));
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static class MemoryTransactions extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
