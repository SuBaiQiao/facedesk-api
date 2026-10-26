package com.face.api.engine;

public class FaceBusyException extends RuntimeException {
    public FaceBusyException(String message) {
        super(message);
    }
}
