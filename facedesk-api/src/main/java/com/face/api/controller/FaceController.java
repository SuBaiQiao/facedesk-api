package com.face.api.controller;

import com.face.api.model.ApiResponse;
import com.face.api.service.FaceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 与参考 psp-app-face 保持路径和参数兼容的人脸接口。 */
@RestController
@RequestMapping("/face")
public class FaceController {
    private final FaceService service;

    public FaceController(FaceService service) {
        this.service = service;
    }

    /** 采集或更新一条人脸底库记录。 */
    @PostMapping("/v2/faceCollection")
    public ApiResponse<?> faceCollection(
            @RequestParam("img") MultipartFile img,
            @RequestParam String role,
            @RequestParam String code,
            @RequestParam(required = false) String name) {
        service.collect(bytes(img), role, code, name, img.getContentType());
        return ApiResponse.success();
    }

    /** 人脸 1:N 识别，可按角色过滤。 */
    @PostMapping("/v2/faceVerification")
    public ApiResponse<?> faceVerification(
            @RequestParam("img") MultipartFile img,
            @RequestParam(required = false, defaultValue = "") String role,
            @RequestParam(required = false) Double matchScore,
            @RequestParam(required = false, defaultValue = "0") Integer verifyMinSide) {
        return ApiResponse.success(service.recognize(bytes(img), role, matchScore, verifyMinSide));
    }

    /** 两张图片的人脸 1:1 比对。 */
    @PostMapping("/v2/faceVerificationOne")
    public ApiResponse<?> faceVerificationOne(
            @RequestParam("img1") MultipartFile img1,
            @RequestParam("img2") MultipartFile img2,
            @RequestParam(required = false) Double matchScore) {
        return ApiResponse.success(service.verifyOne(bytes(img1), bytes(img2), matchScore));
    }

    /** 活体检测；未配置活体模型时返回 supported=false。 */
    @PostMapping("/v2/faceDetectLiveliness")
    public ApiResponse<?> faceDetectLiveliness(@RequestParam("img") MultipartFile img) {
        return ApiResponse.success(service.liveness(bytes(img)));
    }

    /** 检测图片中的人脸框。 */
    @PostMapping("/v2/faceDetectionAndDraw")
    public ApiResponse<?> faceDetectionAndDraw(@RequestParam("img") MultipartFile img) {
        return ApiResponse.success(service.detect(bytes(img)));
    }

    /** 执行基础人脸数量和图片质量检查。 */
    @PostMapping("/v2/faceDetectQuality")
    public ApiResponse<?> faceDetectQuality(
            @RequestParam("img") MultipartFile img,
            @RequestParam(required = false) Double qualityScore) {
        return ApiResponse.success(service.quality(bytes(img), qualityScore));
    }

    /** 删除指定角色和编号的人脸。 */
    @PostMapping("/v2/faceRemove")
    public ApiResponse<?> faceRemove(@RequestParam String role, @RequestParam String code) {
        service.remove(role, code);
        return ApiResponse.success();
    }

    /** 查询指定角色的人脸列表；角色为空时查询全部。 */
    @PostMapping("/v2/faceListByRole")
    public ApiResponse<?> faceListByRole(
            @RequestParam(required = false, defaultValue = "") String role) {
        return ApiResponse.success(service.list(role));
    }

    /** 查看当前引擎和内存底库状态。 */
    @GetMapping("/v2/status")
    public ApiResponse<?> status() {
        return ApiResponse.success(new StatusResponse(
                service.engineName(), service.isAvailable(), service.databaseSize()));
    }

    /** 避免把 MultipartFile 的异常处理散落到各个接口中。 */
    private byte[] bytes(MultipartFile file) {
        try {
            if (file == null || file.isEmpty()) {
                throw new IllegalArgumentException("图片不能为空");
            }
            if (file.getSize() > 10 * 1024 * 1024) {
                throw new IllegalArgumentException("图片不能超过 10 MB");
            }
            return file.getBytes();
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("无效的图片", e);
        }
    }

    /** 状态接口的稳定返回模型。 */
    private static class StatusResponse {
        private final String engine;
        private final boolean available;
        private final int databaseSize;

        private StatusResponse(String engine, boolean available, int databaseSize) {
            this.engine = engine;
            this.available = available;
            this.databaseSize = databaseSize;
        }

        public String getEngine() {
            return engine;
        }

        public boolean isAvailable() {
            return available;
        }

        public int getDatabaseSize() {
            return databaseSize;
        }
    }
}
