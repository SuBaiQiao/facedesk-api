import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Java 11 source-file command. No dependency, download, or activation. */
class FaceHttpLoad {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("java scripts/FaceHttpLoad.java BASE_URL IMAGE [concurrency=250] [requests=250] [expectedCode=P0]");
        String base = args[0].replaceAll("/$", "");
        int concurrency = args.length > 2 ? Integer.parseInt(args[2]) : 250;
        int requests = args.length > 3 ? Integer.parseInt(args[3]) : 250;
        String code = args.length > 4 ? args[4] : "P0";
        String boundary = "FaceLoadBoundary2026";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"role\"\r\n\r\nGuest\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"img\"; filename=\"face.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(Files.readAllBytes(Paths.get(args[1])));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).version(HttpClient.Version.HTTP_1_1).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/face/v2/faceVerification"))
            .timeout(Duration.ofSeconds(120)).header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        for (int i = 0; i < 8; i++) client.send(request, HttpResponse.BodyHandlers.discarding());
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(Math.min(concurrency, requests)), go = new CountDownLatch(1);
        List<Future<long[]>> pending = new ArrayList<>();
        try {
            for (int i = 0; i < requests; i++) pending.add(pool.submit(() -> {
                ready.countDown(); go.await(); long start = System.nanoTime();
                try {
                    HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString());
                    boolean ok = r.statusCode() == 200 && r.body().contains("\"code\":\"200\"") && r.body().contains("\"code\":\"" + code + "\"");
                    return new long[] {TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), ok ? 1 : 0, r.statusCode()};
                } catch (Exception e) { return new long[] {TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), 0, 0}; }
            }));
            if (!ready.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Clients did not start");
            long start = System.nanoTime(); go.countDown();
            List<Long> latency = new ArrayList<>(); int success = 0; Map<Long, Integer> statuses = new TreeMap<>();
            for (Future<long[]> f : pending) {
                long[] r = f.get(); latency.add(r[0]); success += r[1]; statuses.merge(r[2], 1, Integer::sum);
            }
            double seconds = (System.nanoTime() - start) / 1e9; Collections.sort(latency);
            String report = String.format(Locale.ROOT,
                "{\"scope\":\"HTTP multipart + real inference; verify enrolled count separately; same portrait repeated, not accuracy evaluation\",\"concurrency\":%d,\"requests\":%d,\"successes\":%d,\"elapsedSeconds\":%.3f,\"rps\":%.2f,\"p50Ms\":%d,\"p95Ms\":%d,\"p99Ms\":%d,\"maxMs\":%d}",
                concurrency, requests, success, seconds, requests / seconds, latency.get((int)Math.ceil(requests * .5)-1), latency.get((int)Math.ceil(requests * .95)-1), latency.get((int)Math.ceil(requests * .99)-1), latency.get(requests-1));
            System.out.println(report); System.err.println("HTTP status counts: " + statuses);
            if (success != requests) throw new IllegalStateException("Some requests did not match expected identity");
        } finally { go.countDown(); pool.shutdownNow(); }
    }
}
