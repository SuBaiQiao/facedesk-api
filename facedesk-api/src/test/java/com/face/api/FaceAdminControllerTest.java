package com.face.api;

import com.face.api.controller.FaceAdminController;
import com.face.api.engine.*;
import com.face.api.model.FaceRecord;
import com.face.api.service.*;
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

@WebMvcTest(FaceAdminController.class)
class FaceAdminControllerTest {
    @Test void captureEnrollmentRequiresAdminAndUsesCreateOnlyService() throws Exception {
        MockMultipartFile photo=new MockMultipartFile("img","capture.jpg","image/jpeg",new byte[]{1,2,3});
        mvc.perform(multipart("/admin/faces/from-capture").file(photo).param("role","Guest").param("code","NEW")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/admin/faces/from-capture").file(photo).header("X-Face-Admin","1").param("role","Guest").param("code","NEW").param("name","Fixture"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        verify(service).collectCapture(new byte[]{1,2,3},"Guest","NEW","Fixture","image/jpeg");
        verify(service,never()).collect(any(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void detailAndReferenceEndpointsExposeMetadataAndRequireAdminForWrites() throws Exception {
        Map<String,Object> detail=new LinkedHashMap<>();detail.put("role","Guest");detail.put("code","P1");detail.put("sampleCount",2);detail.put("samples",Collections.singletonList(Collections.singletonMap("id","s1")));
        when(admin.detail("Guest","P1")).thenReturn(detail);
        mvc.perform(get("/admin/faces/detail").param("role","Guest").param("code","P1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.sampleCount").value(2)).andExpect(jsonPath("$.data.feature").doesNotExist()).andExpect(header().string("Cache-Control","no-store"));
        MockMultipartFile sample=new MockMultipartFile("img","reference.jpg","image/jpeg",new byte[]{1,2,3});
        mvc.perform(multipart("/admin/faces/references").file(sample).param("role","Guest").param("code","P1")).andExpect(status().isBadRequest());
        mvc.perform(multipart("/admin/faces/references").file(sample).header("X-Face-Admin","1").param("role","Guest").param("code","P1")).andExpect(status().isOk());
        verify(service).addReference(any(),eq("Guest"),eq("P1"));
        mvc.perform(delete("/admin/faces/references").header("X-Face-Admin","1").param("role","Guest").param("code","P1").param("id","s1")).andExpect(status().isOk());verify(admin).removeReference("Guest","P1","s1");
    }
    @Autowired MockMvc mvc;
    @MockBean FaceAdminService admin;
    @MockBean FaceService service;
    @Test void updatesNameWithoutRequiringAnImageOrRunningEngine() throws Exception {
        when(admin.get("Guest", "G001")).thenReturn(new FaceRecord());
        mvc.perform(multipart("/admin/faces/update").header("X-Face-Admin","1")
            .param("role","Guest").param("code","G001").param("name","新姓名"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.code").value("200"));
        verify(admin).updateName("Guest", "G001", "新姓名"); verifyNoInteractions(service);
    }
    @Test void controlRequiresAdminHeaderAndPauseUsesLifecycle() throws Exception {
        FaceEngine engine = mock(FaceEngine.class); when(engine.featureSpaceId()).thenReturn("v1");
        EngineLifecycle lifecycle = new EngineLifecycle(engine, 1000);
        when(service.lifecycle()).thenReturn(lifecycle); when(service.engineStatus()).thenReturn(Collections.singletonMap("state","PAUSED"));
        mvc.perform(post("/admin/engine/pause")).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/engine/pause").header("X-Face-Admin","1"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("PAUSED"));
        org.junit.jupiter.api.Assertions.assertEquals(EngineLifecycle.State.PAUSED, lifecycle.getState());
    }
    @Test void photosAreLoadedSeparatelyWithNoStoreAndNoSniff() throws Exception {
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        when(admin.get("Guest","G001")).thenReturn(new FaceRecord("Guest","G001","人员",Base64.getEncoder().encodeToString(png),null));
        mvc.perform(get("/admin/faces/image").param("role","Guest").param("code","G001"))
            .andExpect(status().isOk()).andExpect(content().contentType("image/png"))
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().string("X-Content-Type-Options","nosniff"));
    }
    @Test void replacementPhotoAndDeletionUseExistingServiceRules() throws Exception {
        when(admin.get("Guest", "G001")).thenReturn(new FaceRecord());
        MockMultipartFile photo = new MockMultipartFile("img", "person.png", "image/png", new byte[] {1,2,3});
        mvc.perform(multipart("/admin/faces/update").file(photo).header("X-Face-Admin","1")
            .param("role","Guest").param("code","G001").param("name","新姓名"))
            .andExpect(status().isOk());
        verify(service).collect(new byte[] {1,2,3}, "Guest", "G001", "新姓名", "image/png");
        mvc.perform(delete("/admin/faces").header("X-Face-Admin","1").param("role","Guest").param("code","G001"))
            .andExpect(status().isOk());
        verify(service).remove("Guest", "G001");
    }
}
