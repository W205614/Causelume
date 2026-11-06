package io.causelume;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:mail-outbox;DB_CLOSE_DELAY=-1","APP_ADMIN_PASSWORD=test-admin-secret",
    "APP_OPERATOR_PASSWORD=test-operator-secret","APP_SMTP_FROM=oncall@example.invalid","app.mail.poll-ms=600000",
    "spring.mail.properties.mail.smtp.timeout=1500","spring.mail.properties.mail.smtp.connectiontimeout=1500"})
class MailDeliveryTest {
    @Autowired Store store;
    @Autowired MailOutbox mail;
    static final ServerSocket smtp;
    static final AtomicInteger received=new AtomicInteger();
    static volatile String response="250 accepted";
    static {
        try {
            smtp=new ServerSocket(0,8,InetAddress.getLoopbackAddress());
            Thread.startVirtualThread(()->{
                while(!smtp.isClosed()){
                    try(var socket=smtp.accept()) {handle(socket);}
                    catch(IOException error){if(!smtp.isClosed())throw new UncheckedIOException(error);}
                }
            });
        } catch(IOException error){throw new UncheckedIOException(error);}
    }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry values){
        values.add("spring.mail.host",()->smtp.getInetAddress().getHostAddress());
        values.add("spring.mail.port",smtp::getLocalPort);
    }
    static void handle(Socket socket) throws IOException {
        var input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
        var output=new PrintWriter(socket.getOutputStream(),true,StandardCharsets.US_ASCII);
        output.print("220 local ESMTP\r\n"); output.flush();
        for(String line; (line=input.readLine())!=null;) {
            if(line.equals("DATA")){
                output.print("354 send\r\n");output.flush();
                while((line=input.readLine())!=null && !line.equals(".")) {}
                received.incrementAndGet();
                if(response.equals("disconnect"))return;
                output.print(response+"\r\n");
            }else if(line.equals("QUIT")){output.print("221 bye\r\n");output.flush();return;}
            else output.print("250 OK\r\n");
            output.flush();
        }
    }
    @AfterAll static void close() throws IOException {smtp.close();}
    long enqueue(){
        return store.insert("""
            insert into notifications(recipient_id,event_type,message,mail_to,mail_status)
            values ((select id from accounts where username='admin'),'TEST','local sink','recipient@example.invalid','PENDING')
            """);
    }
    String status(long id){return (String)store.one("select mail_status from notifications where id=?",id).get("mailStatus");}
    @Test void acceptanceDefiniteRejectionAndUnknownHaveDifferentOutcomes(){
        long ok=enqueue(); mail.deliver(); assertEquals("SENT",status(ok));
        response="451 try later";
        long transientId=enqueue();
        for(int attempt=1;attempt<=4;attempt++){
            mail.deliver();
            assertEquals(attempt==4?"FAILED":"RETRY",status(transientId));
            assertEquals(attempt,store.one("select attempts from notifications where id=?",transientId).get("attempts"));
            store.db.update("update notifications set next_attempt=current_timestamp where id=?",transientId);
        }
        response="550 denied";
        long rejected=enqueue(); mail.deliver();assertEquals("FAILED",status(rejected));
        response="disconnect";
        long unknown=enqueue();mail.deliver();assertEquals("UNKNOWN",status(unknown));
        int calls=received.get();mail.deliver();assertEquals(calls,received.get());
        store.db.update("update notifications set mail_status='SENDING' where id=?",ok);
        mail.recover();assertEquals("UNKNOWN",status(ok));mail.deliver();assertEquals(calls,received.get());
    }
}
