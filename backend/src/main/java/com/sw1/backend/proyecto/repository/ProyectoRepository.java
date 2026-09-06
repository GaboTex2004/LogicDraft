package com.sw1.backend.proyecto.repository;

import com.sw1.backend.proyecto.model.Proyecto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProyectoRepository extends JpaRepository<Proyecto, Long> {

    List<Proyecto> findByWorkspaceId(Long workspaceId);
}
