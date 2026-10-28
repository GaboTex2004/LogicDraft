package com.sw1.backend.ai.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sw1.backend.ai.agent.dto.AgentAskRequest;
import com.sw1.backend.ai.agent.dto.AgentProjectContext;
import com.sw1.backend.ai.agent.dto.AgentUpstreamRequest;
import com.sw1.backend.ai.agent.service.AgentProjectContextService;
import com.sw1.backend.ai.agent.service.ContextualAgentService;
import com.sw1.backend.ai.client.AiServiceClient;
import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.client.AiServiceException.ErrorCategory;
import com.sw1.backend.ai.conversation.dto.ConversationMessagesPageResponse;
import com.sw1.backend.ai.conversation.dto.ConversationSummaryResponse;
import com.sw1.backend.ai.conversation.dto.CreateConversationRequest;
import com.sw1.backend.ai.conversation.dto.ProposalDecisionRequest;
import com.sw1.backend.ai.conversation.dto.SendConversationMessageRequest;
import com.sw1.backend.ai.conversation.dto.SendMessageResultResponse;
import com.sw1.backend.ai.conversation.dto.SharedProposalResponse;
import com.sw1.backend.ai.conversation.model.AgentProposalState;
import com.sw1.backend.ai.conversation.model.ConversacionAgente;
import com.sw1.backend.ai.conversation.model.ConversationMessageStatus;
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
import com.sw1.backend.ai.diagram.dto.DiagramContext;
import com.sw1.backend.ai.diagram.dto.DiagramInterpretResponse;
import com.sw1.backend.ai.diagram.dto.DiagramOperation;
import com.sw1.backend.ai.diagram.dto.EntityDefinition;
import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import com.sw1.backend.ai.diagram.model.DiagramDataType;
import com.sw1.backend.ai.diagram.model.DiagramOperationType;
import com.sw1.backend.ai.diagram.validation.ContextualOperationValidator;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper;
import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.collaboration.dto.CollaborationEvent;
import com.sw1.backend.collaboration.model.CollaborationEventType;
import com.sw1.backend.collaboration.security.CollaborationIdentity;
import com.sw1.backend.collaboration.service.DiagramCommitPublisher;
import com.sw1.backend.collaboration.service.ElementLockedException;
import com.sw1.backend.collaboration.service.ProjectElementLockService;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.diagrama.service.DiagramRevisionHistory;
import com.sw1.backend.diagrama.validation.DiagramDocumentValidator;
import com.sw1.backend.diagrama.validation.DiagramValidationException;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Mandatory end-to-end regression suite for UML v2 semantic interpretation,
 * FastAPI wire compatibility, differentiated error categories, and shared UML proposals.
 */
class UmlV2AgentContextAndProposalRegressionTest {

    @Configuration
    @EnableTransactionManagement
    static class TxConfig {
    }

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer fastApiServer;
    private final AtomicInteger upstreamStatus = new AtomicInteger(200);
    private final AtomicReference<String> upstreamBody = new AtomicReference<>(
            "{\"answer\":\"Hola! El diagrama tiene Producto, Venta y la clase asociativa compra.\",\"operations\":[]}");
    private final AtomicReference<String> lastRawRequestJson = new AtomicReference<>();

    private AnnotationConfigApplicationContext springContext;
    private SimpMessagingTemplate messaging;
    private AiServiceClient aiServiceClient;
    private AgentProjectContextService agentContextService;
    private ContextualAgentService contextualAgentService;
    private ProjectElementLockService lockService;
    private DiagramRevisionHistory revisionHistory;
    private SharedAgentConversationService conversationService;

    private Proyecto project;
    private final AtomicReference<Diagrama> storedDiagram = new AtomicReference<>();
    private final AtomicLong convSeq = new AtomicLong(500);
    private final AtomicLong msgSeq = new AtomicLong(5000);
    private final AtomicLong propSeq = new AtomicLong(600);
    private final Map<Long, ConversacionAgente> conversationsStore = new ConcurrentHashMap<>();
    private final Map<Long, MensajeConversacionAgente> messagesStore = new ConcurrentHashMap<>();
    private final Map<Long, AtomicReference<PropuestaUmlAgente>> proposalsStore = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        conversationsStore.clear();
        messagesStore.clear();
        proposalsStore.clear();
        lastRawRequestJson.set(null);
        upstreamStatus.set(200);
        upstreamBody.set("{\"answer\":\"Hola! El diagrama tiene Producto, Venta y la clase asociativa compra.\",\"operations\":[]}");

        fastApiServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fastApiServer.createContext("/api/agent/ask", exchange -> {
            String reqJson = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRawRequestJson.set(reqJson);
            byte[] responseBytes = upstreamBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(upstreamStatus.get(), responseBytes.length);
            exchange.getResponseBody().write(responseBytes);
            exchange.close();
        });
        fastApiServer.start();

        String baseUrl = "http://127.0.0.1:" + fastApiServer.getAddress().getPort();
        aiServiceClient = new AiServiceClient(baseUrl, 2);

        ConversacionAgenteRepository convRepo = mock(ConversacionAgenteRepository.class);
        MensajeConversacionAgenteRepository msgRepo = mock(MensajeConversacionAgenteRepository.class);
        PropuestaUmlAgenteRepository propRepo = mock(PropuestaUmlAgenteRepository.class);
        ProyectoRepository projects = mock(ProyectoRepository.class);
        DiagramaRepository diagrams = mock(DiagramaRepository.class);
        UsuarioRepository users = mock(UsuarioRepository.class);
        MiembroTenantRepository tenants = mock(MiembroTenantRepository.class);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        MiembroWorkspaceRepository members = mock(MiembroWorkspaceRepository.class);
        messaging = mock(SimpMessagingTemplate.class);

        Tenant tenant = new Tenant();
        tenant.setId(1L);
        Workspace workspace = new Workspace();
        workspace.setId(2L);
        workspace.setTenant(tenant);
        project = new Proyecto();
        project.setId(3L);
        project.setNombre("Ventas Colaborativas");
        project.setDescripcion("Proyecto UML v2 con clases y clase asociativa");
        project.setWorkspace(workspace);

        when(workspaces.findById(2L)).thenReturn(Optional.of(workspace));
        when(projects.findById(3L)).thenReturn(Optional.of(project));

