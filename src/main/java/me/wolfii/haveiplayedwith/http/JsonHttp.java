package me.wolfii.haveiplayedwith.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class JsonHttp {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private JsonHttp() {
    }

    public static HttpResponse<String> get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(12))
            .header("Accept", "application/json")
            .header("User-Agent", "HaveIPlayedWith/1.0 (https://github.com/JustAlittleWolf/HaveIPlayedWith)")
            .GET()
            .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
