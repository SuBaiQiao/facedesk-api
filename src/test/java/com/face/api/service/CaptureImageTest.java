package com.face.api.service;

import com.face.api.model.FaceBox;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class CaptureImageTest {
    BufferedImage decode(String image) throws Exception {return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(image.substring(image.indexOf(',')+1))));}
    @Test void enrollmentRetainsContextAndResolutionWhilePreviewStaysSmall() throws Exception {
        BufferedImage source=new BufferedImage(1280,720,BufferedImage.TYPE_INT_RGB);
        FaceBox box=new FaceBox(500,200,700,400);
        BufferedImage capture=decode(VideoRecognitionService.enrollmentImage(source,box));
        assertEquals(320,capture.getWidth());assertEquals(320,capture.getHeight());
        assertEquals(112,decode(VideoRecognitionService.thumbnail(source,box)).getWidth());
        capture=decode(VideoRecognitionService.enrollmentImage(source,new FaceBox(0,0,1000,700)));
        assertEquals(512,capture.getWidth());assertTrue(capture.getHeight()<=512);
        assertNull(VideoRecognitionService.enrollmentImage(null,box));
    }
}
