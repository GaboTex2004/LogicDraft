package com.sw1.backend.diagrama.service;

import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.collaboration.service.DiagramCommitPublisher;
import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.diagrama.dto.request.GuardarDiagramaRequest;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.diagrama.validation.DiagramValidationException;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.tenant.model.MiembroTenant;
import com.sw1.backend.tenant.model.Tenant;
import com.sw1.backend.tenant.repository.MiembroTenantRepository;
import com.sw1.backend.tenant.service.TenantAccessService;
import com.sw1.backend.usuario.model.Usuario;
import com.sw1.backend.usuario.repository.UsuarioRepository;
import com.sw1.backend.workspace.model.*;
import com.sw1.backend.workspace.repository.*;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real service/access/validation, a CAS repository simulating @Version; never opens PostgreSQL. */
class DiagramConcurrentSaveTest {
    DiagramaRepository diagrams;
    DiagramCommitPublisher events;
    DiagramaService service;
    Proyecto project;
    AtomicReference<Diagrama> stored;

    @BeforeEach
    void setup() {
        var users = mock(UsuarioRepository.class);
        var tenants = mock(MiembroTenantRepository.class);
        var members = mock(MiembroWorkspaceRepository.class);
        var workspaces = mock(WorkspaceRepository.class);
        var projects = mock(ProyectoRepository.class);
        diagrams = mock(DiagramaRepository.class);
        events = mock(DiagramCommitPublisher.class);
        var tenant = new Tenant(); tenant.setId(100L);
        var workspace = new Workspace(); workspace.setId(20L); workspace.setTenant(tenant);
        project = new Proyecto(); project.setId(10L); project.setWorkspace(workspace);
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        when(workspaces.findById(20L)).thenReturn(Optional.of(workspace));
        var roles = List.of(RolWorkspace.OWNER, RolWorkspace.EDITOR, RolWorkspace.VIEWER);
        for (int index = 0; index < 4; index++) {
            long id = index + 1L;
            var user = new Usuario(); user.setId(id); user.setEmail("user" + id);
            when(users.findByEmail("user" + id)).thenReturn(Optional.of(user));
            if (index < 3) {
                when(tenants.findByUsuarioIdAndTenantId(id, 100L)).thenReturn(Optional.of(new MiembroTenant()));
                var member = new MiembroWorkspace(); member.setRol(roles.get(index));
                when(members.findByUsuarioIdAndWorkspaceId(id, 20L)).thenReturn(Optional.of(member));
            }
        }
        var current = new UsuarioActualService(users);
        var access = new WorkspaceAccessService(current, new TenantAccessService(current, tenants), workspaces, members);
        service = new DiagramaService(diagrams, projects, access, events);
        stored = new AtomicReference<>(record(0L, "Inicial"));
        when(diagrams.findByProyectoId(10L)).thenAnswer(call -> Optional.of(copy(stored.get())));
        when(diagrams.saveAndFlush(any())).thenAnswer(call -> {
            Diagrama candidate = call.getArgument(0);
            Diagrama old = stored.get();
            if (!Objects.equals(old.getRevision(), candidate.getRevision()))
                throw new ObjectOptimisticLockingFailureException(Diagrama.class, 30L);
            Diagrama next = copy(candidate);
            next.setRevision(old.getRevision() + 1);
            if (!stored.compareAndSet(old, next))
                throw new ObjectOptimisticLockingFailureException(Diagrama.class, 30L);
            return copy(next);
        });
    }

    @Test
    void ownerAndEditorRacingHaveOneWinnerAndRetryCannotApplyTwice() throws Exception {
        var barrier = new CyclicBarrier(2);
        var reads = new AtomicInteger();
        when(diagrams.findByProyectoId(10L)).thenAnswer(call -> {
            var snapshot = copy(stored.get());
            if (reads.incrementAndGet() <= 2) barrier.await(5, TimeUnit.SECONDS);
            return Optional.of(snapshot);
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var owner = pool.submit(() -> saveAs("user1", "Manual"));
            var editor = pool.submit(() -> saveAs("user2", "Propuesta aceptada localmente"));
            assertNotEquals(owner.get(10, TimeUnit.SECONDS), editor.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1L, stored.get().getRevision());
        var content = stored.get().getContenido();
        assertFalse(saveAs("user1", "Reintento obsoleto"));
        assertEquals(content, stored.get().getContenido());
        verify(events, times(1)).schedule(any(), any());
        as("user3", () -> assertEquals(content, service.obtenerPorProyecto(10L).contenido()));
    }

    @Test
    void viewerReadsButCannotWriteAndForeignTenantCannotReadOrWrite() {
        as("user3", () -> {
            assertEquals(0L, service.obtenerPorProyecto(10L).revision());
            assertThrows(AccesoDenegadoException.class, () -> service.guardar(10L, request("Cambio")));
        });
        as("user4", () -> {
            assertThrows(AccesoDenegadoException.class, () -> service.obtenerPorProyecto(10L));
            assertThrows(AccesoDenegadoException.class, () -> service.guardar(10L, request("Cambio")));
        });
        verify(diagrams, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void invalidBatchIsRejectedAtomicallyBeforePersistenceOrPublication() {
        var request = request("Valida");
        request.setEdges(List.of(Map.of("id", "broken", "source", "class", "target", "missing")));
        as("user1", () -> assertThrows(DiagramValidationException.class, () -> service.guardar(10L, request)));
        assertEquals(0L, stored.get().getRevision());
        verify(diagrams, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    private boolean saveAs(String email, String name) {
        var success = new AtomicBoolean();
        as(email, () -> {
            try { service.guardar(10L, request(name)); success.set(true); }
            catch (DiagramConflictException expected) { /* losing writer retains its draft */ }
        });
        return success.get();
    }

    private void as(String email, Runnable operation) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));
        try { operation.run(); } finally { SecurityContextHolder.clearContext(); }
    }

    private GuardarDiagramaRequest request(String name) {
        var request = new GuardarDiagramaRequest();
        request.setVersion(2); request.setBaseRevision(0L); request.setClientId("test-client");
        request.setNodes(List.of(Map.of("id", "class", "type", "entity", "position", Map.of("x", 0, "y", 0),
                "data", Map.of("id", "class", "kind", "CLASS", "name", name, "attributes", List.of(), "methods", List.of()))));
        request.setEdges(List.of());
        return request;
    }

    private Diagrama record(long revision, String name) {
        var request = request(name);
        var diagram = new Diagrama(); diagram.setId(30L); diagram.setProyecto(project);
        diagram.setVersion(2); diagram.setRevision(revision);
        diagram.setContenido(Map.of("version", 2, "nodes", request.getNodes(), "edges", request.getEdges()));
        return diagram;
    }

    private Diagrama copy(Diagrama value) {
        var diagram = new Diagrama(); diagram.setId(value.getId()); diagram.setProyecto(value.getProyecto());
        diagram.setVersion(value.getVersion()); diagram.setRevision(value.getRevision());
        diagram.setContenido(value.getContenido());
        return diagram;
    }
}
