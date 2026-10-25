package com.face.api;

import com.face.api.engine.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeatureMathTest {
    @Test void cosineUsesActualNormAndRejectsCorruptFeatures() {
        float[] a = new float[128], b = new float[128]; a[0] = 3; b[1] = 4;
        assertEquals(0, FeatureMath.cosine(a, b), 1e-8);
        b[0] = 6; b[1] = 0;
        assertEquals(1, FeatureMath.cosine(a, b), 1e-8);
        b[0] = -6;
        assertEquals(-1, FeatureMath.cosine(a, b), 1e-8);
        assertEquals(1, FeatureMath.normalize(a)[0]);
        assertThrows(FaceEngineException.class, () -> FeatureMath.cosine(a, new float[1024]));
        assertThrows(FaceEngineException.class, () -> FeatureMath.cosine(a, new float[128]));
        b[0] = Float.NaN;
        assertThrows(FaceEngineException.class, () -> FeatureMath.cosine(a, b));
    }
}
