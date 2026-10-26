package com.face.api.controller;

import com.face.api.model.ApiResponse;
import com.face.api.service.RecognitionHistoryService;
import com.face.api.repository.RecognitionHistoryRepository.Filter;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/history")
public class RecognitionHistoryController {
    private final RecognitionHistoryService history;

    public RecognitionHistoryController(RecognitionHistoryService history) {
        this.history = history;
    }

    @ModelAttribute
    public void noCache(javax.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
    }

    @GetMapping
    public ApiResponse<?> page(@RequestParam(defaultValue = "") String source, @RequestParam(defaultValue = "") String status,
                               @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
                               @RequestParam(defaultValue = "") String taskId, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(history.page(new Filter(source, status, q, from, to, taskId), page, size));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "") String source, @RequestParam(defaultValue = "") String status,
                                         @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to, @RequestParam(defaultValue = "") String taskId) {
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=recognition-history.csv").contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(history.export(new Filter(source, status, q, from, to, taskId)));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable String id) {
        return ApiResponse.success(history.get(id));
    }
}
