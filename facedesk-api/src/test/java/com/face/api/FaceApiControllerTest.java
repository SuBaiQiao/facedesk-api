package com.face.api;

import com.face.api.controller.FaceController;
import com.face.api.model.FaceBox;
import com.face.api.model.RecognitionResult;
import com.face.api.service.FaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Collections;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/** 验证控制器的兼容接口和请求参数映射。 */
@WebMvcTest(FaceController.class)
class FaceApiControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FaceService faceService;

    @Test
    void collectionAndOneToNVerificationUseCompatibleContract() throws Exception {
        byte[] image = png();
        MockMultipartFile file = new MockMultipartFile(
                "img", "face.png", "image/png", image);
        when(faceService.recognize(
                any(byte[].class), eq("Guest"), isNull(), eq(0)))
                .thenReturn(Collections.singletonList(
                        new RecognitionResult(
                                "Guest", "G001", "测试人员", 0.98,
                                new FaceBox(0, 0, 32, 32))));

        mockMvc.perform(multipart("/face/v2/faceCollection")
                        .file(file)
                        .param("role", "Guest")
                        .param("code", "G001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"));

        mockMvc.perform(multipart("/face/v2/faceVerification")
                        .file(file)
                        .param("role", "Guest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("200"))
                .andExpect(jsonPath("$.data[0].code").value("G001"));
    }

    /** 生成一张最小有效 PNG，供控制器请求测试使用。 */
    private byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(
                32, 32, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
