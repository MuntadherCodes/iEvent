package iq.ievent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** One unambiguous log line so "email not working" is diagnosable at a glance. */
@Component
public class StartupInfoLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupInfoLogger.class);

    private final String mailMode;
    private final String mailHost;
    private final int mailPort;
    private final String mailFrom;
    private final String baseUrl;

    public StartupInfoLogger(@Value("${app.mail.mode}") String mailMode,
                             @Value("${spring.mail.host}") String mailHost,
                             @Value("${spring.mail.port}") int mailPort,
                             @Value("${app.mail.from}") String mailFrom,
                             @Value("${app.base-url}") String baseUrl) {
        this.mailMode = mailMode;
        this.mailHost = mailHost;
        this.mailPort = mailPort;
        this.mailFrom = mailFrom;
        this.baseUrl = baseUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("iEvent ready at {} | mail mode={} via {}:{} from={}{}",
                baseUrl, mailMode, mailHost, mailPort, mailFrom,
                "mailpit".equals(mailMode)
                        ? " — dev inbox UI: http://localhost:<MAILPIT_UI_PORT, default 8025>"
                        : "");
    }
}
