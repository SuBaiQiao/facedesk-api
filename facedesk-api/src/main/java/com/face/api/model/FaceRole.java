package com.face.api.model;

/** 参考项目中使用的人脸角色编码。 */
public final class FaceRole {
    /** 监所民警。 */
    public static final String POLICE_PRISON = "Police_Prison";
    /** 办案民警。 */
    public static final String POLICE_CASE = "Police_Case";
    /** 律师。 */
    public static final String LAWYER = "Lawyer";
    /** 在押人员。 */
    public static final String PRISONER = "Prisoner";
    /** 访客。 */
    public static final String GUEST = "Guest";
    /** 家属。 */
    public static final String FAMILY = "Family";
    /** 在押人员进出场景。 */
    public static final String PRISONER_INOUT = "Prisoner_Inout";

    private FaceRole() {
    }
}
