package com.sw1.backend.ai.conversation.repository;

import com.sw1.backend.ai.conversation.model.MensajeConversacionAgente;
import com.sw1.backend.ai.conversation.model.MessageAuthorType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MensajeConversacionAgenteRepository extends JpaRepository<MensajeConversacionAgente, Long> {

    Optional<MensajeConversacionAgente> findByConversacionIdAndClientMessageId(
            Long conversacionId,
            String clientMessageId);

    Optional<MensajeConversacionAgente> findFirstByConversacionIdAndMensajeOrigenIdAndAutorTipoOrderByIdDesc(
            Long conversacionId,
            Long mensajeOrigenId,
            MessageAuthorType autorTipo);

    List<MensajeConversacionAgente> findByConversacionIdOrderByIdDesc(Long conversacionId, Pageable pageable);

    List<MensajeConversacionAgente> findByConversacionIdAndIdLessThanOrderByIdDesc(
            Long conversacionId,
            Long beforeId,
            Pageable pageable);
}
