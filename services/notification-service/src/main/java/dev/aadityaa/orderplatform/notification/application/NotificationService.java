package dev.aadityaa.orderplatform.notification.application;

import dev.aadityaa.orderplatform.notification.email.EmailProvider;
import dev.aadityaa.orderplatform.notification.persistence.NotificationRepository;
import dev.aadityaa.orderplatform.platform.messaging.EventContext;
import dev.aadityaa.orderplatform.platform.messaging.ProcessedEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final EmailProvider email;
    private final ProcessedEventRepository processed;
    private final TransactionTemplate tx;

    public NotificationService(NotificationRepository notifications, EmailProvider email,
                               ProcessedEventRepository processed, TransactionTemplate tx) {
        this.notifications = notifications;
        this.email = email;
        this.processed = processed;
        this.tx = tx;
    }

    /**
     * Deliberately NOT @Transactional: the external call must not hold a database connection.
     * Order: (1) already notified? (2) send with a stable idempotency key (3) short transaction to record.
     * A provider failure throws, so the shared error handler retries and eventually dead-letters.
     */
    public void notifyCustomer(EventContext ctx, String type, String recipient, NotificationTemplates.Message message) {
        if (notifications.exists(ctx.orderId(), type)) {
            log.info("Order {} already notified for {}; ignoring event {}", ctx.orderId(), type, ctx.eventId());
            return;
        }

        email.send("notif-" + ctx.orderId() + "-" + type, recipient, message.subject(), message.body());

        tx.executeWithoutResult(status -> {
            if (!processed.markProcessed(ctx.eventId())) {
                log.info("Duplicate event {} ignored", ctx.eventId());
                return;
            }
            notifications.insertIfAbsent(ctx.orderId(), type, recipient, message.subject(), message.body());
            log.info("Notified {} about order {} ({})", recipient, ctx.orderId(), type);
        });
    }
}
