package com.zhiyun.web;

import com.zhiyun.domain.Manuscript;
import com.zhiyun.domain.ResearchProject;
import com.zhiyun.domain.ReviewTask;
import com.zhiyun.manuscript.ManuscriptService;
import com.zhiyun.rag.VenueCatalog;
import com.zhiyun.workflow.ReviewReport;
import com.zhiyun.workflow.ReviewService;
import com.zhiyun.workflow.WorkflowCatalog;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ManuscriptController {
    private final ManuscriptService manuscriptService;
    private final ReviewService reviewService;
    private final ReviewReport reviewReport;
    private final WorkflowCatalog workflowCatalog;

    public ManuscriptController(ManuscriptService manuscriptService, ReviewService reviewService,
                                ReviewReport reviewReport, WorkflowCatalog workflowCatalog) {
        this.manuscriptService = manuscriptService;
        this.reviewService = reviewService;
        this.reviewReport = reviewReport;
        this.workflowCatalog = workflowCatalog;
    }

    public record NameReq(String name) {
    }

    public record WorkflowReq(String workflow, String targetVenue) {
    }

    @GetMapping("/workflows")
    public Map<String, Object> workflows() {
        return Map.of("workflows", workflowCatalog.all(), "billing", workflowCatalog.billingHint());
    }

    @GetMapping("/venues")
    public Map<String, Object> venues(@RequestParam(required = false) String q) {
        return Map.of("venues", VenueCatalog.search(q), "note", "未指定时审校回退正文抽刊名。目录与 PUBLIC 知识专章对齐。");
    }

    @GetMapping("/projects")
    public List<ResearchProject> projects() {
        return manuscriptService.projects();
    }

    @PostMapping("/projects")
    public ResearchProject createProject(@RequestBody NameReq req) {
        return manuscriptService.createProject(req.name());
    }

    @GetMapping("/manuscripts")
    public Map<String, Object> manuscripts(@RequestParam(required = false) String q,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) Long projectId) {
        return manuscriptService.list(q, page, size, projectId);
    }

    @GetMapping("/manuscripts/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return manuscriptService.detail(id);
    }

    @GetMapping("/manuscripts/{id}/versions/{versionNo}")
    public Map<String, Object> version(@PathVariable long id, @PathVariable int versionNo) {
        return manuscriptService.versionPreview(id, versionNo);
    }

    @GetMapping("/manuscripts/{id}/versions/{versionNo}/inspect")
    public Map<String, Object> inspect(@PathVariable long id, @PathVariable int versionNo) {
        return manuscriptService.inspect(id, versionNo);
    }

    @GetMapping("/manuscripts/{id}/versions/{versionNo}/file")
    public ResponseEntity<Resource> versionFile(@PathVariable long id,
                                                @PathVariable int versionNo,
                                                @RequestParam(defaultValue = "false") boolean download) {
        ManuscriptService.FilePayload file = manuscriptService.fileOf(id, versionNo);
        ContentDisposition disposition = (download
                ? ContentDisposition.attachment()
                : ContentDisposition.inline())
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(file.mediaType())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.resource());
    }

    @PostMapping("/manuscripts")
    public Manuscript upload(@RequestParam long projectId, @RequestParam("file") MultipartFile file) throws Exception {
        return manuscriptService.upload(projectId, file);
    }

    @PostMapping("/manuscripts/{id}/reviews")
    public ReviewTask start(@PathVariable long id, @RequestBody WorkflowReq req) {
        return reviewService.start(id, req.workflow(), req.targetVenue());
    }

    @GetMapping("/reviews")
    public Map<String, Object> reviews(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size,
                                       @RequestParam(required = false) String status) {
        return reviewService.listMine(q, page, size, status);
    }

    @GetMapping("/reviews/{taskId}")
    public ReviewTask task(@PathVariable String taskId) {
        return reviewService.get(taskId);
    }

    @GetMapping("/reviews/{taskId}/trace")
    public Map<String, Object> trace(@PathVariable String taskId) {
        return reviewService.trace(taskId);
    }

    @GetMapping("/reviews/{taskId}/artifacts")
    public List<Object> artifacts(@PathVariable String taskId) {
        return reviewService.artifacts(taskId);
    }

    @GetMapping("/reviews/{taskId}/report")
    public Map<String, Object> report(@PathVariable String taskId) {
        return reviewReport.export(taskId);
    }

    @PostMapping("/reviews/{taskId}/retry")
    public ReviewTask retry(@PathVariable String taskId) {
        return reviewService.retry(taskId);
    }

    @PostMapping("/reviews/{taskId}/cancel")
    public ReviewTask cancel(@PathVariable String taskId) {
        return reviewService.cancel(taskId);
    }

    @PostMapping("/reviews/{taskId}/accept")
    public ReviewTask accept(@PathVariable String taskId) {
        return reviewService.accept(taskId);
    }

    public record AcceptPartialReq(List<String> patchIds) {
    }

    @PostMapping("/reviews/{taskId}/accept-partial")
    public Map<String, Object> acceptPartial(@PathVariable String taskId, @RequestBody(required = false) AcceptPartialReq req) {
        List<String> ids = req == null || req.patchIds() == null ? List.of() : req.patchIds();
        return reviewService.acceptPartial(taskId, ids);
    }

    @PostMapping("/reviews/{taskId}/reject")
    public ReviewTask reject(@PathVariable String taskId) {
        return reviewService.reject(taskId);
    }

    @GetMapping("/reviews/{taskId}/diff")
    public Map<String, String> diff(@PathVariable String taskId) {
        return reviewService.diff(taskId);
    }

    @GetMapping("/reviews/{taskId}/merge-preview")
    public Map<String, Object> mergePreview(@PathVariable String taskId) {
        return reviewService.mergePreview(taskId, null);
    }

    @PostMapping("/reviews/{taskId}/merge-preview")
    public Map<String, Object> mergePreviewPartial(@PathVariable String taskId,
                                                   @RequestBody(required = false) AcceptPartialReq req) {
        List<String> ids = req == null ? null : req.patchIds();
        return reviewService.mergePreview(taskId, ids);
    }
}