        registerUser(users, tenants, members, 11L, "gabriel@logicdraft.test", "Gabriel", 1L, 2L, RolWorkspace.OWNER);
        registerUser(users, tenants, members, 22L, "carlos@logicdraft.test", "Carlos", 1L, 2L, RolWorkspace.EDITOR);

        Diagrama initial = new Diagrama();
        initial.setId(3L);
        initial.setProyecto(project);
        initial.setVersion(2);
        initial.setRevision(98L);
        initial.setContenido(project3RealUmlV2Document());
        storedDiagram.set(initial);

        when(diagrams.findByProyectoId(3L)).thenAnswer(call -> Optional.ofNullable(storedDiagram.get()));
        when(diagrams.saveAndFlush(any())).thenAnswer(call -> {
            Diagrama candidate = call.getArgument(0);
            Diagrama persisted = new Diagrama();
            persisted.setId(candidate.getId() != null ? candidate.getId() : 3L);
            persisted.setProyecto(candidate.getProyecto());
            persisted.setVersion(candidate.getVersion());
            long prevRev = storedDiagram.get() != null && storedDiagram.get().getRevision() != null
                    ? storedDiagram.get().getRevision()
                    : 0L;
            persisted.setRevision(prevRev + 1L);
            persisted.setContenido(ServerDiagramOperationApplier.deepCopyMap(candidate.getContenido()));
            storedDiagram.set(persisted);
            return persisted;
        });

        when(convRepo.countByProyectoId(3L)).thenAnswer(call -> (long) conversationsStore.size());
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
            slot.set(next);
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
            return (Objects.equals(current.getConversacion().getId(), cid)
                    && Objects.equals(current.getProyecto().getId(), pid))
                    ? Optional.of(copyProposal(current))
                    : Optional.empty();
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

        agentContextService = new AgentProjectContextService(projects, diagrams, accessService);
        contextualAgentService = new ContextualAgentService(agentContextService, aiServiceClient);

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

