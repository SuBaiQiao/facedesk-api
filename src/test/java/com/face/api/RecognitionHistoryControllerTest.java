package com.face.api;

import com.face.api.controller.RecognitionHistoryController;
import com.face.api.service.RecognitionHistoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RecognitionHistoryController.class)
class RecognitionHistoryControllerTest {
    @Autowired MockMvc mvc;
    @MockBean RecognitionHistoryService history;
    @Test void servesFilteredPagesDetailsAndCsvWithoutCaching() throws Exception {
        when(history.page(any(),eq(2),eq(20))).thenReturn(Collections.singletonMap("total",30));
        mvc.perform(get("/admin/history").param("page","2").param("source","VIDEO").param("q","P001"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(30)).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/admin/history").param("source","invalid")).andExpect(status().isBadRequest());
        when(history.get("missing")).thenThrow(new IllegalArgumentException("识别记录不存在"));
        mvc.perform(get("/admin/history/missing")).andExpect(status().isBadRequest());
        when(history.export(any())).thenReturn(new byte[]{1,2,3});
        mvc.perform(get("/admin/history/export")).andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=recognition-history.csv"));
    }
}
