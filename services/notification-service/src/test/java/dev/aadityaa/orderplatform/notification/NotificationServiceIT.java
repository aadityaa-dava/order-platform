package dev.aadityaa.orderplatform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.aadityaa.orderplatform.notification.application.NotificationService;
import dev.aadityaa.orderplatform.notification.application.NotificationTemplates;
import dev.aadityaa.orderplatform.notification.email.EmailProviderException;
import dev.aadityaa.orderplatform.notification.email.EmailSettings;
import dev.aadityaa.orderplatform.notification.email.SimulatedEmailProvider;
import dev.aadityaa.orderplatform.platform.messaging.EventContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class NotificationServiceIT extends AbstractNotificationIT {

    private static final String TYPE = "ORDER_CONFIRMED";

    @Autowired NotificationService service;
    @Autowired SimulatedEmailProvider provider;
    @Autowired EmailSettings settings;
    @Autowired JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE notifications, processed_events").update();
        settings.setFailureRate(0);
        settings.setMinLatencyMs(0);
        settings.setMaxLatencyMs(0);
    }

    @Test
    void sendsOneEmailAndRecordsTheNotification() {
        EventContext ctx = newContext();
        int before = provider.sentCount();

        service.notifyCustomer(ctx, TYPE, "jane@example.com", message(ctx));

        assertThat(notificationCount(ctx.orderId())).isEqualTo(1);
        assertThat(provider.sentCount() - before).isEqualTo(1);
    }

    @Test
    void redeliveredEventSendsNoSecondEmail() {
        EventContext ctx = newContext();
        int before = provider.sentCount();

        service.notifyCustomer(ctx, TYPE, "jane@example.com", message(ctx));
        service.notifyCustomer(ctx, TYPE, "jane@example.com", message(ctx));

        assertThat(notificationCount(ctx.orderId())).isEqualTo(1);
        assertThat(provider.sentCount() - before).isEqualTo(1);
    }

    @Test
    void sameOrderAndOutcomeUnderADifferentEventIdSendsOnlyOnce() {
        UUID orderId = UUID.randomUUID();
        int before = provider.sentCount();

        var first = new EventContext(UUID.randomUUID(), orderId, UUID.randomUUID());
        var second = new EventContext(UUID.randomUUID(), orderId, UUID.randomUUID());
        service.notifyCustomer(first, TYPE, "jane@example.com", message(first));
        service.notifyCustomer(second, TYPE, "jane@example.com", message(second));

        assertThat(notificationCount(orderId)).isEqualTo(1);
        assertThat(provider.sentCount() - before).isEqualTo(1);
    }

    @Test
    void providerFailureLeavesNoTraceSoTheRetryStillSendsTheEmail() {
        EventContext ctx = newContext();
        settings.setFailureRate(1.0);

        assertThatThrownBy(() -> service.notifyCustomer(ctx, TYPE, "jane@example.com", message(ctx)))
                .isInstanceOf(EmailProviderException.class);

        // no notification AND no processed-event marker: otherwise the retry would be skipped as a duplicate
        assertThat(notificationCount(ctx.orderId())).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM processed_events").query(Long.class).single()).isZero();

        settings.setFailureRate(0);
        service.notifyCustomer(ctx, TYPE, "jane@example.com", message(ctx)); // the retry

        assertThat(notificationCount(ctx.orderId())).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateEventsSendExactlyOneEmail() throws Exception {
        EventContext ctx = newContext();
        int before = provider.sentCount();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                service.notifyCustomer(ctx, TYPE, "jane@example.com", message(ctx));
                return null;
            }));
        }
        start.countDown();
        for (Future<Void> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(notificationCount(ctx.orderId())).isEqualTo(1);
        assertThat(provider.sentCount() - before).isEqualTo(1);
    }

    private static EventContext newContext() {
        return new EventContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    private static NotificationTemplates.Message message(EventContext ctx) {
        return NotificationTemplates.confirmed(ctx.orderId(), 2500, "USD");
    }

    private long notificationCount(UUID orderId) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE order_id = :id").param("id", orderId)
                .query(Long.class).single();
    }
}
