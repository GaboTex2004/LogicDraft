package com.sw1.backend.proyecto.controller;

import com.sw1.backend.proyecto.dto.request.ActualizarProyectoRequest;
import com.sw1.backend.proyecto.dto.request.CrearProyectoRequest;
import com.sw1.backend.proyecto.dto.response.ProyectoResponse;
import com.sw1.backend.proyecto.service.ProyectoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/proyectos")
public class ProyectoController {

    private final ProyectoService proyectoService;

    public ProyectoController(ProyectoService proyectoService) {
        this.proyectoService = proyectoService;
    }

    @PostMapping
    public ResponseEntity<ProyectoResponse> crear(
            @Valid @RequestBody CrearProyectoRequest request) {
        ProyectoResponse proyectoCreado = proyectoService.crear(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(proyectoCreado);
    }

    @GetMapping
    public List<ProyectoResponse> listarPorWorkspace(
            @RequestParam Long workspaceId) {
        return proyectoService.listarPorWorkspace(workspaceId);
    }

    @GetMapping("/{id}")
    public ProyectoResponse buscarPorId(@PathVariable Long id) {
        return proyectoService.buscarPorId(id);
    }

    @PutMapping("/{id}")
    public ProyectoResponse actualizar(
            @PathVariable Long id,
            @Valid @RequestBody ActualizarProyectoRequest request) {
        return proyectoService.actualizar(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable Long id) {
        proyectoService.eliminar(id);
        return ResponseEntity.noContent().build();
    }
}
