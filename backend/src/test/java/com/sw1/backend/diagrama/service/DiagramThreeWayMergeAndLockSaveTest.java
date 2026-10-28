package com.sw1.backend.diagrama.service;

import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.collaboration.security.CollaborationIdentity;
import com.sw1.backend.collaboration.service.DiagramCommitPublisher;
import com.sw1.backend.collaboration.service.ElementLockedException;
import com.sw1.backend.collaboration.service.ProjectElementLockService;
import com.sw1.backend.diagrama.controller.DiagramaController;
import com.sw1.backend.diagrama.dto.request.GuardarDiagramaRequest;
import com.sw1.backend.diagrama.dto.response.DiagramaResponse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DiagramThreeWayMergeAndLockSaveTest {
    private static final Long PROJECT_ID = 10L;

    private DiagramaRepository diagrams;
    private DiagramCommitPublisher commitPublisher;
    private ProjectElementLockService lockService;
    private DiagramaService service;
    private DiagramaController controller;
    private Proyecto project;
    private AtomicReference<Diagrama> stored;

    @BeforeEach
    void setUp() {
        var users = mock(UsuarioRepository.class);
        var tenants = mock(MiembroTenantRepository.class);
        var members = mock(MiembroWorkspaceRepository.class);
        var workspaces = mock(WorkspaceRepository.class);
        var projects = mock(ProyectoRepository.class);
        diagrams = mock(DiagramaRepository.class);
        commitPublisher = mock(DiagramCommitPublisher.class);
        lockService = new ProjectElementLockService(
                mock(SimpMessagingTemplate.class), Clock.systemUTC(), Duration.ofSeconds(30), false);

        var tenant = new Tenant();
        tenant.setId(100L);
        var workspace = new Workspace();
        workspace.setId(20L);
        workspace.setTenant(tenant);
        project = new Proyecto();
        project.setId(PROJECT_ID);
        project.setWorkspace(workspace);

        when(projects.findById(PROJECT_ID)).thenReturn(Optional.of(project));
        when(workspaces.findById(20L)).thenReturn(Optional.of(workspace));

        setupUser(users, tenants, members, 1L, "gabriel@example.com", "Gabriel", RolWorkspace.OWNER);
        setupUser(users, tenants, members, 2L, "carlos@example.com", "Carlos", RolWorkspace.EDITOR);

        var currentUser = new UsuarioActualService(users);
        var access = new WorkspaceAccessService(
                currentUser, new TenantAccessService(currentUser, tenants), workspaces, members);
        service = new DiagramaService(
                diagrams, projects, access, commitPublisher, new DiagramRevisionHistory(), lockService, currentUser);
        controller = new DiagramaController(service);

        Map<String, Object> initialContent = Map.of(
                "version", 2,
                "nodes", List.of(
                        classNode("ventas", "Ventas", 0, 0, List.of(pkAttr("id_v"))),
                        classNode("producto", "Producto", 300, 0, List.of(pkAttr("id_p")))),
                "edges", List.of());
        stored = new AtomicReference<>(diagramRecord(5L, initialContent));

        when(diagrams.findByProyectoId(PROJECT_ID)).thenAnswer(call -> Optional.of(copy(stored.get())));
        when(diagrams.saveAndFlush(any())).thenAnswer(call -> {
            Diagrama candidate = call.getArgument(0);
            Diagrama old = stored.get();
            if (!Objects.equals(old.getRevision(), candidate.getRevision())) {
                throw new ObjectOptimisticLockingFailureException(Diagrama.class, 30L);
            }
            Diagrama next = copy(candidate);
            next.setRevision(old.getRevision() + 1);
            if (!stored.compareAndSet(old, next)) {
                throw new ObjectOptimisticLockingFailureException(Diagrama.class, 30L);
            }
            return copy(next);
        });
    }

    @Test
    void twoUsersEditingDifferentClassesOnSameBaseRevisionBothPersistWithoutConflict() {
        // Ambos leen inicialmente revision = 5
        as("gabriel@example.com", () -> assertEquals(5L, controller.obtener(PROJECT_ID).getBody().revision()));
        as("carlos@example.com", () -> assertEquals(5L, controller.obtener(PROJECT_ID).getBody().revision()));

        // Gabriel modifica Ventas (mueve + agrega atributo) sobre baseRevision = 5 -> revision 6
        GuardarDiagramaRequest gabrielSave = buildRequest(5L, "client-gabriel", List.of(
                classNode("ventas", "Ventas", 120, 80, List.of(pkAttr("id_v"), attr("total", "DECIMAL"))),
                classNode("producto", "Producto", 300, 0, List.of(pkAttr("id_p")))), List.of());
        DiagramaResponse r6 = callAs("gabriel@example.com", () -> controller.guardar(PROJECT_ID, gabrielSave).getBody());
        assertEquals(6L, r6.revision());

        // Carlos modifica Producto + crea nueva clase Categoria sobre baseRevision = 5 (obsoleta respecto a 6)
        GuardarDiagramaRequest carlosSave = buildRequest(5L, "client-carlos", List.of(
                classNode("ventas", "Ventas", 0, 0, List.of(pkAttr("id_v"))),
                classNode("producto", "Producto", 420, 140, List.of(pkAttr("id_p"), attr("precio", "DECIMAL"))),
                classNode("categoria", "Categoria", 650, 140, List.of(pkAttr("id_c")))),
                List.of(relationship("rel-prod-cat", "producto", "categoria")));
        DiagramaResponse r7 = callAs("carlos@example.com", () -> controller.guardar(PROJECT_ID, carlosSave).getBody());
        assertEquals(7L, r7.revision());

        // Verifica que AMBAS modificaciones coexisten íntegras en revision 7
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> finalNodes = (List<Map<String, Object>>) r7.contenido().get("nodes");
        assertEquals(3, finalNodes.size());
        Map<String, Object> ventas = findById(finalNodes, "ventas");
        Map<String, Object> producto = findById(finalNodes, "producto");
        Map<String, Object> categoria = findById(finalNodes, "categoria");
        assertNotNull(ventas);
        assertNotNull(producto);
        assertNotNull(categoria);
        assertEquals(120, ((Number) ((Map<?, ?>) ventas.get("position")).get("x")).intValue());
        assertEquals(2, ((List<?>) ((Map<?, ?>) ventas.get("data")).get("attributes")).size());
        assertEquals(420, ((Number) ((Map<?, ?>) producto.get("position")).get("x")).intValue());
        assertEquals(2, ((List<?>) ((Map<?, ?>) producto.get("data")).get("attributes")).size());
        assertEquals(1, ((List<?>) r7.contenido().get("edges")).size());
    }

    @Test
    void simultaneousCasCollisionOnDisjointClassesMergesViaControllerRetryWithout409() throws Exception {
        var barrier = new CyclicBarrier(2);
        var reads = new AtomicInteger();
        when(diagrams.findByProyectoId(PROJECT_ID)).thenAnswer(call -> {
            var snapshot = copy(stored.get());
            if (reads.incrementAndGet() <= 2) {
                barrier.await(5, TimeUnit.SECONDS);
            }
            return Optional.of(snapshot);
        });

        GuardarDiagramaRequest gabrielSave = buildRequest(5L, "client-gabriel", List.of(
                classNode("ventas", "VentasConfirmadas", 40, 50, List.of(pkAttr("id_v"))),
                classNode("producto", "Producto", 300, 0, List.of(pkAttr("id_p")))), List.of());

        GuardarDiagramaRequest carlosSave = buildRequest(5L, "client-carlos", List.of(
                classNode("ventas", "Ventas", 0, 0, List.of(pkAttr("id_v"))),
                classNode("producto", "ProductoActualizado", 350, 90, List.of(pkAttr("id_p")))), List.of());

        try (var pool = Executors.newFixedThreadPool(2)) {
            var f1 = pool.submit(() -> callAs("gabriel@example.com", () -> controller.guardar(PROJECT_ID, gabrielSave).getBody()));
            var f2 = pool.submit(() -> callAs("carlos@example.com", () -> controller.guardar(PROJECT_ID, carlosSave).getBody()));
            assertNotNull(f1.get(10, TimeUnit.SECONDS));
            assertNotNull(f2.get(10, TimeUnit.SECONDS));
        }

        assertEquals(7L, stored.get().getRevision());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) stored.get().getContenido().get("nodes");
        assertEquals("VentasConfirmadas", ((Map<?, ?>) findById(nodes, "ventas").get("data")).get("name"));
        assertEquals("ProductoActualizado", ((Map<?, ?>) findById(nodes, "producto").get("data")).get("name"));
    }

    @Test
    void trueConflictOnSameElementOrBrokenCrossElementReferenceStillRejectsWith409() {
        as("gabriel@example.com", () -> controller.obtener(PROJECT_ID));

        // Gabriel elimina Producto en revision 6
        GuardarDiagramaRequest gabrielDeletesProducto = buildRequest(5L, "client-gabriel", List.of(
                classNode("ventas", "Ventas", 0, 0, List.of(pkAttr("id_v")))), List.of());
        as("gabriel@example.com", () -> controller.guardar(PROJECT_ID, gabrielDeletesProducto));

        // Carlos (sobre baseRevision = 5) intenta conectar una relación hacia Producto (ya eliminado) -> 409 real
        GuardarDiagramaRequest carlosConnectsDeleted = buildRequest(5L, "client-carlos", List.of(
                classNode("ventas", "Ventas", 0, 0, List.of(pkAttr("id_v"))),
                classNode("producto", "Producto", 300, 0, List.of(pkAttr("id_p")))),
                List.of(relationship("rel-1", "ventas", "producto")));
        assertThrows(DiagramConflictException.class, () ->
                as("carlos@example.com", () -> controller.guardar(PROJECT_ID, carlosConnectsDeleted)));
        assertEquals(6L, stored.get().getRevision());
    }

    @Test
    void backendRejectsSaveTouchingElementLockedByAnotherUserWhileAllowingSaveOnUnlockedElement() {
        var gabrielIdentity = new CollaborationIdentity(1L, "gabriel@example.com", "Gabriel");
        lockService.acquire(PROJECT_ID, gabrielIdentity, "client-gabriel", "sess-g", List.of("ventas"));

        // Carlos intenta guardar una modificación sobre "ventas" (bloqueado por Gabriel) -> rechazado en servidor
        GuardarDiagramaRequest carlosTouchesLocked = buildRequest(5L, "client-carlos", List.of(
                classNode("ventas", "VentasHackeadas", 0, 0, List.of(pkAttr("id_v"))),
                classNode("producto", "Producto", 300, 0, List.of(pkAttr("id_p")))), List.of());
        ElementLockedException locked = assertThrows(ElementLockedException.class, () ->
                as("carlos@example.com", () -> controller.guardar(PROJECT_ID, carlosTouchesLocked)));
        assertEquals("Está siendo usado por Gabriel", locked.getMessage());
        assertEquals(5L, stored.get().getRevision());

        // Carlos guarda una modificación únicamente sobre "producto" (libre) mientras Gabriel mantiene bloqueado "ventas" -> permitido
        GuardarDiagramaRequest carlosTouchesFree = buildRequest(5L, "client-carlos", List.of(
                classNode("ventas", "Ventas", 0, 0, List.of(pkAttr("id_v"))),
                classNode("producto", "ProductoLibre", 360, 40, List.of(pkAttr("id_p")))), List.of());
        DiagramaResponse saved = callAs("carlos@example.com", () -> controller.guardar(PROJECT_ID, carlosTouchesFree).getBody());
        assertEquals(6L, saved.revision());
    }

    private static void setupUser(
            UsuarioRepository users,
            MiembroTenantRepository tenants,
            MiembroWorkspaceRepository members,
            Long id,
            String email,
            String name,
            RolWorkspace role) {
        var user = new Usuario();
        user.setId(id);
        user.setEmail(email);
        user.setNombre(name);
        when(users.findByEmail(email)).thenReturn(Optional.of(user));
        when(tenants.findByUsuarioIdAndTenantId(id, 100L)).thenReturn(Optional.of(new MiembroTenant()));
        var member = new MiembroWorkspace();
        member.setRol(role);
        when(members.findByUsuarioIdAndWorkspaceId(id, 20L)).thenReturn(Optional.of(member));
    }

    private static void as(String email, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static <T> T callAs(String email, java.util.concurrent.Callable<T> action) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
        try {
            return action.call();
        } catch (RuntimeException runtime) {
            throw runtime;
        } catch (Exception checked) {
            throw new RuntimeException(checked);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static GuardarDiagramaRequest buildRequest(
            Long baseRevision,
            String clientId,
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges) {
        var req = new GuardarDiagramaRequest();
        req.setVersion(2);
        req.setBaseRevision(baseRevision);
        req.setClientId(clientId);
        req.setNodes(nodes);
        req.setEdges(edges);
        return req;
    }

    private static Map<String, Object> classNode(
            String id,
            String name,
            int x,
            int y,
            List<Map<String, Object>> attributes) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("kind", "CLASS");
        data.put("name", name);
        data.put("attributes", attributes);
        data.put("methods", List.of());

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "entity");
        node.put("position", Map.of("x", x, "y", y));
        node.put("data", data);
        return node;
    }

    private static Map<String, Object> pkAttr(String id) {
        return Map.of("id", id, "name", "id", "type", "BIGINT", "primaryKey", true);
    }

    private static Map<String, Object> attr(String name, String type) {
        return Map.of("id", "attr-" + name, "name", name, "type", type, "primaryKey", false);
    }

    private static Map<String, Object> relationship(String id, String source, String target) {
        return Map.of(
                "id", id,
                "source", source,
                "target", target,
                "type", "relationship",
                "data", Map.of(
                        "sourceCardinality", "ONE_ONE",
                        "targetCardinality", "ZERO_MANY",
                        "relationKind", "ASSOCIATION"));
    }

    private static Map<String, Object> findById(List<Map<String, Object>> items, String id) {
        return items.stream()
                .filter(item -> id.equals(item.get("id")))
                .findFirst()
                .orElse(null);
    }

    private Diagrama diagramRecord(Long revision, Map<String, Object> content) {
        var diagram = new Diagrama();
        diagram.setId(30L);
        diagram.setProyecto(project);
        diagram.setVersion((Integer) content.get("version"));
        diagram.setRevision(revision);
        diagram.setContenido(DiagramThreeWayMerge.deepCopyMap(content));
        return diagram;
    }

    private static Diagrama copy(Diagrama source) {
        var diagram = new Diagrama();
        diagram.setId(source.getId());
        diagram.setProyecto(source.getProyecto());
        diagram.setVersion(source.getVersion());
        diagram.setRevision(source.getRevision());
        diagram.setContenido(DiagramThreeWayMerge.deepCopyMap(source.getContenido()));
        return diagram;
    }
}
