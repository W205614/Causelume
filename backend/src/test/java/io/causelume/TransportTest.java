package io.causelume;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

class TransportTest {
    @Test void capsChunkedResponseAndStalledBody() throws Exception {
        var http = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var release = new CountDownLatch(1);
        http.createContext("/large",exchange -> {
            exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()) { output.write(new byte[InternalClient.MAX_RESPONSE+1]); }
        });
        http.createContext("/slow",exchange -> {
            exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()) {
                output.write('{');
                output.flush();
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        });
        http.start();
        try {
            var env = new MockEnvironment().withProperty("BASE","http://127.0.0.1:"+http.getAddress().getPort()).withProperty("TOKEN","local-test");
            var client = new InternalClient(env,new ObjectMapper());
            var large = assertThrows(Problem.class,() -> client.call("BASE","TOKEN","/large",null,3));
            assertEquals(502,large.status);
            assertTrue(large.getMessage().contains("128 KiB"),large.getMessage());
            var slow = assertTimeoutPreemptively(java.time.Duration.ofSeconds(4),
                    () -> assertThrows(Problem.class,() -> client.call("BASE","TOKEN","/slow",null,1)));
            assertEquals(504,slow.status);
        } finally {
            release.countDown();
            http.stop(0);
        }
    }
}
