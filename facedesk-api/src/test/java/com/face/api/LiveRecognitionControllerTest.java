package com.face.api;

import com.face.api.controller.LiveRecognitionController;
import com.face.api.service.LiveRecognitionService;
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

@WebMvcTest(LiveRecognitionController.class)
class LiveRecognitionControllerTest {
    @Autowired MockMvc mvc;@MockBean LiveRecognitionService live;
    @Test void cameraWritesRequireHeaderAndFramesUseMultipart()throws Exception{
        when(live.start(eq("USB"),eq("门口"),eq(""),isNull())).thenReturn(Collections.singletonMap("id","session"));
        String input="{\"source\":\"USB\",\"name\":\"门口\"}";
        mvc.perform(post("/admin/live").contentType("application/json").content(input)).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/live").header("X-Face-Admin","1").contentType("application/json").content(input)).andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value("session"));
        mvc.perform(multipart("/admin/live/session/frame").file(new MockMultipartFile("frame","camera.jpg","image/jpeg",new byte[]{1})).header("X-Face-Admin","1")).andExpect(status().isOk());
        verify(live).usbFrame(eq("session"),any());
        mvc.perform(post("/admin/live/session/stop")).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/live/session/stop").header("X-Face-Admin","1")).andExpect(status().isOk());verify(live).stop("session");
    }
    @Test void pollsChangedFramesAndDisablesCaching()throws Exception{
        when(live.status("session",7)).thenReturn(Collections.singletonMap("sequence",8));
        mvc.perform(get("/admin/live/session").param("after","7")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.data.sequence").value(8));
        mvc.perform(get("/admin/live/capabilities")).andExpect(status().isOk());
    }
}
