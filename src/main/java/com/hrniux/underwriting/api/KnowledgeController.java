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
import org.springframework.web.util.UriComponentsBuilder;

import com.hrniux.underwriting.api.ApiDtos.KnowledgeDocumentRequest;
import com.hrniux.underwriting.api.ApiDtos.KnowledgeEvaluationRequest;
import com.hrniux.underwriting.api.ApiDtos.KnowledgeSearchRequest;
import com.hrniux.underwriting.api.ApiDtos.KnowledgeVersionRequest;
import com.hrniux.underwriting.rag.KnowledgeDocument;
import com.hrniux.underwriting.rag.KnowledgeDocumentVersion;
import com.hrniux.underwriting.rag.KnowledgePublicationResult;
import com.hrniux.underwriting.rag.KnowledgeService;
import com.hrniux.underwriting.rag.RetrievalEvaluationReport;
import com.hrniux.underwriting.rag.RetrievalEvaluationService;
import com.hrniux.underwriting.rag.RetrievalHit;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledge;
    private final RetrievalEvaluationService evaluations;

    public KnowledgeController(KnowledgeService knowledge, RetrievalEvaluationService evaluations) {
        this.knowledge = knowledge;
        this.evaluations = evaluations;
    }

    @PostMapping("/documents")
    public ResponseEntity<KnowledgeDocumentVersion> createDraft(
            @Valid @RequestBody KnowledgeDocumentRequest request) {
        KnowledgeDocumentVersion draft = knowledge.createDraft(request.toDomain());
        return ResponseEntity.created(versionLocation(draft)).body(draft);
    }

    private URI versionLocation(KnowledgeDocumentVersion version) {
        return UriComponentsBuilder.fromPath("/api/v1/knowledge/documents")
                .pathSegment("{documentId}", "versions", "{version}")
                .encode()
                .buildAndExpand(version.documentId(), version.version())
                .toUri();
    }

    @GetMapping("/documents")
    public List<KnowledgeDocument> list() {
        return knowledge.list();
    }

    @GetMapping("/documents/{documentId}")
    public KnowledgeDocument get(@PathVariable String documentId) {
        return knowledge.get(documentId);
    }

    @GetMapping("/documents/{documentId}/versions")
    public List<KnowledgeDocumentVersion> versions(@PathVariable String documentId) {
        return knowledge.versions(documentId);
    }

    @GetMapping("/documents/{documentId}/versions/{version}")
    public KnowledgeDocumentVersion getVersion(
            @PathVariable String documentId,
            @PathVariable int version) {
        return knowledge.getVersion(documentId, version);
    }

    @PostMapping("/documents/{documentId}/versions")
    public ResponseEntity<KnowledgeDocumentVersion> createVersion(
            @PathVariable String documentId,
            @Valid @RequestBody KnowledgeVersionRequest request) {
        KnowledgeDocumentVersion draft = knowledge.createVersion(documentId, request.toDomain(documentId));
        return ResponseEntity.created(versionLocation(draft)).body(draft);
    }

    @PostMapping("/documents/{documentId}/versions/{version}/publish")
    public KnowledgePublicationResult publish(
            @PathVariable String documentId,
            @PathVariable int version) {
        return knowledge.publish(documentId, version);
    }

    @PostMapping("/documents/{documentId}/versions/{version}/retire")
    public KnowledgeDocumentVersion retire(
            @PathVariable String documentId,
            @PathVariable int version) {
        return knowledge.retire(documentId, version);
    }

    @PostMapping("/search")
    public List<RetrievalHit> search(@Valid @RequestBody KnowledgeSearchRequest request) {
        return knowledge.search(request.query(), request.topK(), request.documentType(), request.productCode());
    }

    @PostMapping("/evaluations")
    public RetrievalEvaluationReport evaluate(@Valid @RequestBody KnowledgeEvaluationRequest request) {
        return evaluations.evaluate(
                request.cases().stream().map(ApiDtos.KnowledgeEvaluationCaseRequest::toDomain).toList(),
                request.topK(),
                request.minimumRecallAtK(),
                request.minimumMeanReciprocalRank());
    }
}
