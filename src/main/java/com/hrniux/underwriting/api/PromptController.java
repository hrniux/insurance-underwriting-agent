package com.hrniux.underwriting.api;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hrniux.underwriting.api.ApiDtos.PromptPreviewRequest;
import com.hrniux.underwriting.api.ApiDtos.PromptPreviewResponse;
import com.hrniux.underwriting.api.ApiDtos.PromptVersionRequest;
import com.hrniux.underwriting.prompt.PromptTemplateService;
import com.hrniux.underwriting.prompt.PromptTemplateVersion;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/prompts")
public class PromptController {

    private final PromptTemplateService prompts;

    public PromptController(PromptTemplateService prompts) {
        this.prompts = prompts;
    }

    @GetMapping
    public List<PromptTemplateVersion> list() {
        return prompts.listAll();
    }

    @GetMapping("/{code}")
    public List<PromptTemplateVersion> versions(@PathVariable String code) {
        return prompts.versions(code);
    }

    @PostMapping("/{code}/versions")
    public ResponseEntity<PromptTemplateVersion> createVersion(
            @PathVariable String code,
            @Valid @RequestBody PromptVersionRequest request) {
        PromptTemplateVersion template = prompts.createVersion(code, request.body(), request.requiredVariables());
        URI location = URI.create("/api/v1/prompts/%s/versions/%d".formatted(template.code(), template.version()));
        return ResponseEntity.created(location).body(template);
    }

    @PostMapping("/{code}/versions/{version}/activate")
    public PromptTemplateVersion activate(@PathVariable String code, @PathVariable int version) {
        return prompts.activate(code, version);
    }

    @PostMapping("/{code}/preview")
    public PromptPreviewResponse preview(
            @PathVariable String code,
            @Valid @RequestBody PromptPreviewRequest request) {
        return new PromptPreviewResponse(prompts.preview(code, request.variables()));
    }
}
