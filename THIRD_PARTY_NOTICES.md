# Third-party components

This service uses local models and libraries without network activation, API keys, license-server requests, or online inference.

| Component | Version / file | License / source |
| --- | --- | --- |
| OpenCV | 4.9.0 | Apache-2.0, https://opencv.org/license/ |
| SQLite JDBC | org.xerial:sqlite-jdbc:3.53.4.0 | Apache-2.0; bundled SQLite is public domain, https://github.com/xerial/sqlite-jdbc |
| OpenPnP OpenCV Java package | org.openpnp:opencv:4.9.0-0 | Native binaries and loader distribution: https://github.com/openpnp/opencv ; see bundled JAR notices |
| YuNet | face_detection_yunet_2023mar.onnx | MIT, https://github.com/opencv/opencv_zoo/tree/main/models/face_detection_yunet |
| SFace | face_recognition_sface_2021dec.onnx | Apache-2.0, https://github.com/opencv/opencv_zoo/tree/main/models/face_recognition_sface |

Redistribute the model license texts in `licenses/` with deployment copies. The JAR preserves library notices in its bundled dependencies. Open source license obligations still apply; no paid recognition SDK or activation service is used.

Model SHA-256:

```
8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4  face_detection_yunet_2023mar.onnx
0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79  face_recognition_sface_2021dec.onnx
```

The public portrait downloaded from the OpenCV sample repository during validation is not included in the offline deployment bundle. Synthetic distractor vectors are only performance fixtures; they are not evidence of recognition accuracy across real people.

Real-time RTSP uses a separately installed FFmpeg executable through a child process. FFmpeg is not redistributed in this project's JAR or offline ZIP; deployment owners supply a build appropriate to their platform and retain its own notices and license terms. The locally installed test build identifies itself as GPL v3 in its vendor README. USB browser capture does not require FFmpeg. MediaMTX downloaded under `target/rtsp-test-server` is a local verification fixture only and is not included in deployment artifacts (upstream: https://github.com/bluenviron/mediamtx).

## AuraFace-v1 (default recognition model)

Publisher: fal, model card and license: https://huggingface.co/fal/AuraFace-v1 . Publisher labels weights Apache-2.0 and describes commercial use; its model card notes demographic/generalization limitations. This does not establish accuracy for this deployment. No InsightFace-provided pretrained weights or Python package are bundled.

Pinned revision: af6d057c9b0ec4071d4c49c80e3539258798b609; upstream glintr100.onnx renamed face_recognition_auraface_v1.onnx. SHA-256: a7933ea5330113b01c9b60351d8f4c33003f145d8470ac5f0e52ee2effe25c60. License text is preserved verbatim in licenses/AuraFace-Apache-2.0.txt. SFace remains in the bundle for its OpenCV alignment wrapper and explicit rollback; AuraFace extraction uses its own RGB/127.5 normalization, not SFace preprocessing.
