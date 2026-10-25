package com.face.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/** 离线人脸服务启动类；保留原类名以兼容已有启动脚本。 */
@EnableAsync
@SpringBootApplication
public class SeetaFace6Application {
    public static void main(String[] args) {
        SpringApplication.run(SeetaFace6Application.class, args);
    }
}
