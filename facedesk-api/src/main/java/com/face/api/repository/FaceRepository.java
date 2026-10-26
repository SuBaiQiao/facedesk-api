package com.face.api.repository;

import com.face.api.model.FaceRecord;

import java.util.List;
import java.util.Optional;

/**
 * 人脸底库抽象，默认 SQLite 持久化、内存特征检索。
 */
public interface FaceRepository {
    void save(FaceRecord record);

    default void saveNew(FaceRecord record) {
        throw new UnsupportedOperationException();
    }

    Optional<FaceRecord> find(String role, String code);

    List<FaceRecord> findByRole(String role);

    List<FaceRecord> findAll();

    void delete(String role, String code);

    void updateName(String role, String code, String name);

    default void addReference(FaceRecord sample) {
        throw new UnsupportedOperationException();
    }

    default void removeReference(String role, String code, String id) {
        throw new UnsupportedOperationException();
    }

    default java.util.Map<String, Object> detail(String role, String code) {
        throw new UnsupportedOperationException();
    }

    default String referenceImage(String role, String code, String id) {
        throw new UnsupportedOperationException();
    }
}
