package com.face.api.controller;

import com.face.api.model.ApiResponse;
import com.face.api.service.LiveRecognitionService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/admin/live")
public class LiveRecognitionController {
    private final LiveRecognitionService live;
    public LiveRecognitionController(LiveRecognitionService live){this.live=live;}
    @ModelAttribute public void noCache(javax.servlet.http.HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("X-Content-Type-Options","nosniff");}
    @GetMapping("/capabilities")public ApiResponse<?> capabilities(){return ApiResponse.success(live.capabilities());}
    @PostMapping public ApiResponse<?> start(@RequestHeader("X-Face-Admin")String marker,@RequestBody Map<String,String> input){
        return ApiResponse.success(live.start(input.get("source"),input.get("name"),input.getOrDefault("role",""),input.get("rtspUrl")));
    }
    @GetMapping("/{id}")public ApiResponse<?> status(@PathVariable String id,@RequestParam(defaultValue="-1")long after){return ApiResponse.success(live.status(id,after));}
    @PostMapping("/{id}/frame")public ApiResponse<?> frame(@RequestHeader("X-Face-Admin")String marker,@PathVariable String id,@RequestParam("frame")MultipartFile frame)throws IOException{
        if(frame.isEmpty()||frame.getSize()>4*1024*1024)throw new IllegalArgumentException("摄像头帧为空或超过 4 MB");return ApiResponse.success(live.usbFrame(id,frame.getBytes()));
    }
    @PostMapping("/{id}/stop")public ApiResponse<?> stop(@RequestHeader("X-Face-Admin")String marker,@PathVariable String id){return ApiResponse.success(live.stop(id));}
}
