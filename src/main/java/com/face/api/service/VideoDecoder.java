package com.face.api.service;

import com.face.api.config.FaceProperties;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.videoio.*;
import org.springframework.stereotype.Component;
import java.nio.file.Path;

/** 顺序读取，不按时间间隔跳帧；仅接受本地临时文件。 */
@Component
public class VideoDecoder {
    private final FaceProperties config;
    public VideoDecoder(FaceProperties config) { this.config = config; }
    public Session open(Path path) { return new Session(path, config.getMaxImagePixels()); }

    public static class Session implements AutoCloseable {
        private final VideoCapture capture;
        private final Mat image = new Mat();
        private final int maxPixels;
        public final double fps;
        public final long totalFrames;
        public Session(Path path, int maxPixels) {
            this.maxPixels = maxPixels;
            capture = new VideoCapture();
            try {
                if (!capture.open(path.toAbsolutePath().toString()))
                    throw new IllegalArgumentException("视频无法解码。请使用本机支持的 MP4 或 AVI 编码，或将视频转为 MJPEG AVI 后重试。");
                fps = capture.get(Videoio.CAP_PROP_FPS);
                totalFrames = Math.max(0, (long) capture.get(Videoio.CAP_PROP_FRAME_COUNT));
                if (!Double.isFinite(fps) || fps <= 0)
                    throw new IllegalArgumentException("视频帧率无效，无法可靠定位识别时间点");
                validateSize(capture.get(Videoio.CAP_PROP_FRAME_WIDTH), capture.get(Videoio.CAP_PROP_FRAME_HEIGHT));
            } catch (RuntimeException | Error e) { capture.release(); image.release(); throw e; }
        }
        private void validateSize(double width, double height) {
            if (width < 1 || height < 1 || width * height > maxPixels)
                throw new IllegalArgumentException("视频画面尺寸无效或超过像素限制（" + maxPixels + "）");
        }
        // 复用解码Mat，但输出独立JPEG字节；每帧再次验证尺寸，兼容中途变分辨率的视频。
        public byte[] next() {
            if (!capture.read(image) || image.empty()) return null;
            validateSize(image.cols(), image.rows());
            MatOfByte encoded = new MatOfByte();
            try {
                if (!Imgcodecs.imencode(".jpg", image, encoded)) throw new IllegalArgumentException("视频帧编码失败");
                return encoded.toArray();
            } finally { encoded.release(); }
        }
        // 优先使用解码器时间戳；无有效时间戳时才按帧号/帧率估算，用于定位原视频。
        public double timeSeconds(long frame) {
            double milliseconds = capture.get(Videoio.CAP_PROP_POS_MSEC);
            return Double.isFinite(milliseconds) && milliseconds > 0 ? milliseconds / 1000 : frame / fps;
        }
        @Override public void close() { image.release(); capture.release(); }
    }
}
