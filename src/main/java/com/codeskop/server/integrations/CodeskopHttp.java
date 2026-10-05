package com.codeskop.server.integrations;

import com.codeskop.server.Codeskop;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;

/**
 * Outgoing calls through {@code java.net.http.HttpClient}:
 * {@code HttpResponse<String> r = CodeskopHttp.send(client, request, BodyHandlers.ofString());}
 */
public final class CodeskopHttp {
    private CodeskopHttp() {}

    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        long started = System.nanoTime();
        String host = request.uri().getHost();
        String path = request.uri().getRawPath();
        try {
            HttpResponse<T> res = client.send(request, handler);
            Codeskop.recordOutgoing(request.method(), host, path, res.statusCode(), (System.nanoTime() - started) / 1e6, null);
            return res;
        } catch (IOException e) {
            Codeskop.recordOutgoing(request.method(), host, path, null, (System.nanoTime() - started) / 1e6,
                e instanceof HttpTimeoutException ? "timeout" : "network_error");
            throw e;
        }
    }
}
