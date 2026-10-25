package com.face.api.engine;

/** 不通过 JNI 进行底库匹配，避免每个候选的原生调用开销。 */
public final class FeatureMath {
    private FeatureMath() { }
    // 返回单位长度向量的副本；拒绝NaN、无穷值和退化向量，避免污染匹配结果。
    public static float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) {
            if (!Float.isFinite(x)) throw new FaceEngineException("无效的人脸特征");
            norm += (double) x * x;
        }
        if (norm < 1e-12) throw new FaceEngineException("人脸特征为空或退化");
        float[] result = v.clone();
        double scale = 1 / Math.sqrt(norm);
        for (int i = 0; i < result.length; i++) result[i] *= scale;
        return result;
    }
    // 只比较同维向量；余弦值是相似度而非身份概率，模型指纹由上层另外校验。
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || (a.length != 128 && a.length != 512))
            throw new FaceEngineException("特征维度不匹配，请重新采集人脸");
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            if (!Float.isFinite(a[i]) || !Float.isFinite(b[i]))
                throw new FaceEngineException("无效的人脸特征");
            dot += (double) a[i] * b[i]; aa += (double) a[i] * a[i]; bb += (double) b[i] * b[i];
        }
        if (aa < 1e-12 || bb < 1e-12) throw new FaceEngineException("人脸特征退化");
        return Math.max(-1, Math.min(1, dot / Math.sqrt(aa * bb)));
    }
}
