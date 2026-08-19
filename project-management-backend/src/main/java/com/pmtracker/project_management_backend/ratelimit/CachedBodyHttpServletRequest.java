package com.pmtracker.project_management_backend.ratelimit;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.util.StreamUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Тело HTTP-запроса читается из сокета ровно один раз, поэтому фильтру, которому нужно
 * заглянуть внутрь (нам — за email для ключа лимита), приходится вычитать его целиком в
 * память и дальше по цепочке отдавать копию — иначе контроллер получит пустой поток.
 *
 * ContentCachingRequestWrapper из Spring для этого не годится: он кеширует то, что уже
 * прочитали ниже по цепочке, то есть до вызова filterChain.doFilter его буфер пуст.
 *
 * Оборачивать так можно только маленькие запросы — см. ограничение по Content-Length
 * в AuthRateLimitFilter; на multipart-загрузки это не распространяется.
 */
class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = StreamUtils.copyToByteArray(request.getInputStream());
    }

    byte[] getCachedBody() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(body);
        return new ServletInputStream() {

            @Override
            public boolean isFinished() {
                return buffer.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                throw new UnsupportedOperationException("Async reads are not supported for cached request bodies");
            }

            @Override
            public int read() {
                return buffer.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        String encoding = getCharacterEncoding();
        Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), charset));
    }
}
