package me.wolfii.haveiplayedwith.http;

import me.wolfii.haveiplayedwith.ModLog;

import java.net.http.HttpResponse;

/**
 * One rate limited JSON service. Every request waits for a permit and never throws:
 * timeouts, rate limits and error statuses all come back as
 * {@link JsonAnswer.Unavailable}.
 *
 * <p>All endpoints of a service share one instance so they also share its request budget.
 */
public final class JsonApi {
    private final String name;
    private final RateLimiter limiter;

    public JsonApi(String name, RateLimiter limiter) {
        this.name = name;
        this.limiter = limiter;
    }

    public JsonAnswer get(String url) {
        try {
            limiter.acquire();
            HttpResponse<String> response = JsonHttp.get(url);
            int status = response.statusCode();
            if (status == 204 || status == 404) {
                return JsonAnswer.MISSING;
            }
            if (status / 100 != 2) {
                ModLog.LOGGER.debug("{} returned {} for {}", name, status, url);
                return JsonAnswer.UNAVAILABLE;
            }
            return new JsonAnswer.Body(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return JsonAnswer.UNAVAILABLE;
        } catch (Exception e) {
            ModLog.LOGGER.debug("{} request failed: {}", name, url, e);
            return JsonAnswer.UNAVAILABLE;
        }
    }
}
