package com.face.api.model;

/**
 * 独立的活体检测能力结果。
 */
public class LivenessResult {
    private boolean supported;
    private boolean pass;
    private String message;

    public LivenessResult() {
    }

    public LivenessResult(boolean supported, boolean pass, String message) {
        this.supported = supported;
        this.pass = pass;
        this.message = message;
    }

    public boolean isSupported() {
        return supported;
    }

    public void setSupported(boolean supported) {
        this.supported = supported;
    }

    public boolean isPass() {
        return pass;
    }

    public void setPass(boolean pass) {
        this.pass = pass;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
