package com.face.api.engine;

import com.face.api.model.FaceBox;

/** 单次检测、对齐、提取的结果。 */
public class FaceSample {
    private final FaceBox box;
    private final float[] feature;
    public FaceSample(FaceBox box, float[] feature) { this.box = box; this.feature = feature; }
    public FaceBox getBox() { return box; }
    public float[] getFeature() { return feature; }
}
