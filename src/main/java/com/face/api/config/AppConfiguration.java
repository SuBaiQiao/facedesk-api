package com.face.api.config;

import com.face.api.engine.FaceEngine;
import com.face.api.engine.SFaceEngine;
import com.face.api.repository.FaceRepository;
import com.face.api.repository.SqliteFaceRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Spring Bean 配置：引擎、底库和业务线程池。 */
@Configuration
@EnableConfigurationProperties(FaceProperties.class)
public class AppConfiguration {
    @Bean(destroyMethod = "close")
    public com.face.api.repository.RecognitionHistoryRepository recognitionHistoryRepository(FaceProperties properties,
            FaceRepository initializedRepository, com.fasterxml.jackson.databind.ObjectMapper json) {
        return new com.face.api.repository.RecognitionHistoryRepository(properties.getStoragePath(),json);
    }
    /** 创建并初始化离线YuNet检测与所选SFace/AuraFace识别后端。 */
    @Bean(destroyMethod = "close")
    public FaceEngine faceEngine(FaceProperties properties) {
        FaceEngine engine = new SFaceEngine(properties);
        engine.initialize();
        return engine;
    }

    /** SQLite 持久化，启动加载内存快照；只在显式指定时导入旧 JSON。 */
    @Bean(destroyMethod = "close")
    // 引擎先初始化得到真实模型指纹，再打开数据库；旧库原图迁移在仓储加载快照之前完成。
    public FaceRepository faceRepository(FaceProperties properties, FaceEngine engine) {
        SqliteFaceRepository repository = new SqliteFaceRepository(properties.getStoragePath(), engine.featureSpaceId(), engine);
        try {
            if (properties.getImportJson() != null && !properties.getImportJson().trim().isEmpty())
                repository.importJson(java.nio.file.Paths.get(properties.getImportJson()));
            return repository;
        } catch (RuntimeException e) { repository.close(); throw e; }
    }

    /** 隔离图片解码和 JNI 推理任务，避免占满 Tomcat 工作线程。 */
    @Bean(name = "faceTaskExecutor")
    public ThreadPoolTaskExecutor faceTaskExecutor(FaceProperties properties) {
        if (properties.getWorkers() > properties.getMaxInFlight())
            throw new IllegalArgumentException("face.workers 不能大于 face.max-in-flight");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getWorkers());
        executor.setMaxPoolSize(properties.getWorkers());
        executor.setQueueCapacity(properties.getMaxInFlight() - properties.getWorkers());
        executor.setThreadNamePrefix("face-inference-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        return executor;
    }
}
