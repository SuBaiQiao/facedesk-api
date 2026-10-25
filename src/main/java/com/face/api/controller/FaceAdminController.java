package com.face.api.controller;

import com.face.api.model.*;
import com.face.api.service.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.net.URLConnection;
import java.util.*;

@RestController
@RequestMapping("/admin")
public class FaceAdminController {
    private final FaceAdminService admin;
    private final FaceService service;
    public FaceAdminController(FaceAdminService admin, FaceService service) { this.admin = admin; this.service = service; }
    @ModelAttribute public void noCache(javax.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
    @GetMapping("/status") public ApiResponse<?> status() { return ApiResponse.success(service.engineStatus()); }
    @GetMapping("/roles") public ApiResponse<?> roles() { return ApiResponse.success(FaceRoles.supported()); }
    @GetMapping("/faces") public ApiResponse<?> faces(
        @RequestParam(defaultValue = "") String role, @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "12") int size) {
        return ApiResponse.success(admin.page(role, q, page, size));
    }
    @GetMapping("/faces/detail") public ApiResponse<?> detail(@RequestParam String role,@RequestParam String code) {
        Map<String,Object> result=admin.detail(role,code);
        if(service.isAvailable())try{String stored=admin.get(role,code).getImageBase64();if(stored!=null){if(stored.startsWith("data:"))stored=stored.substring(stored.indexOf(',')+1);List<FaceBox> boxes=service.detect(Base64.getDecoder().decode(stored));if(boxes.size()==1)result.put("faceBox",boxes.get(0));}}catch(RuntimeException ignored){}
        return ApiResponse.success(result);
    }
    // 抓拍创建接口要求管理标记并限制图片大小；检测与重复编号保护由业务/仓储层负责。
    @PostMapping("/faces/from-capture") public ApiResponse<?> enrollCapture(
            @RequestHeader("X-Face-Admin") String marker,@RequestParam String role,@RequestParam String code,
            @RequestParam(defaultValue="") String name,@RequestParam MultipartFile img) throws IOException {
        if(img.isEmpty()||img.getSize()>10*1024*1024)throw new IllegalArgumentException("请选择不超过10MB的抓拍照片");
        service.collectCapture(img.getBytes(),role,code,name,img.getContentType());return ApiResponse.success();
    }
    @PostMapping("/faces/references") public ApiResponse<?> addReference(@RequestHeader("X-Face-Admin")String marker,@RequestParam String role,@RequestParam String code,@RequestParam MultipartFile img)throws IOException{
        if(img.isEmpty()||img.getSize()>10*1024*1024)throw new IllegalArgumentException("请选择不超过10MB的照片");service.addReference(img.getBytes(),role,code);return ApiResponse.success();
    }
    @DeleteMapping("/faces/references")public ApiResponse<?> removeReference(@RequestHeader("X-Face-Admin")String marker,@RequestParam String role,@RequestParam String code,@RequestParam String id){admin.removeReference(role,code,id);return ApiResponse.success();}
    // 图片单独返回且禁止缓存；根据图片内容判断MIME，不信任原上传文件名或声明类型。
    @GetMapping("/faces/image") public ResponseEntity<byte[]> image(@RequestParam String role, @RequestParam String code,@RequestParam(required=false)String sampleId) throws IOException {
        String stored = sampleId==null?admin.get(role, code).getImageBase64():admin.referenceImage(role,code,sampleId);
        if (stored == null || stored.isEmpty()) return ResponseEntity.notFound().build();
        if (stored.startsWith("data:")) stored = stored.substring(stored.indexOf(',') + 1);
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(stored); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("保存的图片无效，请重新采集"); }
        String type = URLConnection.guessContentTypeFromStream(new ByteArrayInputStream(bytes));
        if (type == null || !Arrays.asList("image/jpeg", "image/png", "image/gif", "image/bmp", "image/x-bitmap").contains(type))
            throw new IllegalArgumentException("保存的图片格式不受支持，请重新采集");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
            .contentType(MediaType.parseMediaType(type)).body(bytes);
    }
    @PostMapping("/faces/update") public ApiResponse<?> update(
        @RequestHeader("X-Face-Admin") String marker, @RequestParam String role, @RequestParam String code,
        @RequestParam(defaultValue = "") String name, @RequestParam(required = false) MultipartFile img) throws IOException {
        admin.get(role, code);
        if (name.length() > 128) throw new IllegalArgumentException("姓名不能超过 128 个字符");
        if (img != null && img.getSize() > 10 * 1024 * 1024) throw new IllegalArgumentException("图片不能超过 10 MB");
        if (img != null && !img.isEmpty()) service.collect(img.getBytes(), role, code, name, img.getContentType());
        else admin.updateName(role, code, name);
        return ApiResponse.success();
    }
    @DeleteMapping("/faces") public ApiResponse<?> remove(
        @RequestHeader("X-Face-Admin") String marker, @RequestParam String role, @RequestParam String code) {
        service.remove(role, code); return ApiResponse.success();
    }
    @PostMapping("/engine/{action}") public ApiResponse<?> control(
        @RequestHeader("X-Face-Admin") String marker, @PathVariable String action) {
        switch (action) {
            case "pause": service.lifecycle().pause(); break;
            case "resume": service.lifecycle().resume(); break;
            case "reload": service.lifecycle().reload(); break;
            default: throw new IllegalArgumentException("未知的引擎操作");
        }
        return ApiResponse.success(service.engineStatus());
    }
}
