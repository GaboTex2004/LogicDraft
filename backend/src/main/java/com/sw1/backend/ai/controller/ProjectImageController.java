package com.sw1.backend.ai.controller;

import com.sw1.backend.ai.image.ImageProposalService;
import com.sw1.backend.diagrama.dto.response.DiagramaResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import com.sw1.backend.ai.service.ImageInterpretationService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/proyectos/{projectId}/ai/image")
public class ProjectImageController {

    private final ImageInterpretationService service;
    private final ImageProposalService proposals;

    public ProjectImageController(ImageInterpretationService service, ImageProposalService proposals) {
        this.service = service;
        this.proposals = proposals;
    }

    @PostMapping(
            value = "/interpret",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ImageProposalService.Proposal interpret(
            @PathVariable Long projectId,
            @RequestPart("image") MultipartFile image,
            @RequestParam(value = "prompt", required = false, defaultValue = "") String prompt
    ) {
        return service.interpret(projectId, image, prompt);
    }

    public record AcceptImageRequest(@NotBlank @Pattern(regexp = "[a-f0-9-]{36}") String proposalId,
                                    @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,100}") String clientId) {}

    @PostMapping("/accept")
    public DiagramaResponse accept(@PathVariable Long projectId, @Valid @RequestBody AcceptImageRequest request) {
        return proposals.accept(projectId, request.proposalId(), request.clientId());
    }
}
