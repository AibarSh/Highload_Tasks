package notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal REST/JSON client used only as the measurement baseline (no retries). */
public final class RestClient {
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI uri;
    private final Duration timeout;

    public RestClient(String host, int port, Duration timeout) {
        this.uri = URI.create("http://" + host + ":" + port + "/v1/notification-events");
        this.timeout = timeout;
    }

    public Dto.Ack submit(Dto.Event event) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(event))).build();
        HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 202) throw new IOException("HTTP " + resp.statusCode());
        return mapper.readValue(resp.body(), Dto.Ack.class);
    }
}
