package com.face.api.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 人脸角色校验工具。
 */
public final class FaceRoles {
    private FaceRoles() {
    }

    /**
     * 返回与旧项目兼容的角色列表。
     */
    public static List<String> supported() {
        return Collections.unmodifiableList(Arrays.asList(
                FaceRole.POLICE_PRISON,
                FaceRole.POLICE_CASE,
                FaceRole.LAWYER,
                FaceRole.PRISONER,
                FaceRole.GUEST,
                FaceRole.FAMILY,
                FaceRole.PRISONER_INOUT
        ));
    }

    /**
     * 空角色表示查询全部角色。
     */
    public static boolean isSupported(String role) {
        return role == null || role.isEmpty() || supported().contains(role);
    }
}
