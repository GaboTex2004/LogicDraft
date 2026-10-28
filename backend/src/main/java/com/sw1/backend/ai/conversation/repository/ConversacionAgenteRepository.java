package com.sw1.backend.ai.conversation.repository;

import com.sw1.backend.ai.conversation.model.ConversacionAgente;
import com.sw1.backend.ai.conversation.model.ConversationState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ConversacionAgenteRepository extends JpaRepository<ConversacionAgente, Long> {

    List<ConversacionAgente> findByProyectoIdOrderByFechaActualizacionDescIdDesc(Long proyectoId);

    List<ConversacionAgente> findByProyectoIdAndEstadoOrderByFechaActualizacionDescIdDesc(
            Long proyectoId,
            ConversationState estado);

    Optional<ConversacionAgente> findByIdAndProyectoId(Long id, Long proyectoId);

    long countByProyectoId(Long proyectoId);
}
