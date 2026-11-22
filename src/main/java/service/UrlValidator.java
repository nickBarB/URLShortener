package service;

import java.net.URI;
import java.net.URISyntaxException;

public final class UrlValidator {

    private static final int MAX_LENGTH = 2048;

    public String validate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException("invalid_url", "Введите URL, например: https://example.com/page");
        }
        String url = raw.trim();
        if (url.length() > MAX_LENGTH) {
            throw new BusinessException("invalid_url", "URL слишком длинный: допустимо не более " + MAX_LENGTH + " символов");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new BusinessException("invalid_url", "Некорректный URL. Пример правильного адреса: https://example.com/page");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new BusinessException("invalid_url", "Допустимы только ссылки, начинающиеся с http:// или https://");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BusinessException("invalid_url", "В URL не указан адрес сайта. Пример: https://example.com");
        }
        if (uri.getPort() > 65535 || uri.getPort() == 0) {
            throw new BusinessException("invalid_url", "Порт в URL должен быть от 1 до 65535");
        }
        return url;
    }
}
