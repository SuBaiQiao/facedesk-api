package com.face.api.service;

import java.io.IOException;

public interface RtspFrameSource {
    boolean available();

    Capture open(String url) throws IOException;

    interface Capture extends AutoCloseable {
        byte[] next() throws IOException;

        @Override
        void close();
    }
}
