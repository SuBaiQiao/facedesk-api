package com.facedesk.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 统一入口，Nacos发现识别实例；保留/api前缀转发给原服务。
 */
@SpringBootApplication
public class FaceDeskGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(FaceDeskGatewayApplication.class, args);
    }
}
