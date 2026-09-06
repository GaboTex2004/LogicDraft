package com.sw1.backend.diagrama.controller;
import com.sw1.backend.diagrama.dto.request.GuardarDiagramaRequest;
import com.sw1.backend.diagrama.dto.response.DiagramaResponse;
import com.sw1.backend.diagrama.service.DiagramaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/proyectos/{proyectoId}/diagrama")
public class DiagramaController {
    private final DiagramaService diagramaService;
    public DiagramaController(DiagramaService diagramaService) { this.diagramaService = diagramaService; }
    @GetMapping public ResponseEntity<DiagramaResponse> obtener(@PathVariable Long proyectoId) { return ResponseEntity.ok(diagramaService.obtenerPorProyecto(proyectoId)); }
    @PutMapping public ResponseEntity<DiagramaResponse> guardar(@PathVariable Long proyectoId, @Valid @RequestBody GuardarDiagramaRequest request) { return ResponseEntity.ok(diagramaService.guardar(proyectoId, request)); }
}
