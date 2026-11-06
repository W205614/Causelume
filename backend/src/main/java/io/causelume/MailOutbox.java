package io.causelume;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class MailOutbox {
    private static final int[] RETRY_SECONDS={10,60,300};
    final Store store;
    final JavaMailSender sender;
    final String from;
    private volatile boolean ready;

    MailOutbox(Store store, ObjectProvider<JavaMailSender> sender, Environment env) {
        this.store=store;
        this.sender=sender.getIfAvailable();
        this.from=env.getProperty("APP_SMTP_FROM","");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        // A process can die after SMTP accepted DATA but before the local commit.
        store.db.update("update notifications set mail_status='UNKNOWN',last_error='INTERRUPTED' where mail_status='SENDING'");
        ready=true;
    }

    @Scheduled(fixedDelayString="${app.mail.poll-ms:1000}")
    void deliver() {
        if(!ready || sender==null || from.isBlank()) return;
        var due=store.rows("""
            select n.id,n.ticket_id,n.message,n.mail_to,n.attempts from notifications n
            left join tickets k on k.id=n.ticket_id left join services s on s.id=k.service_id
            where n.mail_status in ('PENDING','RETRY') and n.next_attempt<=current_timestamp
            and (s.maintenance_until is null or s.maintenance_until<=current_timestamp)
            order by n.next_attempt,n.id limit 10
            """);
        for(var row:due) send(row);
    }

    private void send(Map<String,Object> row) {
        long id=(long)row.get("id");
        int attempts=((Number)row.get("attempts")).intValue()+1;
        int claimed=store.db.update("""
            update notifications set mail_status='SENDING',claimed_at=current_timestamp,attempts=attempts+1
            where id=? and attempts=? and mail_status in ('PENDING','RETRY')
            """,id,attempts-1);
        if(claimed==0) return;
        var message=new SimpleMailMessage();
        message.setFrom(from);
        message.setTo((String)row.get("mailTo"));
        message.setSubject("Causelume · 工单 #"+row.get("ticketId"));
        message.setText((String)row.get("message"));
        try {
            sender.send(message);
        } catch(MailException error) {
            String category=classify(error);
            if(category.equals("TRANSIENT") && attempts<=RETRY_SECONDS.length) {
                var next=OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(RETRY_SECONDS[attempts-1]);
                store.db.update("update notifications set mail_status='RETRY',next_attempt=?,last_error=? where id=? and mail_status='SENDING'",next,category,id);
            } else {
                store.db.update("update notifications set mail_status=?,last_error=? where id=? and mail_status='SENDING'",
                    category.equals("UNKNOWN") ? "UNKNOWN" : "FAILED",category,id);
            }
            return;
        }
        store.db.update("update notifications set mail_status='SENT',last_error=null where id=? and mail_status='SENDING'",id);
    }

    private static String classify(Throwable error) {
        if(error instanceof MailAuthenticationException) return "REJECTED";
        if(error instanceof MailSendException send && send.getMessageExceptions().length>0)
            return classify(send.getMessageExceptions()[0]);
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof ConnectException || cause instanceof UnknownHostException) return "TRANSIENT";
            int reply;
            if(cause instanceof org.eclipse.angus.mail.smtp.SMTPAddressFailedException rejected) reply=rejected.getReturnCode();
            else if(cause instanceof org.eclipse.angus.mail.smtp.SMTPSendFailedException rejected) reply=rejected.getReturnCode();
            else continue;
            if(reply>=400 && reply<500) return "TRANSIENT";
            if(reply>=500 && reply<600) return "REJECTED";
        }
        return "UNKNOWN";
    }
}
