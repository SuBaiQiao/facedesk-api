package com.face.api;

import com.face.api.controller.VideoRecognitionController;
import com.face.api.service.VideoRecognitionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(VideoRecognitionController.class)
class VideoRecognitionControllerTest {
    @Autowired MockMvc mvc;
    @MockBean VideoRecognitionService service;
    @Test void requiresHeaderForUploadAndCancelAndReturnsProgress() throws Exception {
        MockMultipartFile file=new MockMultipartFile("video","sample.avi","video/x-msvideo",new byte[]{1});
        when(service.start(any(),eq(""))).thenReturn(Collections.singletonMap("id","job"));
        mvc.perform(multipart("/admin/videos").file(file)).andExpect(status().isBadRequest());
        mvc.perform(multipart("/admin/videos").file(file).header("X-Face-Admin","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("job"));
        when(service.status("job")).thenReturn(Collections.singletonMap("state","RUNNING"));
        mvc.perform(get("/admin/videos/job")).andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("RUNNING"))
            .andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/admin/videos/job/cancel")).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/videos/job/cancel").header("X-Face-Admin","1")).andExpect(status().isOk());
        verify(service).cancel("job");
    }
    @Test void returnsPagedResultsAndDownload() throws Exception {
        when(service.page("job",2,24)).thenReturn(Collections.singletonMap("total",50));
        mvc.perform(get("/admin/videos/job/faces").param("page","2")).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(50));
        when(service.export("job")).thenReturn("frame,time_seconds".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mvc.perform(get("/admin/videos/job/export")).andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=video-faces.csv"));
    }
}
