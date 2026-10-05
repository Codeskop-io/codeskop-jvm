package com.codeskop.server.integrations;

import com.codeskop.server.Codeskop;
import java.io.IOException;
import java.io.InterruptedIOException;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Outgoing calls through OkHttp (and Retrofit): {@code new OkHttpClient.Builder().addInterceptor(new CodeskopOkHttpInterceptor())}.
 */
public final class CodeskopOkHttpInterceptor implements Interceptor {
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request req = chain.request();
        long started = System.nanoTime();
        try {
            Response res = chain.proceed(req);
            Codeskop.recordOutgoing(req.method(), req.url().host(), req.url().encodedPath(), res.code(), (System.nanoTime() - started) / 1e6, null);
            return res;
        } catch (IOException e) {
            Codeskop.recordOutgoing(req.method(), req.url().host(), req.url().encodedPath(), null, (System.nanoTime() - started) / 1e6,
                e instanceof InterruptedIOException ? "timeout" : "network_error");
            throw e;
        }
    }
}
