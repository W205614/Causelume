package io.causelume;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class InternalClient {
    private final Environment env;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    InternalClient(Environment env, ObjectMapper json) {
        this.env = env;
        this.json = json;
    }

    JsonNode call(String baseVariable, String tokenVariable, String path, Map<String,?> body, int timeout) {
        String base = env.getProperty(baseVariable);
        String token = env.getProperty(tokenVariable);
        if (base == null || base.isBlank() || token == null || token.isBlank()) throw new Problem(503,"内部服务尚未配置");
        try {
            var builder = HttpRequest.newBuilder(URI.create(base+path)).header("Authorization","Bearer "+token)
                .timeout(Duration.ofSeconds(timeout));
            if (body != null) builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response = http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String reason = switch(response.statusCode()) {
                    case 422 -> "证据不足或服务尚未登记诊断工具";
                    case 504 -> "内部服务响应超时";
                    default -> "内部服务返回 HTTP "+response.statusCode();
                };
                throw new Problem(response.statusCode()==504 ? 504 : 502,reason);
            }
            JsonNode node = json.readTree(response.body());
            if (node == null || !node.isObject()) throw new Problem(502,"内部服务响应格式无效");
            return node;
        } catch (IllegalArgumentException e) {
            throw new Problem(503,"内部服务地址或凭据格式无效");
        } catch (HttpTimeoutException e) {
            throw new Problem(504,"内部服务响应超时");
        } catch (IOException e) {
            throw new Problem(502,"无法连接内部服务");
        } catch (JacksonException e) {
            throw new Problem(502,"内部服务返回无效 JSON");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Problem(503,"内部调用中断");
        }
    }
}
