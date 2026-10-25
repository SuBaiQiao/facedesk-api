package com.face.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/** FaceDesk人脸识别服务平台启动类。 */
@EnableAsync
@SpringBootApplication
public class FaceDeskApplication {
    public static void main(String[] args) {
        SpringApplication.run(FaceDeskApplication.class, args);
    }
}
