package com.face.api.controller;
import com.face.api.model.ApiResponse;
import com.face.api.service.*;
import com.face.api.repository.RecognitionHistoryRepository.Filter;
import org.springframework.web.bind.annotation.*;
@RestController
public class PersonHistoryController {
    private final FaceAdminService people;private final RecognitionHistoryService history;
    public PersonHistoryController(FaceAdminService people,RecognitionHistoryService history){this.people=people;this.history=history;}
    @GetMapping("/admin/faces/history")public ApiResponse<?> history(@RequestParam String role,@RequestParam String code,@RequestParam(defaultValue="1")int page,javax.servlet.http.HttpServletResponse response){people.get(role,code);response.setHeader("Cache-Control","no-store");return ApiResponse.success(history.page(Filter.person(role,code),page,5));}
}
