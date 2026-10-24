package dev.svcmon.hub.alerts;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import dev.svcmon.hub.model.AlertChannel;
import dev.svcmon.hub.model.AlertType;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;

/**
 * Plain-text email over SMTP. STARTTLS is the default and is required (the connection is refused rather than
 * silently falling back to clear text); "SSL" is implicit TLS; "NONE" is for a trusted internal relay.
 * Server certificates are checked against the host name.
 */
@Component
class EmailSender implements AlertSender {

    @Override
    public AlertType type() {
        return AlertType.EMAIL;
    }

    @Override
    public void send(AlertChannel channel, AlertEvent event, String hubId) throws AlertDeliveryException {
        Map<String, Object> c = channel.config();
        String host = String.valueOf(c.get("smtpHost"));
        int port = c.get("smtpPort") instanceof Number n ? n.intValue() : 587;
        String security = c.get("security") == null ? "STARTTLS" : c.get("security").toString().toUpperCase(Locale.ROOT);
        String username = c.get("username") == null ? "" : c.get("username").toString();
        String password = c.get("password") == null ? "" : c.get("password").toString();

        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        switch (security) {
            case "STARTTLS" -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
                props.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            case "SSL" -> {
                props.put("mail.smtp.ssl.enable", "true");
                props.put("mail.smtp.ssl.checkserveridentity", "true");
            }
            default -> {
                // NONE: clear text, for a trusted relay only
            }
        }
        Authenticator authenticator = null;
        if (!username.isBlank()) {
            props.put("mail.smtp.auth", "true");
            authenticator = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                }
            };
        }

        try {
            MimeMessage message = new MimeMessage(Session.getInstance(props, authenticator));
            message.setFrom(new InternetAddress(String.valueOf(c.get("from"))));
            List<?> to = c.get("to") instanceof List<?> l ? l : List.of();
            message.setRecipients(Message.RecipientType.TO,
                    InternetAddress.parse(String.join(",", to.stream().map(Object::toString).toList()), true));
            message.setSubject(AlertFormat.title(event), "UTF-8");
            message.setSentDate(new Date());
            message.setText(AlertFormat.plainBody(event, hubId), "UTF-8");
            Transport.send(message);
        } catch (AuthenticationFailedException e) {
            throw new AlertDeliveryException("The mail server refused the login", false);
        } catch (MessagingException e) {
            String reason = e.getMessage() == null ? "" : ": " + AlertFormat.truncate(AlertFormat.oneLine(e.getMessage()), 200);
            throw new AlertDeliveryException("Could not send the email" + reason, true);
        }
    }
}
