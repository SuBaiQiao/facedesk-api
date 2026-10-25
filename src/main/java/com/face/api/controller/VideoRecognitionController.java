package com.face.api.controller;

import com.face.api.model.ApiResponse;
import com.face.api.service.VideoRecognitionService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
@RequestMapping("/admin/videos")
public class VideoRecognitionController {
    private final VideoRecognitionService videos;
    public VideoRecognitionController(VideoRecognitionService videos) { this.videos = videos; }
    @ModelAttribute public void noCache(javax.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store"); response.setHeader("X-Content-Type-Options","nosniff");
    }
    @PostMapping public ApiResponse<?> start(@RequestHeader("X-Face-Admin") String marker,
        @RequestParam("video") MultipartFile video, @RequestParam(defaultValue="") String role) throws IOException {
        return ApiResponse.success(videos.start(video,role));
    }
    @GetMapping("/{id}") public ApiResponse<?> status(@PathVariable String id) { return ApiResponse.success(videos.status(id)); }
    @GetMapping("/{id}/faces") public ApiResponse<?> page(@PathVariable String id,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="24") int size) throws IOException {
        return ApiResponse.success(videos.page(id,page,size));
    }
    @PostMapping("/{id}/cancel") public ApiResponse<?> cancel(@RequestHeader("X-Face-Admin") String marker,@PathVariable String id) {
        return ApiResponse.success(videos.cancel(id));
    }
    @GetMapping("/{id}/export") public ResponseEntity<byte[]> export(@PathVariable String id) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Content-Disposition","attachment; filename=video-faces.csv")
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(videos.export(id));
    }
}
