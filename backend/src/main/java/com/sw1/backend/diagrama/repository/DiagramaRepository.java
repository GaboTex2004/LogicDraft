package com.sw1.backend.diagrama.repository;
import com.sw1.backend.diagrama.model.Diagrama;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface DiagramaRepository extends JpaRepository<Diagrama, Long> {
    Optional<Diagrama> findByProyectoId(Long proyectoId);
}
