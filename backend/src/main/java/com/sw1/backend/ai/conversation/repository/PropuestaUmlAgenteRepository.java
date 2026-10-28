package com.sw1.backend.ai.conversation.repository;

import com.sw1.backend.ai.conversation.model.PropuestaUmlAgente;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PropuestaUmlAgenteRepository extends JpaRepository<PropuestaUmlAgente, Long> {

    Optional<PropuestaUmlAgente> findByIdAndConversacionIdAndProyectoId(
            Long id,
            Long conversacionId,
            Long proyectoId);

    Optional<PropuestaUmlAgente> findByMensajeId(Long mensajeId);

    List<PropuestaUmlAgente> findByMensajeIdIn(Collection<Long> mensajeIds);
}
