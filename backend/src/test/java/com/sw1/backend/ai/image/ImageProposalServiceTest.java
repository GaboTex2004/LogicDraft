package com.sw1.backend.ai.image;

import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.collaboration.service.DiagramCommitPublisher;
import com.sw1.backend.collaboration.service.ProjectElementLockService;
import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.diagrama.service.*;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.usuario.model.Usuario;
import com.sw1.backend.workspace.model.*;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.*;
import static com.sw1.backend.ai.image.ImageDiagramReconcilerTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real proposal + save services under Spring transaction advice, with isolated persistence doubles. */
class ImageProposalServiceTest {
    private final DiagramaRepository diagrams = mock(DiagramaRepository.class);
    private final ProyectoRepository projects = mock(ProyectoRepository.class);
    private final WorkspaceAccessService access = mock(WorkspaceAccessService.class);
    private final UsuarioActualService users = mock(UsuarioActualService.class);
    private final ProjectElementLockService locks = mock(ProjectElementLockService.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final TestTransactions transactions = new TestTransactions();
    private ImageProposalService service;
    private DiagramaService persistence;
    private Proyecto project;
    private Usuario user;
    private Map<String, Object> committed;
    private long revision;
    private boolean failSave;

    @BeforeEach void setup() {
        var workspace = new Workspace(); workspace.setId(20L);
        project = new Proyecto(); project.setId(10L); project.setWorkspace(workspace);
        user = new Usuario(); user.setId(7L); user.setNombre("Editor");
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        when(users.obtenerUsuarioActual()).thenReturn(user);
        committed = base(); revision = 4;
        when(diagrams.findByProyectoId(10L)).thenAnswer(call -> Optional.of(record(committed, revision)));
        when(diagrams.saveAndFlush(any())).thenAnswer(call -> {
            if (failSave) throw new IllegalStateException("simulated storage failure");
            Diagrama saved = call.getArgument(0);
            saved.setRevision(revision + 1);
            var candidate = ImageDiagramReconciler.copy(saved.getContenido());
            long next = saved.getRevision();
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { committed = candidate; revision = next; }
            });
            return saved;
        });
        var events = mock(ApplicationEventPublisher.class);
        var publisher = new DiagramCommitPublisher(events, users, messaging);
        doAnswer(call -> {
            var event = (DiagramCommitPublisher.CommittedDiagram) call.getArgument(0);
            // Same AFTER_COMMIT phase as the existing production listener; no live broker.
            verifyNoInteractions(messaging);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publisher.afterCommit(event); }
            });
            return null;
        }).when(events).publishEvent(any(Object.class));
        persistence = new DiagramaService(diagrams, projects, access, publisher, new DiagramRevisionHistory(), locks, users);
        var target = new ImageProposalService(projects, diagrams, access, users, persistence);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (ImageProposalService) proxy.getProxy();
    }
    private Diagrama record(Map<String, Object> content, long rev) {
        var d = new Diagrama(); d.setId(30L); d.setProyecto(project); d.setVersion((Integer) content.get("version"));
        d.setRevision(rev); d.setContenido(ImageDiagramReconciler.copy(content)); return d;
    }
    private ImageProposalService.Proposal proposal() {
        return service.prepare(service.load(10L), recognition(classifier("Cliente", "CLASS", List.of(m("name", "correo", "type", "String")))));
    }

    @Test void prepareAndCancelDoNotWrite() {
        var before = ImageDiagramReconciler.copy(committed);
        var proposal = proposal(); assertTrue(proposal.canApply()); assertEquals(4L, proposal.baseRevision());
        assertEquals(before, committed); verify(diagrams, never()).saveAndFlush(any()); verifyNoInteractions(messaging);
    }
    @Test void savesAndRecoversThroughRealSaveServiceAndPublishesAfterCommit() {
        var before = ImageDiagramReconciler.copy(committed);
        var proposal = proposal(); var saved = service.accept(10L, proposal.proposalId(), "browser");
        assertEquals(5L, saved.revision()); assertEquals(committed, persistence.obtenerPorProyecto(10L).contenido());
        assertEquals("c", firstData(committed).get("id")); assertEquals(firstData(before).get("methods"), firstData(committed).get("methods"));
        verify(locks).verifyCanModify(eq(10L), argThat(ids -> ids.contains("c")), eq(7L), eq("browser"));
        verify(messaging).convertAndSend(eq("/topic/proyectos/10"), any(Object.class));
        assertThrows(DiagramConflictException.class, () -> service.accept(10L, proposal.proposalId(), "browser"));
        verify(diagrams, times(1)).saveAndFlush(any());
    }
    @Test void staleRevisionCannotBeMergedOrSaved() {
        var proposal = proposal(); revision++;
        assertThrows(DiagramConflictException.class, () -> service.accept(10L, proposal.proposalId(), "browser"));
        verify(diagrams, never()).saveAndFlush(any()); verifyNoInteractions(messaging);
    }
    @Test void strictSaveAlsoRejectsRevisionChangeBetweenChecks() {
        var proposal = proposal();
        when(diagrams.findByProyectoId(10L)).thenReturn(Optional.of(record(committed, 4)), Optional.of(record(committed, 5)));
        assertThrows(DiagramConflictException.class, () -> service.accept(10L, proposal.proposalId(), "browser"));
        verify(diagrams, never()).saveAndFlush(any());
    }
    @Test void viewerCannotConfirmEvenWithValidToken() {
        var proposal = proposal();
        doThrow(new AccesoDenegadoException("VIEWER")).when(access).verificarRol(20L, RolWorkspace.OWNER, RolWorkspace.EDITOR);
        assertThrows(AccesoDenegadoException.class, () -> service.accept(10L, proposal.proposalId(), "browser"));
        verify(diagrams, never()).saveAndFlush(any());
    }
    @Test void proposalBelongsToItsAuthor() {
        var proposal = proposal(); user.setId(8L);
        assertThrows(AiServiceException.class, () -> service.accept(10L, proposal.proposalId(), "browser"));
        verify(diagrams, never()).saveAndFlush(any());
    }
    @Test void lockRejectionDoesNotPersist() {
        var proposal = proposal();
        doThrow(new IllegalStateException("locked by another collaborator")).when(locks).verifyCanModify(eq(10L), any(), eq(7L), eq("browser"));
        assertThrows(IllegalStateException.class, () -> service.accept(10L, proposal.proposalId(), "browser"));
        verify(diagrams, never()).saveAndFlush(any()); verifyNoInteractions(messaging);
    }
    @Test void failedSaveLeavesNoPartialChangesAndCanRetry() {
        var before = ImageDiagramReconciler.copy(committed); var proposal = proposal(); failSave = true;
        assertThrows(IllegalStateException.class, () -> service.accept(10L, proposal.proposalId(), "browser"));
        assertEquals(before, committed); assertEquals(4, revision); verifyNoInteractions(messaging);
        failSave = false;
        assertEquals(5L, service.accept(10L, proposal.proposalId(), "browser").revision());
    }
    @Test void transactionCommitFailureDoesNotCommitDataOrPublish() {
        var before = ImageDiagramReconciler.copy(committed); var proposal = proposal(); transactions.failCommit = true;
        assertThrows(TransactionSystemException.class, () -> service.accept(10L, proposal.proposalId(), "browser"));
        assertEquals(before, committed); assertEquals(4, revision); verifyNoInteractions(messaging);
    }
    @Test void conflictingProposalCannotBeAccepted() {
        var proposal = service.prepare(service.load(10L), recognition(classifier("Cliente", "INTERFACE", List.of())));
        assertFalse(proposal.canApply());
        assertThrows(AiServiceException.class, () -> service.accept(10L, proposal.proposalId(), "browser"));
        verify(diagrams, never()).saveAndFlush(any());
    }

    private static final class TestTransactions extends AbstractPlatformTransactionManager {
        boolean failCommit;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) throw new TransactionSystemException("simulated commit failure");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
