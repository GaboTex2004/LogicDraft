package com.sw1.backend.ai.conversation;

import com.sw1.backend.ai.conversation.dto.ConversationMessageResponse;
import com.sw1.backend.ai.conversation.dto.ConversationMessagesPageResponse;
import com.sw1.backend.ai.conversation.dto.ConversationSummaryResponse;
import com.sw1.backend.ai.conversation.dto.CreateConversationRequest;
import com.sw1.backend.ai.conversation.dto.ProjectConversationsListResponse;
import com.sw1.backend.ai.conversation.dto.SendConversationMessageRequest;
import com.sw1.backend.ai.conversation.dto.SendMessageResultResponse;
import com.sw1.backend.ai.conversation.dto.UpdateConversationRequest;
import com.sw1.backend.ai.conversation.exception.ConversationConflictException;
import com.sw1.backend.ai.conversation.model.ConversacionAgente;
import com.sw1.backend.ai.conversation.model.ConversationState;
import com.sw1.backend.ai.conversation.model.MensajeConversacionAgente;
import com.sw1.backend.ai.conversation.model.MessageAuthorType;
import com.sw1.backend.ai.conversation.repository.ConversacionAgenteRepository;
import com.sw1.backend.ai.conversation.repository.MensajeConversacionAgenteRepository;
import com.sw1.backend.ai.conversation.repository.PropuestaUmlAgenteRepository;
import com.sw1.backend.ai.conversation.service.AgentConversationEventPublisher;
import com.sw1.backend.ai.conversation.service.SharedAgentConversationService;
import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.collaboration.controller.CollaborationController;
import com.sw1.backend.collaboration.dto.CollaborationClientEvent;
import com.sw1.backend.collaboration.dto.CollaborationEvent;
import com.sw1.backend.collaboration.model.CollaborationEventType;
import com.sw1.backend.collaboration.security.CollaborationIdentity;
import com.sw1.backend.collaboration.security.ProjectCollaborationAuthorizer;
import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.common.exception.RecursoNoEncontradoException;
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
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SharedAgentConversationStageATest {

    @Configuration
    @EnableTransactionManagement
    static class TxConfig {
    }

    private AnnotationConfigApplicationContext springContext;
    private SimpMessagingTemplate messaging;
    private SharedAgentConversationService service;
    private AgentConversationEventPublisher publisher;
    private MemoryTransactions txManager;

    private final AtomicLong convSeq = new AtomicLong(100);
    private final AtomicLong msgSeq = new AtomicLong(1000);
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
        ConversacionAgenteRepository convRepo = mock(ConversacionAgenteRepository.class);
        MensajeConversacionAgenteRepository msgRepo = mock(MensajeConversacionAgenteRepository.class);
        PropuestaUmlAgenteRepository propRepo = mock(PropuestaUmlAgenteRepository.class);
        messaging = mock(SimpMessagingTemplate.class);

        Tenant tenantA = new Tenant();
        tenantA.setId(1L);
        Workspace wsA = new Workspace();
        wsA.setId(10L);
        wsA.setTenant(tenantA);

        Proyecto project1 = new Proyecto();
        project1.setId(101L);
        project1.setNombre("Sistema Ventas");
        project1.setWorkspace(wsA);

        Proyecto project2 = new Proyecto();
        project2.setId(102L);
        project2.setNombre("Sistema Inventario");
        project2.setWorkspace(wsA);

        when(workspaces.findById(10L)).thenReturn(Optional.of(wsA));
        when(projects.findById(101L)).thenReturn(Optional.of(project1));
        when(projects.findById(102L)).thenReturn(Optional.of(project2));

        registerUser(users, tenants, members, 1L, "gabriel@logicdraft.test", "Gabriel", 1L, 10L, RolWorkspace.OWNER);
        registerUser(users, tenants, members, 2L, "carlos@logicdraft.test", "Carlos", 1L, 10L, RolWorkspace.EDITOR);
        registerUser(users, tenants, members, 3L, "lucia@logicdraft.test", "Lucía", 1L, 10L, RolWorkspace.VIEWER);
        // Outsider from another tenant/workspace
        Usuario outsider = new Usuario();
        outsider.setId(4L);
        outsider.setEmail("intruso@otro.test");
        outsider.setNombre("Intruso");
        when(users.findByEmail("intruso@otro.test")).thenReturn(Optional.of(outsider));

        when(convRepo.countByProyectoId(any())).thenAnswer(call -> {
            Long pid = call.getArgument(0);
            return conversationsStore.values().stream()
                    .filter(c -> Objects.equals(c.getProyecto().getId(), pid))
                    .count();
        });
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
            if (found != null && Objects.equals(found.getProyecto().getId(), pid)) {
                return Optional.of(found);
            }
            return Optional.empty();
        });
        when(convRepo.findByProyectoIdOrderByFechaActualizacionDescIdDesc(any())).thenAnswer(call -> {
            Long pid = call.getArgument(0);
            return conversationsStore.values().stream()
                    .filter(c -> Objects.equals(c.getProyecto().getId(), pid))
                    .sorted(Comparator.comparing(ConversacionAgente::getFechaActualizacion).reversed()
                            .thenComparing(ConversacionAgente::getId, Comparator.reverseOrder()))
                    .toList();
        });
        when(convRepo.findByProyectoIdAndEstadoOrderByFechaActualizacionDescIdDesc(any(), any())).thenAnswer(call -> {
            Long pid = call.getArgument(0);
            ConversationState state = call.getArgument(1);
            return conversationsStore.values().stream()
                    .filter(c -> Objects.equals(c.getProyecto().getId(), pid) && c.getEstado() == state)
                    .sorted(Comparator.comparing(ConversacionAgente::getFechaActualizacion).reversed()
                            .thenComparing(ConversacionAgente::getId, Comparator.reverseOrder()))
                    .toList();
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
                .thenReturn(Optional.empty());
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

        UsuarioActualService currentUser = new UsuarioActualService(users);
        WorkspaceAccessService accessService = new WorkspaceAccessService(
                currentUser, new TenantAccessService(currentUser, tenants), workspaces, members);

        txManager = new MemoryTransactions();
        springContext = new AnnotationConfigApplicationContext();
        springContext.register(TxConfig.class);
        springContext.registerBean(SimpMessagingTemplate.class, () -> messaging);
        springContext.registerBean(AgentConversationEventPublisher.class);
        springContext.refresh();
        publisher = springContext.getBean(AgentConversationEventPublisher.class);

        service = new SharedAgentConversationService(
                convRepo,
                msgRepo,
                propRepo,
                projects,
                accessService,
                currentUser,
                publisher,
                txManager);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (springContext != null) {
            springContext.close();
        }
    }

    @Test
    void createsMultipleConversationsRenamesArchivesAndIsolatesByProject() {
        ConversationSummaryResponse ventas = as("gabriel@logicdraft.test", () ->
                service.crearConversacion(101L, new CreateConversationRequest("Diseño del sistema de ventas", "tab-g")));
        ConversationSummaryResponse relaciones = as("carlos@logicdraft.test", () ->
                service.crearConversacion(101L, new CreateConversationRequest("Revisión de relaciones UML", "tab-c")));
        as("gabriel@logicdraft.test", () ->
                service.crearConversacion(102L, new CreateConversationRequest("Conversación de otro proyecto", "tab-g")));

        ProjectConversationsListResponse list101 = as("carlos@logicdraft.test", () ->
                service.listarConversaciones(101L, false));
        assertTrue(list101.canEdit());
        assertEquals(2, list101.conversations().size());

        // Cross-project access to conversation of project 101 through project 102 is rejected
        assertThrows(RecursoNoEncontradoException.class, () -> as("gabriel@logicdraft.test", () ->
                service.listarMensajes(102L, ventas.id(), null, 20)));

        // Rename and archive without losing history
        as("gabriel@logicdraft.test", () -> service.enviarMensaje(
                101L, relaciones.id(),
                new SendConversationMessageRequest("Revisemos Venta -> Producto", "msg-1", false, null, null, List.of(), "tab-g")));

        ConversationSummaryResponse archived = as("carlos@logicdraft.test", () ->
                service.actualizarConversacion(101L, relaciones.id(),
                        new UpdateConversationRequest("Revisión UML cerrada", ConversationState.ARCHIVED, "tab-c")));
        assertEquals("Revisión UML cerrada", archived.titulo());
        assertEquals(ConversationState.ARCHIVED, archived.estado());

        assertEquals(1, as("gabriel@logicdraft.test", () -> service.listarConversaciones(101L, false)).conversations().size());
        assertEquals(2, as("gabriel@logicdraft.test", () -> service.listarConversaciones(101L, true)).conversations().size());

        // Archived conversation retains its message history, blocks new messages until reactivated
        ConversationMessagesPageResponse archivedHistory = as("lucia@logicdraft.test", () ->
                service.listarMensajes(101L, relaciones.id(), null, 20));
        assertEquals(1, archivedHistory.messages().size());
        assertEquals("Gabriel", archivedHistory.messages().getFirst().autorNombre());

        assertThrows(ConversationConflictException.class, () -> as("gabriel@logicdraft.test", () ->
                service.enviarMensaje(101L, relaciones.id(),
                        new SendConversationMessageRequest("Intento en archivada", "msg-2", false, null, null, List.of(), "tab-g"))));
    }

    @Test
    void enforcesOwnerEditorViewerAndCrossTenantRolesStrictly() {
        ConversationSummaryResponse conv = as("gabriel@logicdraft.test", () ->
                service.crearConversacion(101L, new CreateConversationRequest("Consultas sobre base de datos", "tab-g")));

        as("gabriel@logicdraft.test", () -> service.enviarMensaje(
                101L, conv.id(),
                new SendConversationMessageRequest("He creado la entidad Producto.", "m-g1", false, null, null, List.of(), "tab-g")));
        as("carlos@logicdraft.test", () -> service.enviarMensaje(
                101L, conv.id(),
                new SendConversationMessageRequest("Voy a revisar sus atributos.", "m-c1", false, null, null, List.of(), "tab-c")));

        // VIEWER can list and read with real author names, but cannot create, rename, archive, or post
        ProjectConversationsListResponse viewerList = as("lucia@logicdraft.test", () ->
                service.listarConversaciones(101L, false));
        assertFalse(viewerList.canEdit());
        assertEquals(1, viewerList.conversations().size());

        ConversationMessagesPageResponse viewerHistory = as("lucia@logicdraft.test", () ->
                service.listarMensajes(101L, conv.id(), null, 20));
        assertEquals(List.of("Gabriel", "Carlos"),
                viewerHistory.messages().stream().map(ConversationMessageResponse::autorNombre).toList());
        assertTrue(viewerHistory.messages().stream().allMatch(m -> m.autorTipo() == MessageAuthorType.USER));

        assertThrows(AccesoDenegadoException.class, () -> as("lucia@logicdraft.test", () ->
                service.crearConversacion(101L, new CreateConversationRequest("No permitida", null))));
        assertThrows(AccesoDenegadoException.class, () -> as("lucia@logicdraft.test", () ->
                service.actualizarConversacion(101L, conv.id(), new UpdateConversationRequest("Hack", null, null))));
        assertThrows(AccesoDenegadoException.class, () -> as("lucia@logicdraft.test", () ->
                service.enviarMensaje(101L, conv.id(),
                        new SendConversationMessageRequest("Intento viewer", "m-v1", false, null, null, List.of(), null))));

        // Cross-tenant outsider cannot even read
        assertThrows(AccesoDenegadoException.class, () -> as("intruso@otro.test", () ->
                service.listarConversaciones(101L, false)));
        assertThrows(AccesoDenegadoException.class, () -> as("intruso@otro.test", () ->
                service.listarMensajes(101L, conv.id(), null, 20)));
    }

    @Test
    void supportsUnlimitedPaginatedHistoryDeduplicationAndSimultaneousCollaborators() throws Exception {
        ConversationSummaryResponse conv = as("gabriel@logicdraft.test", () ->
                service.crearConversacion(101L, new CreateConversationRequest("Propuestas del agente IA", "tab-g")));

        // Persist 25 messages (> 10 legacy cap) and recover all 25 through cursor pagination
        for (int i = 1; i <= 25; i++) {
            final int idx = i;
            String user = (i % 2 == 1) ? "gabriel@logicdraft.test" : "carlos@logicdraft.test";
            as(user, () -> service.enviarMensaje(
                    101L, conv.id(),
                    new SendConversationMessageRequest("Mensaje #" + idx, "cid-" + idx, false, null, null, List.of(), "tab")));
        }

        // Duplicate retry with same clientMessageId returns the original message without duplicating
        SendMessageResultResponse retry = as("gabriel@logicdraft.test", () -> service.enviarMensaje(
                101L, conv.id(),
                new SendConversationMessageRequest("Mensaje #1", "cid-1", false, null, null, List.of(), "tab")));
        assertEquals("Mensaje #1", retry.userMessage().contenido());
        assertEquals(25, messagesStore.size());

        List<String> recovered = new ArrayList<>();
        ConversationMessagesPageResponse page1 = as("carlos@logicdraft.test", () ->
                service.listarMensajes(101L, conv.id(), null, 10));
        assertTrue(page1.hasMore());
        assertEquals(10, page1.messages().size());
        assertEquals("Mensaje #16", page1.messages().getFirst().contenido());
        assertEquals("Mensaje #25", page1.messages().getLast().contenido());

        ConversationMessagesPageResponse page2 = as("carlos@logicdraft.test", () ->
                service.listarMensajes(101L, conv.id(), page1.nextBeforeId(), 10));
        assertTrue(page2.hasMore());
        assertEquals(10, page2.messages().size());

        ConversationMessagesPageResponse page3 = as("carlos@logicdraft.test", () ->
                service.listarMensajes(101L, conv.id(), page2.nextBeforeId(), 10));
        assertFalse(page3.hasMore());
        assertEquals(5, page3.messages().size());

        recovered.addAll(page3.messages().stream().map(ConversationMessageResponse::contenido).toList());
        recovered.addAll(page2.messages().stream().map(ConversationMessageResponse::contenido).toList());
        recovered.addAll(page1.messages().stream().map(ConversationMessageResponse::contenido).toList());
        assertEquals(25, recovered.size());
        assertEquals("Mensaje #1", recovered.getFirst());
        assertEquals("Mensaje #25", recovered.getLast());

        // Two collaborators writing simultaneously in parallel threads both persist cleanly
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var f1 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return as("gabriel@logicdraft.test", () -> service.enviarMensaje(
                        101L, conv.id(),
                        new SendConversationMessageRequest("Simultáneo Gabriel", "sim-g", false, null, null, List.of(), "tab-g")));
            });
            var f2 = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return as("carlos@logicdraft.test", () -> service.enviarMensaje(
                        101L, conv.id(),
                        new SendConversationMessageRequest("Simultáneo Carlos", "sim-c", false, null, null, List.of(), "tab-c")));
            });
            assertEquals("Gabriel", f1.get(5, TimeUnit.SECONDS).userMessage().autorNombre());
            assertEquals("Carlos", f2.get(5, TimeUnit.SECONDS).userMessage().autorNombre());
        }
        assertEquals(27, messagesStore.size());
    }

    @Test
    void publishesStompEventsOnlyAfterCommitAndBlocksClientForgedConversationEvents() {
        reset(messaging);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Usuario gabriel = new Usuario();
        gabriel.setId(1L);
        gabriel.setNombre("Gabriel");
        ConversationSummaryResponse summary = new ConversationSummaryResponse(
                500L, 101L, "Prueba Rollback", ConversationState.ACTIVE, 1L, "Gabriel", null, null);

        // Rollback must NEVER emit STOMP events
        tx.executeWithoutResult(status -> {
            publisher.scheduleConversationCreated(summary, gabriel, "tab-g");
            verifyNoInteractions(messaging);
            status.setRollbackOnly();
        });
        verifyNoInteractions(messaging);

        // Commit emits to /topic/proyectos/101
        ConversationSummaryResponse created = as("gabriel@logicdraft.test", () ->
                service.crearConversacion(101L, new CreateConversationRequest("Canal en vivo", "tab-g")));
        as("carlos@logicdraft.test", () -> service.enviarMensaje(
                101L, created.id(),
                new SendConversationMessageRequest("Hola en tiempo real", "rt-1", false, null, null, List.of(), "tab-c")));

        ArgumentCaptor<CollaborationEvent> captor = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(messaging, times(2)).convertAndSend(eq("/topic/proyectos/101"), captor.capture());
        assertEquals(CollaborationEventType.CONVERSATION_CREATED, captor.getAllValues().get(0).type());
        assertEquals(CollaborationEventType.CONVERSATION_MESSAGE_CREATED, captor.getAllValues().get(1).type());
        assertEquals("Carlos", captor.getAllValues().get(1).name());

        // Clients cannot forge CONVERSATION_* events directly through STOMP CollaborationController
        ProjectCollaborationAuthorizer authorizer = mock(ProjectCollaborationAuthorizer.class);
        when(authorizer.authorize(eq(101L), any())).thenReturn(new CollaborationIdentity(1L, "gabriel@logicdraft.test", "Gabriel"));
        CollaborationController wsController = new CollaborationController(authorizer, messaging);
        Principal principal = () -> "gabriel@logicdraft.test";

        for (CollaborationEventType reserved : List.of(
                CollaborationEventType.CONVERSATION_CREATED,
                CollaborationEventType.CONVERSATION_UPDATED,
                CollaborationEventType.CONVERSATION_MESSAGE_CREATED,
                CollaborationEventType.CONVERSATION_AI_STATUS,
                CollaborationEventType.CONVERSATION_PROPOSAL_UPDATED)) {
            CollaborationClientEvent forged = new CollaborationClientEvent(reserved, "tab-g", Map.of(), "forged");
            assertThrows(MessageDeliveryException.class,
                    () -> wsController.publish(101L, forged, principal, "sess-1"));
        }
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
