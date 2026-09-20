package com.sw1.backend.ai.controller;

import com.sw1.backend.ai.service.AudioTranscriptionService;

import java.util.Map;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/proyectos/{projectId}/ai/audio")
public class ProjectAudioController {

    private final AudioTranscriptionService service;

    public ProjectAudioController(AudioTranscriptionService service) {
        this.service = service;
    }

    @PostMapping(
            value = "/transcribe",
            consumes = "multipart/form-data"
    )
    public Map<String, String> transcribe(
            @PathVariable Long projectId,
            @RequestPart("audio") MultipartFile audio
    ) {
        return service.transcribe(projectId, audio);
    }
}