        conversationService = new SharedAgentConversationService(
                convRepo,
                msgRepo,
                propRepo,
                projects,
                diagrams,
                accessService,
                currentUser,
                contextualAgentService,
                lockService,
                springContext.getBean(DiagramCommitPublisher.class),
                revisionHistory,
                springContext.getBean(AgentConversationEventPublisher.class),
                new MemoryTransactions());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        if (fastApiServer != null) {
            fastApiServer.stop(0);
        }
        if (springContext != null) {
            springContext.close();
        }
    }

    // 1. Diagrama válido con CLASS
    @Test
    void case01_validDiagramWithClassNodesLoadsSemanticContext() {
        Map<String, Object> doc = Map.of(
                "version", 2,
                "nodes", List.of(
                        classNode("c-prod", "Producto", List.of(pkAttr("a-1", "id", "INTEGER"), colAttr("a-2", "nombre", "VARCHAR"))),
                        classNode("c-cat", "Categoria", List.of(pkAttr("a-3", "id", "INTEGER")))),
                "edges", List.of(
                        umlEdge("e-1", "c-cat", "c-prod", "ASSOCIATION", "ONE_ONE", "ZERO_MANY")));

        var snapshot = DiagramContextMapper.inspectDocument(doc);
        assertEquals(List.of("Producto", "Categoria"),
                snapshot.diagramContext().entities().stream().map(EntityDefinition::name).toList());
        assertEquals(List.of("c-prod", "c-cat"),
                snapshot.semanticNodes().stream().map(DiagramContextMapper.SemanticNode::id).toList());
        assertEquals(1, snapshot.diagramContext().relationships().size());
        assertEquals(DiagramCardinality.ZERO_MANY,
                snapshot.diagramContext().relationships().getFirst().targetCardinality());
    }

    // 2. ENTITY y CLASS combinados
    @Test
    void case02_mixedEntityAndClassDiagramIncludesBothKindsWithStableIds() {
        Map<String, Object> doc = Map.of(
                "version", 2,
                "nodes", List.of(
                        entityNode("ent-cliente", "Cliente", List.of(pkAttr("a-1", "id", "BIGINT"))),
                        classNode("cls-factura", "Factura", List.of(pkAttr("a-2", "id", "BIGINT"), colAttr("a-3", "fecha", "DATE")))),
                "edges", List.of());

        storedDiagram.get().setContenido(doc);
        AgentProjectContext ctx = as("gabriel@logicdraft.test", () ->
                agentContextService.load(3L, new AgentAskRequest("Revisa", "cls-factura", null, List.of())));

        assertEquals(List.of("ent-cliente", "cls-factura"),
                ctx.entities().stream().map(AgentProjectContext.AgentEntity::id).toList());
        assertEquals("cls-factura", ctx.selectedNodeId());
    }

    // 3. ASSOCIATION_CLASS entre dos nodos CLASS (estructura real del Proyecto 3)
    @Test
    void case03_associationClassBetweenTwoClassNodesReproducesProject3AndSucceeds() {
        Map<String, Object> project3Doc = project3RealUmlV2Document();
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(project3Doc));

        var snapshot = DiagramContextMapper.inspectDocument(project3Doc);
        assertEquals(List.of("Producto", "Venta", "compra"),
                snapshot.diagramContext().entities().stream().map(EntityDefinition::name).toList());
        assertEquals(2, snapshot.diagramContext().relationships().size());
        assertEquals(1, snapshot.diagramContext().associations().size());

        var assoc = snapshot.diagramContext().associations().getFirst();
        assertEquals("compra", assoc.entityName());
        assertEquals("venta_producto", assoc.tableName());
        assertEquals(List.of("Venta", "Producto"), assoc.endpointEntityNames());
        assertEquals(
                List.of("relationship-f08a5dd3-c8f8-4365-a2a8-ed1c05c64035",
                        "relationship-d807cd14-6292-4869-9b33-c73d766b36a3"),
                assoc.structuralRelationshipIds());
    }

    // 4. Asociación entre ENTITY y CLASS (incluyendo AGGREGATION/COMPOSITION)
    @Test
    void case04_associationAndCompositionBetweenEntityAndClassResolveCorrectly() {
        Map<String, Object> doc = Map.of(
                "version", 2,
                "nodes", List.of(
                        entityNode("ent-pedido", "Pedido", List.of(pkAttr("a-1", "id", "BIGINT"))),
                        classNode("cls-linea", "LineaPedido", List.of(pkAttr("a-2", "id", "BIGINT")))),
                "edges", List.of(
                        umlEdge("rel-comp", "ent-pedido", "cls-linea", "COMPOSITION", "ONE_ONE", "ONE_MANY")));

        var snapshot = DiagramContextMapper.inspectDocument(doc);
        assertEquals(1, snapshot.semanticRelationships().size());
        var rel = snapshot.semanticRelationships().getFirst();
        assertEquals("rel-comp", rel.id());
        assertEquals("COMPOSITION", rel.relationKind());
        assertEquals("Pedido", rel.sourceEntity());
        assertEquals("LineaPedido", rel.targetEntity());
        assertEquals(DiagramCardinality.ONE_MANY, rel.targetCardinality());
    }

    // 5. Presencia de INTERFACE y ENUMERATION + contrato JSON estricto con FastAPI
    @Test
    void case05_interfaceAndEnumerationAreExposedDescriptivelyAndProtectedFromMutationAndWireLeak() throws Exception {
        Map<String, Object> iface = new LinkedHashMap<>();
        iface.put("id", "node-iface");
        iface.put("type", "entity");
        iface.put("position", Map.of("x", 50, "y", 50));
        iface.put("data", Map.of(
                "id", "node-iface",
                "name", "Exportable",
                "kind", "INTERFACE",
                "attributes", List.of(),
                "methods", List.of(Map.of(
                        "id", "m-1", "name", "exportar", "visibility", "public",
                        "returnType", "String", "parameters", List.of()))));

        Map<String, Object> enumNode = new LinkedHashMap<>();
        enumNode.put("id", "node-enum");
        enumNode.put("type", "entity");
        enumNode.put("position", Map.of("x", 300, "y", 50));
        enumNode.put("data", Map.of(
                "id", "node-enum",
                "name", "EstadoVenta",
                "kind", "ENUMERATION",
                "attributes", List.of(),
                "literals", List.of("PENDIENTE", "PAGADA")));

        Map<String, Object> cls = classNode("node-venta", "Venta", List.of(pkAttr("a-1", "id", "INTEGER")));
        Map<String, Object> realEdge = Map.of(
                "id", "edge-real",
                "type", "relationship",
                "source", "node-venta",
                "target", "node-iface",
                "data", Map.of("relationKind", "REALIZATION"));

        Map<String, Object> doc = Map.of(
                "version", 2,
                "nodes", List.of(iface, enumNode, cls),
                "edges", List.of(realEdge));
        storedDiagram.get().setContenido(doc);

        AgentProjectContext ctx = as("gabriel@logicdraft.test", () ->
                agentContextService.load(3L, new AgentAskRequest("Describe el modelo", null, null, List.of())));

        assertEquals(Set.of("Exportable", "EstadoVenta"), ctx.readOnlyElementNames());
        assertNotNull(ctx.projectDescription());
        assertTrue(ctx.projectDescription().length() <= 500);
        assertTrue(ctx.projectDescription().contains("INTERFACE Exportable (exportar)"));
        assertTrue(ctx.projectDescription().contains("ENUMERATION EstadoVenta [PENDIENTE, PAGADA]"));
        assertTrue(ctx.projectDescription().contains("Venta -> Exportable (REALIZATION)"));

        // Verify Jackson wire JSON NEVER serializes readOnlyElementNames (FastAPI ConfigDict(extra="forbid"))
        String serialized = objectMapper.writeValueAsString(new AgentUpstreamRequest("Describe", ctx));
        assertFalse(serialized.contains("readOnlyElementNames"));

        DiagramContext diagCtx = DiagramContextMapper.fromDocument(doc);
        assertFalse(objectMapper.writeValueAsString(diagCtx).contains("readOnlyElementNames"));

        // Reject mutating or colliding with INTERFACE / ENUMERATION
        var mutateInterface = new DiagramOperation(
                DiagramOperationType.ADD_ATTRIBUTE, null, "Exportable",
                new AttributeDefinition("codigo", DiagramDataType.String, false, true), null);
        var error = assertThrows(AiServiceException.class, () ->
                ContextualOperationValidator.validate(diagCtx,
                        new DiagramInterpretResponse(List.of(mutateInterface)),
                        diagCtx.readOnlyElementNames()));
        assertEquals(ErrorCategory.OPERATION_VALIDATION_ERROR, error.getCategory());
        assertTrue(error.getMessage().contains("solo consulta"));

        var collideEnumName = new DiagramOperation(
                DiagramOperationType.ADD_ENTITY,
                new EntityDefinition("estadoventa", List.of()), null, null, null);
        assertThrows(AiServiceException.class, () ->
                ContextualOperationValidator.validate(diagCtx,
                        new DiagramInterpretResponse(List.of(collideEnumName)),
                        diagCtx.readOnlyElementNames()));
    }

    // 6. Documento sin nodos y proyecto sin diagrama
    @Test
    void case06_emptyProjectAndZeroNodeDocumentAllowAgentContext() {
        storedDiagram.get().setContenido(Map.of("version", 2, "nodes", List.of(), "edges", List.of()));
        AgentProjectContext zeroNodesCtx = as("gabriel@logicdraft.test", () ->
                agentContextService.load(3L, new AgentAskRequest("Hola", null, null, List.of())));
        assertTrue(zeroNodesCtx.entities().isEmpty());
        assertEquals(3L, zeroNodesCtx.diagramId());

        storedDiagram.set(null);
        AgentProjectContext noDiagramCtx = as("gabriel@logicdraft.test", () ->
                agentContextService.load(3L, new AgentAskRequest("Hola", null, null, List.of())));
        assertTrue(noDiagramCtx.entities().isEmpty());
        assertNull(noDiagramCtx.diagramId());
    }

    // 7. Diagramas UML v1 existentes
    @Test
    void case07_existingUmlV1DiagramsRemainFullyCompatible() {
        Map<String, Object> v1Doc = Map.of(
                "version", 1,
                "nodes", List.of(
                        Map.of("id", "n-1", "data", Map.of("name", "Alumno", "attributes",
                                List.of(Map.of("name", "id", "type", "BIGINT", "primaryKey", true)))),
                        Map.of("id", "n-2", "data", Map.of("name", "Curso", "attributes",
                                List.of(Map.of("name", "id", "type", "BIGINT", "primaryKey", true))))),
                "edges", List.of(
                        Map.of("id", "e-legacy", "source", "n-1", "target", "n-2",
                                "data", Map.of("relationshipType", "ONE_TO_MANY"))));

        DiagramContext projected = DiagramContextMapper.fromDocument(v1Doc);
        assertEquals(List.of("Alumno", "Curso"),
                projected.entities().stream().map(EntityDefinition::name).toList());
        assertEquals(DiagramCardinality.ONE_ONE, projected.relationships().getFirst().sourceCardinality());
        assertEquals(DiagramCardinality.ZERO_MANY, projected.relationships().getFirst().targetCardinality());
    }

    // 8. Documento realmente inválido (rechazado con categoría clara, conserva mensaje humano)
    @Test
    void case08_trulyInvalidUmlDocumentFailsClearlyWithoutCallingFastApiAndPreservesHumanMessage() {
        // Broken association pointing to non-existent node ID "missing-node"
        Map<String, Object> brokenDoc = Map.of(
                "version", 2,
                "nodes", List.of(
                        classNode("c-1", "Producto", List.of(pkAttr("a-1", "id", "INTEGER"))),
                        Map.of("id", "c-assoc", "type", "entity", "position", Map.of("x", 0, "y", 0),
                                "data", Map.of(
                                        "id", "c-assoc",
                                        "name", "compra",
                                        "kind", "ASSOCIATION_CLASS",
                                        "attributes", List.of(pkAttr("a-2", "id", "INTEGER")),
                                        "association", Map.of(
                                                "kind", "MANY_TO_MANY_ASSOCIATION",
                                                "tableName", "venta_producto",
                                                "uniquePair", true,
                                                "endpoints", List.of(
                                                        Map.of("role", "SOURCE", "entityId", "c-1", "relationshipId", "r-1", "foreignKeyName", "producto_id"),
                                                        Map.of("role", "TARGET", "entityId", "missing-node", "relationshipId", "r-2", "foreignKeyName", "venta_id")))))),
                "edges", List.of(umlEdge("r-1", "c-1", "c-assoc", null, "ONE_ONE", "ONE_MANY")));
        storedDiagram.get().setContenido(brokenDoc);

        AiServiceException directErr = assertThrows(AiServiceException.class,
                () -> DiagramContextMapper.inspectDocument(brokenDoc));
        assertEquals(ErrorCategory.INVALID_UML_DOCUMENT, directErr.getCategory());
        assertTrue(directErr.getTechnicalDetail().contains("missing-node"));

        ConversationSummaryResponse conv = as("gabriel@logicdraft.test", () ->
                conversationService.crearConversacion(3L, new CreateConversationRequest("Prueba Documento Roto", "tab-g")));
        SendMessageResultResponse result = as("gabriel@logicdraft.test", () ->
                conversationService.enviarMensaje(3L, conv.id(),
                        new SendConversationMessageRequest("@LogicDraft Hola", "msg-broken-1", false, null, null, List.of(), "tab-g")));

        // FastAPI was NEVER called, human message WAS preserved, and error clearly states structural inconsistency
        assertNull(lastRawRequestJson.get());
        assertEquals(ConversationMessageStatus.SENT, result.userMessage().estado());
        assertEquals("@LogicDraft Hola", result.userMessage().contenido());
        assertNotNull(result.agentMessage());
        assertEquals(ConversationMessageStatus.FAILED, result.agentMessage().estado());
        assertTrue(result.agentMessage().contenido().contains("inconsistencias estructurales"));
    }

    // 9. Pregunta general sin operaciones UML (@LogicDraft Hola sobre Proyecto 3)
    @Test
    void case09_generalQuestionWithoutUmlOperationsSucceedsOnProject3ClassDiagram() {
        ConversationSummaryResponse conv = as("gabriel@logicdraft.test", () ->
                conversationService.crearConversacion(3L, new CreateConversationRequest("General", "tab-g")));

        SendMessageResultResponse result = as("gabriel@logicdraft.test", () ->
                conversationService.enviarMensaje(3L, conv.id(),
                        new SendConversationMessageRequest("@LogicDraft Hola", "msg-hola-1", false, null, null, List.of(), "tab-g")));

        assertNull(result.agentError());
        assertNotNull(result.agentMessage());
        assertEquals(ConversationMessageStatus.SENT, result.agentMessage().estado());
        assertNull(result.agentMessage().proposal());
        assertTrue(result.agentMessage().contenido().contains("Producto"));
        assertNotNull(lastRawRequestJson.get());
        assertFalse(lastRawRequestJson.get().contains("readOnlyElementNames"));
    }

    // 10. Pregunta contextual sobre una clase CLASS
    @Test
    void case10_contextualQuestionAboutClassSendsFullClassAndAssociationContextToFastApi() throws Exception {
        upstreamBody.set("{\"answer\":\"La clase Producto tiene ID (Integer, PK) y Nombre (String), y participa en la clase asociativa compra con Venta.\",\"operations\":[]}");
        ConversationSummaryResponse conv = as("carlos@logicdraft.test", () ->
                conversationService.crearConversacion(3L, new CreateConversationRequest("Consultas UML", "tab-c")));

        SendMessageResultResponse result = as("carlos@logicdraft.test", () ->
                conversationService.enviarMensaje(3L, conv.id(),
                        new SendConversationMessageRequest(
                                "@LogicDraft ¿Qué atributos tiene Producto y cómo se relaciona con compra?",
                                "msg-ctx-1",
                                false,
                                "entity-8a0d0094-0c00-4409-8652-b338a6aa9cb1",
                                null,
                                List.of(),
                                "tab-c")));

        assertNull(result.agentError());
        assertEquals(ConversationMessageStatus.SENT, result.agentMessage().estado());
        assertTrue(result.agentMessage().contenido().contains("Producto"));

        Map<?, ?> sentPayload = objectMapper.readValue(lastRawRequestJson.get(), Map.class);
        Map<?, ?> sentContext = (Map<?, ?>) sentPayload.get("context");
        assertEquals("entity-8a0d0094-0c00-4409-8652-b338a6aa9cb1", sentContext.get("selectedNodeId"));
        List<?> sentEntities = (List<?>) sentContext.get("entities");
        assertEquals(3, sentEntities.size());
        List<?> sentAssociations = (List<?>) sentContext.get("associations");
        assertEquals(1, sentAssociations.size());
    }

    // 11. Solicitud válida de modificación sobre CLASS (propuesta PENDING, bloqueos 3.2B, revisión, STOMP y preservación de kind=CLASS)
    @Test
    void case11_validModificationProposalOnClassPreservesClassKindEnforcesLocksAndPublishesStomp() {
        upstreamBody.set("""
                {"answer":"Propongo agregar el atributo precio a la clase Producto.",
                 "operations":[{"type":"ADD_ATTRIBUTE","entityName":"Producto",
                 "attribute":{"name":"precio","dataType":"Double","primaryKey":false,"nullable":false}}]}
                """);

        ConversationSummaryResponse conv = as("gabriel@logicdraft.test", () ->
                conversationService.crearConversacion(3L, new CreateConversationRequest("Evolución Producto", "tab-g")));

        SendMessageResultResponse askResult = as("gabriel@logicdraft.test", () ->
                conversationService.enviarMensaje(3L, conv.id(),
                        new SendConversationMessageRequest(
                                "@LogicDraft Agrega el atributo precio Double no nulo a Producto",
                                "msg-prop-1",
                                false,
                                null,
                                null,
                                List.of(),
                                "tab-g")));

        assertNull(askResult.agentError());
        SharedProposalResponse proposal = askResult.agentMessage().proposal();
        assertNotNull(proposal);
        assertEquals(AgentProposalState.PENDING, proposal.estado());
        assertEquals(98L, proposal.baseRevision());
        assertEquals(List.of("entity-8a0d0094-0c00-4409-8652-b338a6aa9cb1"), proposal.affectedElementIds());

        // Diagram has NOT been mutated before acceptance (still revision 98, 2 attributes on Producto)
        assertEquals(98L, storedDiagram.get().getRevision());

        // If Carlos holds an active 3.2B element lock on Producto, Gabriel's acceptance is blocked with ElementLockedException (409)
        CollaborationIdentity carlosIdentity = new CollaborationIdentity(22L, "carlos@logicdraft.test", "Carlos");
        lockService.acquire(3L, carlosIdentity, "tab-c", "session-c",
                List.of("entity-8a0d0094-0c00-4409-8652-b338a6aa9cb1"));

        assertThrows(ElementLockedException.class, () -> as("gabriel@logicdraft.test", () ->
                conversationService.aceptarPropuesta(3L, conv.id(), proposal.id(), new ProposalDecisionRequest("tab-g", null))));
        assertEquals(98L, storedDiagram.get().getRevision());

        // Once Carlos releases the lock, Gabriel accepts the proposal transactionally
        lockService.release(3L, carlosIdentity, "tab-c",
                List.of("entity-8a0d0094-0c00-4409-8652-b338a6aa9cb1"));
        reset(messaging);

        SharedProposalResponse accepted = as("gabriel@logicdraft.test", () ->
                conversationService.aceptarPropuesta(3L, conv.id(), proposal.id(), new ProposalDecisionRequest("tab-g", null)));

        assertEquals(AgentProposalState.ACCEPTED, accepted.estado());
        assertEquals(99L, accepted.aplicadaRevision());
        assertEquals(99L, storedDiagram.get().getRevision());
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(storedDiagram.get().getContenido()));

        // Verify Producto KEPT kind="CLASS" (never converted to ENTITY!) and now has 3 attributes
        List<?> updatedNodes = (List<?>) storedDiagram.get().getContenido().get("nodes");
        Map<?, ?> updatedProducto = updatedNodes.stream()
                .map(n -> (Map<?, ?>) n)
                .filter(n -> "entity-8a0d0094-0c00-4409-8652-b338a6aa9cb1".equals(n.get("id")))
                .findFirst()
                .orElseThrow();
        Map<?, ?> prodData = (Map<?, ?>) updatedProducto.get("data");
        assertEquals("CLASS", prodData.get("kind"));
        assertEquals(3, ((List<?>) prodData.get("attributes")).size());

        // Verify STOMP broadcast DIAGRAM_COMMITTED + CONVERSATION_PROPOSAL_UPDATED
        ArgumentCaptor<CollaborationEvent> wsCaptor = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(messaging, atLeast(2)).convertAndSend(eq("/topic/proyectos/3"), wsCaptor.capture());
        List<CollaborationEventType> emittedTypes = wsCaptor.getAllValues().stream().map(CollaborationEvent::type).toList();
        assertTrue(emittedTypes.contains(CollaborationEventType.DIAGRAM_COMMITTED));
        assertTrue(emittedTypes.contains(CollaborationEventType.CONVERSATION_PROPOSAL_UPDATED));
    }

    // 12. Propuesta inválida (colisión con FK estructural o entidad inexistente)
    @Test
    void case12_invalidModificationProposalIsRejectedSafelyWithoutCorruptingDiagram() {
        // Attempting to add attribute "ventaId" to ASSOCIATION_CLASS "compra" collides with structural FK "venta_id"
        upstreamBody.set("""
                {"answer":"Agrego ventaId a compra.",
                 "operations":[{"type":"ADD_ATTRIBUTE","entityName":"compra",
                 "attribute":{"name":"ventaId","dataType":"Integer","primaryKey":false,"nullable":false}}]}
                """);

        ConversationSummaryResponse conv = as("gabriel@logicdraft.test", () ->
                conversationService.crearConversacion(3L, new CreateConversationRequest("Propuesta Inválida", "tab-g")));

        SendMessageResultResponse res = as("gabriel@logicdraft.test", () ->
                conversationService.enviarMensaje(3L, conv.id(),
                        new SendConversationMessageRequest("@LogicDraft Agrega ventaId a compra", "msg-inv-1", false, null, null, List.of(), "tab-g")));

        assertNotNull(res.agentMessage().proposal());
        assertEquals(AgentProposalState.INVALID, res.agentMessage().proposal().estado());
        assertTrue(res.agentMessage().proposal().resultadoAplicacion().contains("clave foranea estructural"));
        assertEquals(98L, storedDiagram.get().getRevision());

        // Also verify ContextualOperationValidator rejects non-existent class before creating proposal
        upstreamBody.set("""
                {"answer":"Agrego atributo a Fantasma.",
                 "operations":[{"type":"ADD_ATTRIBUTE","entityName":"Fantasma",
                 "attribute":{"name":"x","dataType":"String","primaryKey":false,"nullable":true}}]}
                """);
        SendMessageResultResponse resMissing = as("gabriel@logicdraft.test", () ->
                conversationService.enviarMensaje(3L, conv.id(),
                        new SendConversationMessageRequest("@LogicDraft Agrega x a Fantasma", "msg-inv-2", false, null, null, List.of(), "tab-g")));
        assertEquals(ConversationMessageStatus.FAILED, resMissing.agentMessage().estado());
        assertEquals(98L, storedDiagram.get().getRevision());
    }

    // 13. Conservación de IDs estables, cardinalidades, kind: CLASS y referencias sin mutar relaciones UML no multiplicativas
    @Test
    void case13_preservesStableIdsCardinalitiesClassKindMethodsLiteralsAndNonMultiplicativeEdges() {
        Map<String, Object> doc = project3RealUmlV2Document();
        List<Map<String, Object>> nodes = ServerDiagramOperationApplier.deepCopyMap(doc)
                .get("nodes") instanceof List<?> rawN
                ? rawN.stream().map(item -> (Map<String, Object>) item).toList()
                : List.of();
        assertEquals(3, nodes.size());

        // Add an INTERFACE and a REALIZATION edge WITHOUT sourceCardinality/targetCardinality
        Map<String, Object> richDoc = ServerDiagramOperationApplier.deepCopyMap(doc);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> richNodes = (List<Map<String, Object>>) richDoc.get("nodes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> richEdges = (List<Map<String, Object>>) richDoc.get("edges");

        richNodes.add(new LinkedHashMap<>(Map.of(
                "id", "iface-auditable",
                "type", "entity",
                "position", Map.of("x", 600, "y", 100),
                "data", new LinkedHashMap<>(Map.of(
                        "id", "iface-auditable",
                        "name", "Auditable",
                        "kind", "INTERFACE",
                        "attributes", List.of(),
                        "methods", List.of(Map.of(
                                "id", "m-aud-1", "name", "auditar", "visibility", "public",
                                "returnType", "void", "parameters", List.of())))))));
        richEdges.add(new LinkedHashMap<>(Map.of(
                "id", "edge-realization-venta",
                "type", "relationship",
                "source", "entity-e580e42d-40e0-4a2c-a875-09d15c3f6b2a",
                "target", "iface-auditable",
                "data", new LinkedHashMap<>(Map.of("relationKind", "REALIZATION")))));

        var applied = ServerDiagramOperationApplier.apply(richDoc, List.of(
                new DiagramOperation(DiagramOperationType.ADD_ATTRIBUTE, null, "Venta",
                        new AttributeDefinition("total", DiagramDataType.Double, false, false), null)));

        assertEquals(2, applied.version());
        assertEquals(List.of("entity-e580e42d-40e0-4a2c-a875-09d15c3f6b2a"), applied.affectedElementIds());
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(applied.document()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outEdges = (List<Map<String, Object>>) applied.document().get("edges");
        Map<String, Object> preservedRealization = outEdges.stream()
                .filter(e -> "edge-realization-venta".equals(e.get("id")))
                .findFirst()
                .orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> realData = (Map<String, Object>) preservedRealization.get("data");
        assertEquals("REALIZATION", realData.get("relationKind"));
        assertFalse(realData.containsKey("sourceCardinality"));
        assertFalse(realData.containsKey("targetCardinality"));
    }

    // 14. Respuesta real del agente sobre HTTP cuando FastAPI está disponible y diferenciación de errores FastAPI vs Ollama vs Modelo
    @Test
    void case14_endToEndHttpResponseAndDifferentiatedFastApiVsOllamaVsInvalidModelErrors() {
        ConversationSummaryResponse conv = as("gabriel@logicdraft.test", () ->
                conversationService.crearConversacion(3L, new CreateConversationRequest("Diagnóstico Red", "tab-g")));

        // 14a. FastAPI available -> HTTP 200 end-to-end
        SendMessageResultResponse ok = as("gabriel@logicdraft.test", () ->
                conversationService.enviarMensaje(3L, conv.id(),
                        new SendConversationMessageRequest("@LogicDraft Resume el diagrama", "msg-e2e-ok", false, null, null, List.of(), "tab-g")));
        assertEquals(ConversationMessageStatus.SENT, ok.agentMessage().estado());

        // 14b. Ollama unavailable (FastAPI returns HTTP 503) -> AI_PROVIDER_ERROR
        upstreamStatus.set(503);
        upstreamBody.set("{\"detail\":\"Ollama provider unavailable\"}");
        SendMessageResultResponse ollamaDown = as("gabriel@logicdraft.test", () ->
                conversationService.enviarMensaje(3L, conv.id(),
                        new SendConversationMessageRequest("@LogicDraft Consulta con Ollama caído", "msg-e2e-503", false, null, null, List.of(), "tab-g")));
        assertEquals(ConversationMessageStatus.FAILED, ollamaDown.agentMessage().estado());
        assertTrue(ollamaDown.agentMessage().contenido().contains("proveedor de IA no está disponible"));

        // 14c. Invalid model response (FastAPI returns HTTP 502) -> INVALID_MODEL_RESPONSE
        upstreamStatus.set(502);
        upstreamBody.set("{\"detail\":\"Bad model JSON\"}");
        SendMessageResultResponse badModel = as("gabriel@logicdraft.test", () ->
                conversationService.enviarMensaje(3L, conv.id(),
                        new SendConversationMessageRequest("@LogicDraft Consulta con JSON inválido", "msg-e2e-502", false, null, null, List.of(), "tab-g")));
        assertEquals(ConversationMessageStatus.FAILED, badModel.agentMessage().estado());
        assertTrue(badModel.agentMessage().contenido().contains("respuesta no válida"));

        // 14d. FastAPI unreachable (stop local HTTP server) -> FASTAPI_COMMUNICATION_ERROR
        fastApiServer.stop(0);
        fastApiServer = null;
        SendMessageResultResponse fastApiDown = as("gabriel@logicdraft.test", () ->
                conversationService.enviarMensaje(3L, conv.id(),
                        new SendConversationMessageRequest("@LogicDraft Consulta con FastAPI apagado", "msg-e2e-conn", false, null, null, List.of(), "tab-g")));
        assertEquals(ConversationMessageStatus.FAILED, fastApiDown.agentMessage().estado());
        assertTrue(fastApiDown.agentMessage().contenido().contains("servicio de IA (FastAPI)"));

        // Verify ALL 4 human messages were preserved in history!
        ConversationMessagesPageResponse history = as("gabriel@logicdraft.test", () ->
                conversationService.listarMensajes(3L, conv.id(), null, 50));
        long humanCount = history.messages().stream().filter(m -> m.autorTipo() == MessageAuthorType.USER).count();
        assertEquals(4L, humanCount);
    }

    @Test
    void semanticSafetyRejectsMaliciousDeleteFromFastApiWithoutPendingProposalOrDiagramWrite() {
        Map<String, Object> before = project3WithCompoundAttribute();
        storedDiagram.get().setContenido(before);
        upstreamBody.set("""
                {"answer":"Eliminar Producto", "operations":[{"type":"DELETE_ENTITY","entityName":"Producto"}]}
                """);
        var conv = as("gabriel@logicdraft.test", () -> conversationService.crearConversacion(
                3L, new CreateConversationRequest("Seguridad semántica", "tab-g")));
        for (String prompt : List.of("Elimina el atributo precio de venta de Producto.",
                "elimina de Producto el atributo precio de venta",
                "elimina de la clase Producto el atributo precio de venta",
                "renombra precio de venta a precio en Producto")) {
            var result = as("gabriel@logicdraft.test", () -> conversationService.enviarMensaje(3L, conv.id(),
                    new SendConversationMessageRequest("@LogicDraft " + prompt, null, false, null, null, List.of(), "tab-g")));
            assertNotNull(lastRawRequestJson.get()); // Real client/validators; only upstream HTTP and repositories simulated.
            assertEquals(ConversationMessageStatus.SENT, result.userMessage().estado());
            assertEquals(ConversationMessageStatus.FAILED, result.agentMessage().estado());
            assertTrue(result.agentError().contains("objetivo explícito"));
            assertNull(result.agentMessage().proposal());
            assertTrue(proposalsStore.isEmpty());
            assertEquals(98L, storedDiagram.get().getRevision());
            assertEquals(before, storedDiagram.get().getContenido());
        }
    }

    @Test
    void semanticSafetyCorrectAttributeDeletionRequiresAcceptanceAndPreservesClassAndAssociation() {
        Map<String, Object> before = project3WithCompoundAttribute();
        storedDiagram.get().setContenido(before);
        upstreamBody.set("""
                {"answer":"Propuesta", "operations":[{"type":"DELETE_ATTRIBUTE","entityName":"Producto","attributeName":"precio de venta"}]}
                """);
        var conv = as("gabriel@logicdraft.test", () -> conversationService.crearConversacion(
                3L, new CreateConversationRequest("Eliminar atributo", "tab-g")));
        var result = as("gabriel@logicdraft.test", () -> conversationService.enviarMensaje(3L, conv.id(),
                new SendConversationMessageRequest("@LogicDraft elimina de Producto el atributo precio de venta",
                        "semantic-delete-attribute", false, null, null, List.of(), "tab-g")));
        assertNull(result.agentError());
        var proposal = result.agentMessage().proposal();
        assertEquals(AgentProposalState.PENDING, proposal.estado());
        assertEquals(before, storedDiagram.get().getContenido());
        var accepted = as("gabriel@logicdraft.test", () -> conversationService.aceptarPropuesta(
                3L, conv.id(), proposal.id(), new ProposalDecisionRequest("tab-g", null)));
        assertEquals(AgentProposalState.ACCEPTED, accepted.estado());
        assertEquals(99L, storedDiagram.get().getRevision());
        assertEquals(project3RealUmlV2Document(), storedDiagram.get().getContenido());
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(storedDiagram.get().getContenido()));
    }

    @Test
    void semanticSafetyBlocksPreviouslyPersistedUnsafePendingProposalAtAcceptance() {
        Map<String, Object> before = project3WithCompoundAttribute();
        storedDiagram.get().setContenido(before);
        upstreamBody.set("""
                {"answer":"Propuesta", "operations":[{"type":"DELETE_ENTITY","entityName":"Producto"}]}
                """);
        var conv = as("gabriel@logicdraft.test", () -> conversationService.crearConversacion(
                3L, new CreateConversationRequest("Propuesta antigua", "tab-g")));
        var result = as("gabriel@logicdraft.test", () -> conversationService.enviarMensaje(3L, conv.id(),
                new SendConversationMessageRequest("@LogicDraft elimina la clase Producto",
                        "old-unsafe", false, null, null, List.of(), "tab-g")));
        var proposal = result.agentMessage().proposal();
        assertEquals(AgentProposalState.PENDING, proposal.estado());
        // Simulate a pre-fix persisted proposal whose operations contradict its immutable human origin.
        messagesStore.get(result.userMessage().id()).setContenido(
                "@LogicDraft elimina de Producto el atributo precio de venta");
        assertThrows(com.sw1.backend.diagrama.validation.DiagramValidationException.class,
                () -> as("gabriel@logicdraft.test", () -> conversationService.aceptarPropuesta(
                        3L, conv.id(), proposal.id(), new ProposalDecisionRequest("tab-g", null))));
        assertEquals(AgentProposalState.INVALID, proposalsStore.get(proposal.id()).get().getEstado());
        assertEquals(98L, storedDiagram.get().getRevision());
        assertEquals(before, storedDiagram.get().getContenido());
    }

    @Test
    void semanticSafetyKeepsLegitimateClassDeletionAndOnlyItsStructuralDependents() {
        upstreamBody.set("""
                {"answer":"Propuesta", "operations":[{"type":"DELETE_ENTITY","entityName":"Producto"}]}
                """);
        var conv = as("gabriel@logicdraft.test", () -> conversationService.crearConversacion(
                3L, new CreateConversationRequest("Eliminar clase", "tab-g")));
        var result = as("gabriel@logicdraft.test", () -> conversationService.enviarMensaje(3L, conv.id(),
                new SendConversationMessageRequest("@LogicDraft elimina la clase Producto",
                        "valid-delete", false, null, null, List.of(), "tab-g")));
        var proposal = result.agentMessage().proposal();
        assertEquals(AgentProposalState.PENDING, proposal.estado());
        var accepted = as("gabriel@logicdraft.test", () -> conversationService.aceptarPropuesta(
                3L, conv.id(), proposal.id(), new ProposalDecisionRequest("tab-g", null)));
        assertEquals(AgentProposalState.ACCEPTED, accepted.estado());
        var context = DiagramContextMapper.fromDocument(storedDiagram.get().getContenido());
        assertEquals(List.of("Venta"), context.entities().stream().map(EntityDefinition::name).toList());
        assertTrue(context.associations().isEmpty());
        assertTrue(context.relationships().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> project3WithCompoundAttribute() {
        var document = project3RealUmlV2Document();
        var nodes = (List<Map<String, Object>>) document.get("nodes");
        var data = (Map<String, Object>) nodes.getFirst().get("data");
        var attributes = new java.util.ArrayList<>((List<Map<String, Object>>) data.get("attributes"));
        attributes.add(colAttr("attr-price-sale", "precio de venta", "DOUBLE"));
        data.put("attributes", attributes);
        return document;
    }

    private static Map<String, Object> project3RealUmlV2Document() {
        Map<String, Object> producto = classNode(
                "entity-8a0d0094-0c00-4409-8652-b338a6aa9cb1",
                "Producto",
                List.of(
                        pkAttr("attribute-56e35c75-69f6-4a76-b1fd-4db82ff76841", "ID", "INTEGER"),
                        colAttr("attribute-1fd30060-93bf-4b5a-bf84-5ff945a468f2", "Nombre", "VARCHAR")));

        Map<String, Object> venta = classNode(
                "entity-e580e42d-40e0-4a2c-a875-09d15c3f6b2a",
                "Venta",
                List.of(
                        pkAttr("attribute-b8511625-27cf-458a-8323-07c4940f921c", "ID", "INTEGER"),
                        colAttr("attribute-b28f9860-fb01-4347-ac61-42544f08a69d", "fecha", "DATE")));

        Map<String, Object> compraAssociationMeta = new LinkedHashMap<>();
        compraAssociationMeta.put("kind", "MANY_TO_MANY_ASSOCIATION");
        compraAssociationMeta.put("tableName", "venta_producto");
        compraAssociationMeta.put("uniquePair", true);
        compraAssociationMeta.put("sourceMultiplicity", "ONE_MANY");
        compraAssociationMeta.put("targetMultiplicity", "ONE_MANY");
        compraAssociationMeta.put("endpoints", List.of(
                Map.of(
                        "role", "SOURCE",
                        "entityId", "entity-e580e42d-40e0-4a2c-a875-09d15c3f6b2a",
                        "foreignKeyName", "venta_id",
                        "relationshipId", "relationship-f08a5dd3-c8f8-4365-a2a8-ed1c05c64035"),
                Map.of(
                        "role", "TARGET",
                        "entityId", "entity-8a0d0094-0c00-4409-8652-b338a6aa9cb1",
                        "foreignKeyName", "producto_id",
                        "relationshipId", "relationship-d807cd14-6292-4869-9b33-c73d766b36a3")));

        Map<String, Object> compraData = new LinkedHashMap<>();
        compraData.put("id", "entity-be1d5b09-3a3e-4709-938e-3345a9494a6e");
        compraData.put("kind", "ASSOCIATION_CLASS");
        compraData.put("name", "compra");
        compraData.put("methods", List.of());
        compraData.put("attributes", List.of(
                pkAttr("attribute-eb7a4776-508b-4a34-bc01-9cf34d7ed80c", "id", "INTEGER")));
        compraData.put("association", compraAssociationMeta);

        Map<String, Object> compra = new LinkedHashMap<>();
        compra.put("id", "entity-be1d5b09-3a3e-4709-938e-3345a9494a6e");
        compra.put("type", "entity");
        compra.put("position", Map.of("x", 326.0, "y", 282.5));
        compra.put("data", compraData);

        Map<String, Object> edge1 = umlEdge(
                "relationship-f08a5dd3-c8f8-4365-a2a8-ed1c05c64035",
                "entity-e580e42d-40e0-4a2c-a875-09d15c3f6b2a",
                "entity-be1d5b09-3a3e-4709-938e-3345a9494a6e",
                null,
                "ONE_ONE",
                "ONE_MANY");

        Map<String, Object> edge2 = umlEdge(
                "relationship-d807cd14-6292-4869-9b33-c73d766b36a3",
                "entity-8a0d0094-0c00-4409-8652-b338a6aa9cb1",
                "entity-be1d5b09-3a3e-4709-938e-3345a9494a6e",
                null,
                "ONE_ONE",
                "ONE_MANY");

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("version", 2);
        doc.put("nodes", List.of(producto, venta, compra));
        doc.put("edges", List.of(edge1, edge2));
        return doc;
    }

    private static Map<String, Object> classNode(String id, String name, List<Map<String, Object>> attributes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("kind", "CLASS");
        data.put("name", name);
        data.put("methods", List.of());
        data.put("attributes", attributes);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "entity");
        node.put("position", Map.of("x", 180.0, "y", 180.0));
        node.put("data", data);
        return node;
    }

    private static Map<String, Object> entityNode(String id, String name, List<Map<String, Object>> attributes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("kind", "ENTITY");
        data.put("name", name);
        data.put("methods", List.of());
        data.put("attributes", attributes);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "entity");
        node.put("position", Map.of("x", 180.0, "y", 180.0));
        node.put("data", data);
        return node;
    }

    private static Map<String, Object> pkAttr(String id, String name, String sqlType) {
        return new LinkedHashMap<>(Map.of(
                "id", id,
                "name", name,
                "type", sqlType,
                "primaryKey", true,
                "nullable", false));
    }

    private static Map<String, Object> colAttr(String id, String name, String sqlType) {
        return new LinkedHashMap<>(Map.of(
                "id", id,
                "name", name,
                "type", sqlType,
                "primaryKey", false,
                "nullable", true));
    }

    private static Map<String, Object> umlEdge(
            String id,
            String source,
            String target,
            String relationKind,
            String sourceCardinality,
            String targetCardinality) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceCardinality", sourceCardinality);
        data.put("targetCardinality", targetCardinality);
        if (relationKind != null) {
            data.put("relationKind", relationKind);
        }
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("type", "relationship");
        edge.put("source", source);
        edge.put("target", target);
        edge.put("data", data);
        return edge;
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
        copy.setOperacionesJson(src.getOperacionesJson());
        copy.setElementosAfectadosJson(src.getElementosAfectadosJson());
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
