package com.sw1.backend.ai.conversation;

import com.sw1.backend.ai.agent.dto.AgentAskResponse;
import com.sw1.backend.ai.agent.service.ContextualAgentService;
import com.sw1.backend.ai.conversation.dto.ConversationSummaryResponse;
import com.sw1.backend.ai.conversation.dto.CreateConversationRequest;
import com.sw1.backend.ai.conversation.dto.ProposalDecisionRequest;
import com.sw1.backend.ai.conversation.dto.SendConversationMessageRequest;
import com.sw1.backend.ai.conversation.dto.SendMessageResultResponse;
import com.sw1.backend.ai.conversation.dto.SharedProposalResponse;
import com.sw1.backend.ai.conversation.exception.ConversationConflictException;
import com.sw1.backend.ai.conversation.model.AgentProposalState;
import com.sw1.backend.ai.conversation.model.ConversacionAgente;
import com.sw1.backend.ai.conversation.model.MensajeConversacionAgente;
import com.sw1.backend.ai.conversation.model.MessageAuthorType;
import com.sw1.backend.ai.conversation.model.PropuestaUmlAgente;
import com.sw1.backend.ai.conversation.repository.ConversacionAgenteRepository;
import com.sw1.backend.ai.conversation.repository.MensajeConversacionAgenteRepository;
import com.sw1.backend.ai.conversation.repository.PropuestaUmlAgenteRepository;
import com.sw1.backend.ai.conversation.service.AgentConversationEventPublisher;
import com.sw1.backend.ai.conversation.service.ServerDiagramOperationApplier;
import com.sw1.backend.ai.conversation.service.SharedAgentConversationService;
import com.sw1.backend.ai.diagram.dto.AttributeDefinition;
import com.sw1.backend.ai.diagram.dto.DiagramOperation;
import com.sw1.backend.ai.diagram.dto.EntityDefinition;
import com.sw1.backend.ai.diagram.dto.RelationshipDefinition;
import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import com.sw1.backend.ai.diagram.model.DiagramDataType;
import com.sw1.backend.ai.diagram.model.DiagramOperationType;
import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.collaboration.dto.CollaborationEvent;
import com.sw1.backend.collaboration.model.CollaborationEventType;
import com.sw1.backend.collaboration.security.CollaborationIdentity;
import com.sw1.backend.collaboration.service.DiagramCommitPublisher;
import com.sw1.backend.collaboration.service.ElementLockedException;
import com.sw1.backend.collaboration.service.ProjectElementLockService;
import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.diagrama.service.DiagramRevisionHistory;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SharedAgentConversationStageCTest {

    @Configuration
    @EnableTransactionManagement
    static class TxConfig {
    }

    private AnnotationConfigApplicationContext springContext;
    private SimpMessagingTemplate messaging;
    private ContextualAgentService contextualAgent;
    private ProjectElementLockService lockService;
    private DiagramRevisionHistory revisionHistory;
    private SharedAgentConversationService service;
    private PropuestaUmlAgenteRepository propRepo;

    private Proyecto project;
    private AtomicReference<Diagrama> storedDiagram;
    private final AtomicLong convSeq = new AtomicLong(300);
    private final AtomicLong msgSeq = new AtomicLong(3000);
    private final AtomicLong propSeq = new AtomicLong(400);
    private final Map<Long, ConversacionAgente> conversationsStore = new ConcurrentHashMap<>();
    private final Map<Long, MensajeConversacionAgente> messagesStore = new ConcurrentHashMap<>();
    private final Map<Long, AtomicReference<PropuestaUmlAgente>> proposalsStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        conversationsStore.clear();
        messagesStore.clear();
        proposalsStore.clear();

        UsuarioRepository users = mock(UsuarioRepository.class);
        MiembroTenantRepository tenants = mock(MiembroTenantRepository.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        MiembroWorkspaceRepository members = mock(MiembroWorkspaceRepository.class);
        ProyectoRepository projects = mock(ProyectoRepository.class);
        DiagramaRepository diagrams = mock(DiagramaRepository.class);
        ConversacionAgenteRepository convRepo = mock(ConversacionAgenteRepository.class);
        MensajeConversacionAgenteRepository msgRepo = mock(MensajeConversacionAgenteRepository.class);
        propRepo = mock(PropuestaUmlAgenteRepository.class);
        contextualAgent = mock(ContextualAgentService.class);
        messaging = mock(SimpMessagingTemplate.class);

        Tenant tenant = new Tenant();
        tenant.setId(1L);
        Workspace ws = new Workspace();
        ws.setId(10L);
        ws.setTenant(tenant);
        project = new Proyecto();
        project.setId(101L);
        project.setNombre("Sistema Ventas");
        project.setWorkspace(ws);

        storedDiagram = new AtomicReference<>(initialDiagram(0L));

        when(workspaces.findById(10L)).thenReturn(Optional.of(ws));
        when(projects.findById(101L)).thenReturn(Optional.of(project));

        when(diagrams.findByProyectoId(101L)).thenAnswer(call -> Optional.of(copyDiagram(storedDiagram.get())));
        when(diagrams.saveAndFlush(any())).thenAnswer(call -> {
            Diagrama candidate = call.getArgument(0);
            Diagrama old = storedDiagram.get();
            if (!Objects.equals(old.getRevision(), candidate.getRevision())) {
                throw new ObjectOptimisticLockingFailureException(Diagrama.class, old.getId());
            }
            Diagrama next = copyDiagram(candidate);
            next.setRevision(old.getRevision() + 1L);
            if (!storedDiagram.compareAndSet(old, next)) {
                throw new ObjectOptimisticLockingFailureException(Diagrama.class, old.getId());
            }
            return copyDiagram(next);
        });

        registerUser(users, tenants, members, 1L, "gabriel@logicdraft.test", "Gabriel", 1L, 10L, RolWorkspace.OWNER);
        registerUser(users, tenants, members, 2L, "carlos@logicdraft.test", "Carlos", 1L, 10L, RolWorkspace.EDITOR);
        registerUser(users, tenants, members, 3L, "lucia@logicdraft.test", "Lucía", 1L, 10L, RolWorkspace.VIEWER);

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
        when(msgRepo.findByConversacionIdAndClientMessageId(any(), any())).thenReturn(Optional.empty());
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
        when(msgRepo.findByConversacionIdAndIdLessThanOrderByIdDesc(any(), any(), any())).thenReturn(List.of());

        when(propRepo.saveAndFlush(any())).thenAnswer(call -> {
            PropuestaUmlAgente candidate = call.getArgument(0);
            if (candidate.getId() == null) {
                PropuestaUmlAgente inserted = copyProposal(candidate);
                inserted.setId(propSeq.incrementAndGet());
                inserted.setVersionLock(0L);
                proposalsStore.put(inserted.getId(), new AtomicReference<>(inserted));
                return copyProposal(inserted);
            }
            AtomicReference<PropuestaUmlAgente> slot = proposalsStore.get(candidate.getId());
            PropuestaUmlAgente old = slot.get();
            if (!Objects.equals(old.getVersionLock(), candidate.getVersionLock())) {
                throw new ObjectOptimisticLockingFailureException(PropuestaUmlAgente.class, old.getId());
            }
            PropuestaUmlAgente next = copyProposal(candidate);
            next.setVersionLock(old.getVersionLock() + 1L);
            if (!slot.compareAndSet(old, next)) {
                throw new ObjectOptimisticLockingFailureException(PropuestaUmlAgente.class, old.getId());
            }
            return copyProposal(next);
        });
        when(propRepo.findByIdAndConversacionIdAndProyectoId(any(), any(), any())).thenAnswer(call -> {
            Long id = call.getArgument(0);
            Long cid = call.getArgument(1);
            Long pid = call.getArgument(2);
            AtomicReference<PropuestaUmlAgente> slot = proposalsStore.get(id);
            if (slot == null) {
                return Optional.empty();
            }
            PropuestaUmlAgente current = slot.get();
            if (Objects.equals(current.getConversacion().getId(), cid)
                    && Objects.equals(current.getProyecto().getId(), pid)) {
                return Optional.of(copyProposal(current));
            }
            return Optional.empty();
        });
        when(propRepo.findByMensajeIdIn(any())).thenAnswer(call -> {
            java.util.Collection<Long> msgIds = call.getArgument(0);
            return proposalsStore.values().stream()
                    .map(AtomicReference::get)
                    .filter(p -> msgIds.contains(p.getMensaje().getId()))
                    .map(this::copyProposal)
                    .toList();
        });
        when(propRepo.findByMensajeId(any())).thenAnswer(call -> {
            Long mid = call.getArgument(0);
            return proposalsStore.values().stream()
                    .map(AtomicReference::get)
                    .filter(p -> Objects.equals(p.getMensaje().getId(), mid))
                    .map(this::copyProposal)
                    .findFirst();
        });

        UsuarioActualService currentUser = new UsuarioActualService(users);
        WorkspaceAccessService accessService = new WorkspaceAccessService(
                currentUser, new TenantAccessService(currentUser, tenants), workspaces, members);

        lockService = new ProjectElementLockService(
                messaging, Clock.systemUTC(), ProjectElementLockService.DEFAULT_LEASE_TTL, false);
        revisionHistory = new DiagramRevisionHistory();

        springContext = new AnnotationConfigApplicationContext();
        springContext.register(TxConfig.class);
        springContext.registerBean(UsuarioActualService.class, () -> currentUser);
        springContext.registerBean(SimpMessagingTemplate.class, () -> messaging);
        springContext.registerBean(DiagramCommitPublisher.class);
        springContext.registerBean(AgentConversationEventPublisher.class);
        springContext.refresh();

        service = new SharedAgentConversationService(
                convRepo,
                msgRepo,
                propRepo,
                projects,
                diagrams,
                accessService,
                currentUser,
                contextualAgent,
                lockService,
                springContext.getBean(DiagramCommitPublisher.class),
                revisionHistory,
                springContext.getBean(AgentConversationEventPublisher.class),
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
    void createsPendingProposalEnforcesViewerRestrictionAndAppliesTransactionallyOnAccept() {
        ConversationSummaryResponse conv = as("gabriel@logicdraft.test", () ->
                service.crearConversacion(101L, new CreateConversationRequest("Propuestas Ventas", "tab-g")));

        DiagramOperation addRel = new DiagramOperation(
                DiagramOperationType.ADD_RELATIONSHIP,
                null,
                null,
                null,
                new RelationshipDefinition("Venta", "Producto",
                        DiagramCardinality.ONE_ONE, DiagramCardinality.ZERO_MANY, "detalles", null));
        when(contextualAgent.ask(eq(101L), any())).thenReturn(
                new AgentAskResponse("Propongo relacionar Venta con Producto (1..1 a 0..N).", List.of(addRel)));

        SendMessageResultResponse askResult = as("gabriel@logicdraft.test", () -> service.enviarMensaje(
                101L, conv.id(),
                new SendConversationMessageRequest("@LogicDraft relaciona Venta con Producto", "p-1", false, null, null, List.of(), "tab-g")));

        SharedProposalResponse pending = askResult.agentMessage().proposal();
        assertNotNull(pending);
        assertEquals(AgentProposalState.PENDING, pending.estado());
        assertEquals(0L, pending.baseRevision());
        assertEquals("Gabriel", pending.solicitadoPorNombre());
        assertTrue(pending.affectedElementIds().containsAll(List.of("node-venta", "node-producto")));
        assertEquals(0, ((List<?>) storedDiagram.get().getContenido().get("edges")).size());

        // VIEWER can see the PENDING proposal in conversation history, but cannot accept or reject
        var viewerPage = as("lucia@logicdraft.test", () -> service.listarMensajes(101L, conv.id(), null, 20));
        assertEquals(AgentProposalState.PENDING, viewerPage.messages().getLast().proposal().estado());
        assertThrows(AccesoDenegadoException.class, () -> as("lucia@logicdraft.test", () ->
                service.aceptarPropuesta(101L, conv.id(), pending.id(), new ProposalDecisionRequest("tab-l", null))));
        assertThrows(AccesoDenegadoException.class, () -> as("lucia@logicdraft.test", () ->
                service.rechazarPropuesta(101L, conv.id(), pending.id(), new ProposalDecisionRequest("tab-l", null))));

        // EDITOR (Carlos) accepts transactionally -> diagram updates 0 -> 1, proposal ACCEPTED, both events emitted
        reset(messaging);
        SharedProposalResponse accepted = as("carlos@logicdraft.test", () ->
                service.aceptarPropuesta(101L, conv.id(), pending.id(), new ProposalDecisionRequest("tab-c", null)));

        assertEquals(AgentProposalState.ACCEPTED, accepted.estado());
        assertEquals("Carlos", accepted.decididoPorNombre());
        assertEquals(1L, accepted.aplicadaRevision());
        assertEquals(1L, storedDiagram.get().getRevision());
        assertEquals(1, ((List<?>) storedDiagram.get().getContenido().get("edges")).size());
        assertTrue(revisionHistory.find(101L, 1L).isPresent());

        ArgumentCaptor<CollaborationEvent> wsCaptor = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(messaging, times(2)).convertAndSend(eq("/topic/proyectos/101"), wsCaptor.capture());
        List<CollaborationEventType> types = wsCaptor.getAllValues().stream().map(CollaborationEvent::type).toList();
        assertTrue(types.contains(CollaborationEventType.DIAGRAM_COMMITTED));
        assertTrue(types.contains(CollaborationEventType.CONVERSATION_PROPOSAL_UPDATED));
    }

    @Test
    void preventsDuplicateConcurrentAcceptanceAndSupportsAuthorizedRejection() throws Exception {
        ConversationSummaryResponse conv = as("gabriel@logicdraft.test", () ->
                service.crearConversacion(101L, new CreateConversationRequest("Concurrencia Propuestas", "tab-g")));

        DiagramOperation addEntity = new DiagramOperation(
                DiagramOperationType.ADD_ENTITY,
                new EntityDefinition("Factura", List.of(
                        new AttributeDefinition("id", DiagramDataType.Long, true, false))),
                null, null, null);
        when(contextualAgent.ask(eq(101L), any())).thenReturn(
                new AgentAskResponse("Propongo crear la entidad Factura.", List.of(addEntity)));

        SharedProposalResponse pending = as("gabriel@logicdraft.test", () -> service.enviarMensaje(
                101L, conv.id(),
                new SendConversationMessageRequest("@LogicDraft crea la entidad Factura", "race-1", false, null, null, List.of(), "tab-g")))
                .agentMessage().proposal();

        // Synchronize Gabriel and Carlos so BOTH read PENDING simultaneously
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(call -> {
            Long id = call.getArgument(0);
            PropuestaUmlAgente snap = copyProposal(proposalsStore.get(id).get());
            if (reads.incrementAndGet() <= 2) {
                barrier.await(5, TimeUnit.SECONDS);
            }
            return Optional.of(snap);
        }).when(propRepo).findByIdAndConversacionIdAndProyectoId(eq(pending.id()), eq(conv.id()), eq(101L));

        int successes = 0;
        int conflicts = 0;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var f1 = pool.submit(() -> tryAcceptAs("gabriel@logicdraft.test", conv.id(), pending.id(), "tab-g"));
            var f2 = pool.submit(() -> tryAcceptAs("carlos@logicdraft.test", conv.id(), pending.id(), "tab-c"));
            for (boolean ok : List.of(f1.get(5, TimeUnit.SECONDS), f2.get(5, TimeUnit.SECONDS))) {
                if (ok) {
                    successes++;
                } else {
                    conflicts++;
                }
            }
        }
        assertEquals(1, successes, "Exactamente un colaborador debe ganar la aceptación");
        assertEquals(1, conflicts, "El segundo colaborador debe recibir conflicto 409 sin reaplicar");
        assertEquals(1L, storedDiagram.get().getRevision(), "El diagrama debe modificarse una sola vez");
        assertEquals(3, ((List<?>) storedDiagram.get().getContenido().get("nodes")).size());

        // Create a second proposal and reject it cleanly without modifying the diagram
        DiagramOperation deleteProducto = new DiagramOperation(
                DiagramOperationType.DELETE_ENTITY, null, "Producto", null, null);
        when(contextualAgent.ask(eq(101L), any())).thenReturn(
                new AgentAskResponse("Propuesta para eliminar Producto.", List.of(deleteProducto)));
        SharedProposalResponse toReject = as("gabriel@logicdraft.test", () -> service.enviarMensaje(
                101L, conv.id(),
                new SendConversationMessageRequest("@LogicDraft elimina la entidad Producto", "rej-1", false, null, null, List.of(), "tab-g")))
                .agentMessage().proposal();

        SharedProposalResponse rejected = as("carlos@logicdraft.test", () ->
                service.rechazarPropuesta(101L, conv.id(), toReject.id(),
                        new ProposalDecisionRequest("tab-c", "Conservaremos Producto")));
        assertEquals(AgentProposalState.REJECTED, rejected.estado());
        assertEquals("Carlos", rejected.decididoPorNombre());
        assertEquals(1L, storedDiagram.get().getRevision());
        assertEquals(3, ((List<?>) storedDiagram.get().getContenido().get("nodes")).size());
    }

    @Test
    void respects32BElementLocksAndMarksObsoleteProposalStaleWithoutMutatingDiagram() {
        ConversationSummaryResponse conv = as("gabriel@logicdraft.test", () ->
                service.crearConversacion(101L, new CreateConversationRequest("Bloqueos y Revisiones", "tab-g")));

        DiagramOperation addTotal = new DiagramOperation(
                DiagramOperationType.ADD_ATTRIBUTE,
                null,
                "Venta",
                new AttributeDefinition("total", DiagramDataType.Double, false, false),
                null);
        when(contextualAgent.ask(eq(101L), any())).thenReturn(
                new AgentAskResponse("Agrego atributo total a Venta.", List.of(addTotal)));

        SharedProposalResponse proposal = as("gabriel@logicdraft.test", () -> service.enviarMensaje(
                101L, conv.id(),
                new SendConversationMessageRequest("@LogicDraft agrega total Double a Venta", "lock-1", false, null, null, List.of(), "tab-g")))
                .agentMessage().proposal();

        // 1) Integration with 3.2B: Gabriel holds an active element lock on node-venta
        CollaborationIdentity gabrielIdentity = new CollaborationIdentity(1L, "gabriel@logicdraft.test", "Gabriel");
        lockService.acquire(101L, gabrielIdentity, "tab-g", "sess-g", List.of("node-venta"));

        // Carlos attempts to accept -> blocked by 3.2B ElementLockedException, proposal stays PENDING!
        ElementLockedException lockError = assertThrows(ElementLockedException.class, () ->
                as("carlos@logicdraft.test", () -> service.aceptarPropuesta(
                        101L, conv.id(), proposal.id(), new ProposalDecisionRequest("tab-c", null))));
        assertTrue(lockError.getMessage().contains("Gabriel"));
        assertEquals(0L, storedDiagram.get().getRevision());
        assertEquals(AgentProposalState.PENDING, proposalsStore.get(proposal.id()).get().getEstado());

        // Gabriel releases the lock on node-venta, BUT meanwhile someone commits revision 1 on the diagram
        lockService.release(101L, gabrielIdentity, "tab-g", List.of("node-venta"));
        Diagrama newer = copyDiagram(storedDiagram.get());
        newer.setRevision(1L);
        storedDiagram.set(newer);

        // 2) Obsolete proposal: Carlos tries to accept -> rejected with PROPOSAL_STALE, marked STALE in DB & STOMP, diagram NOT mutated!
        reset(messaging);
        ConversationConflictException stale = assertThrows(ConversationConflictException.class, () ->
                as("carlos@logicdraft.test", () -> service.aceptarPropuesta(
                        101L, conv.id(), proposal.id(), new ProposalDecisionRequest("tab-c", null))));
        assertEquals("PROPOSAL_STALE", stale.getCode());
        assertEquals(AgentProposalState.STALE, proposalsStore.get(proposal.id()).get().getEstado());
        assertEquals(1L, storedDiagram.get().getRevision());

        ArgumentCaptor<CollaborationEvent> wsCaptor = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(messaging, times(1)).convertAndSend(eq("/topic/proyectos/101"), wsCaptor.capture());
        assertEquals(CollaborationEventType.CONVERSATION_PROPOSAL_UPDATED, wsCaptor.getValue().type());
    }

    private boolean tryAcceptAs(String email, Long conversationId, Long proposalId, String clientId) {
        try {
            as(email, () -> service.aceptarPropuesta(
                    101L, conversationId, proposalId, new ProposalDecisionRequest(clientId, null)));
            return true;
        } catch (ConversationConflictException conflict) {
            return false;
        }
    }

    private Diagrama initialDiagram(Long revision) {
        Map<String, Object> venta = Map.of(
                "id", "node-venta",
                "type", "entity",
                "position", Map.of("x", 100.0, "y", 100.0),
                "data", Map.of(
                        "id", "node-venta",
                        "name", "Venta",
                        "attributes", List.of(Map.of(
                                "id", "attr-v-id", "name", "id", "type", "BIGINT", "primaryKey", true, "nullable", false))));
        Map<String, Object> producto = Map.of(
                "id", "node-producto",
                "type", "entity",
                "position", Map.of("x", 420.0, "y", 100.0),
                "data", Map.of(
                        "id", "node-producto",
                        "name", "Producto",
                        "attributes", List.of(Map.of(
                                "id", "attr-p-id", "name", "id", "type", "BIGINT", "primaryKey", true, "nullable", false))));
        Diagrama d = new Diagrama();
        d.setId(50L);
        d.setProyecto(project);
        d.setVersion(1);
        d.setRevision(revision);
        d.setContenido(ServerDiagramOperationApplier.deepCopyMap(
                Map.of("version", 1, "nodes", List.of(venta, producto), "edges", List.of())));
        return d;
    }

    private Diagrama copyDiagram(Diagrama src) {
        Diagrama copy = new Diagrama();
        copy.setId(src.getId() != null ? src.getId() : 50L);
        copy.setProyecto(src.getProyecto());
        copy.setVersion(src.getVersion());
        copy.setRevision(src.getRevision());
        copy.setContenido(ServerDiagramOperationApplier.deepCopyMap(src.getContenido()));
        return copy;
    }

    private PropuestaUmlAgente copyProposal(PropuestaUmlAgente src) {
        PropuestaUmlAgente copy = new PropuestaUmlAgente();
        copy.setId(src.getId());
        copy.setVersionLock(src.getVersionLock());
        copy.setConversacion(src.getConversacion());
        copy.setMensaje(src.getMensaje());
        copy.setProyecto(src.getProyecto());
        copy.setSolicitadoPor(src.getSolicitadoPor());
        copy.setSolicitadoPorNombre(src.getSolicitadoPorNombre());
        copy.setEstado(src.getEstado());
        copy.setBaseRevision(src.getBaseRevision());
        copy.setAplicadaRevision(src.getAplicadaRevision());
        copy.setOperacionesJson(ServerDiagramOperationApplier.deepCopyMap(src.getOperacionesJson()));
        copy.setElementosAfectadosJson(ServerDiagramOperationApplier.deepCopyMap(src.getElementosAfectadosJson()));
        copy.setResumen(src.getResumen());
        copy.setDecididoPor(src.getDecididoPor());
        copy.setDecididoPorNombre(src.getDecididoPorNombre());
        copy.setFechaDecision(src.getFechaDecision());
        copy.setResultadoAplicacion(src.getResultadoAplicacion());
        copy.setFechaCreacion(src.getFechaCreacion());
        return copy;
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
