package me.lucko.bytebin;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke tests run against an already running bytebin instance.
 * Set the base url with -Dbytebin.url=... (default http://localhost:8080).
 */
class SmokeIT {

    private static final String BASE_URL = System.getProperty("bytebin.url", "http://localhost:8080").replaceAll("/+$", "");
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void healthEndpointIsOk() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(BASE_URL + "/health")).GET());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"ok\""), response.body());
    }

    @Test
    void postThenGetRoundTrip() throws Exception {
        String content = "bytebin smoke test " + System.nanoTime();

        HttpResponse<String> post = send(HttpRequest.newBuilder(URI.create(BASE_URL + "/post"))
                .header("Content-Type", "text/plain")
                .header("User-Agent", "bytebin-smoke-test")
                .POST(HttpRequest.BodyPublishers.ofString(content)));
        assertEquals(201, post.statusCode(), post.body());

        Matcher matcher = Pattern.compile("\"key\"\\s*:\\s*\"([a-zA-Z0-9]+)\"").matcher(post.body());
        assertTrue(matcher.find(), "no key in response: " + post.body());
        String key = matcher.group(1);

        HttpResponse<String> get = send(HttpRequest.newBuilder(URI.create(BASE_URL + "/" + key))
                .header("User-Agent", "bytebin-smoke-test")
                .GET());
        assertEquals(200, get.statusCode());
        assertEquals(content, get.body());
    }
}
