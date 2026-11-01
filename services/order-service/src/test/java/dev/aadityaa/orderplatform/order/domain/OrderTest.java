package dev.aadityaa.orderplatform.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static Order newOrder() {
        return Order.create("cust-1", "jane@example.com", "USD", UUID.randomUUID(),
                List.of(new OrderItem("SKU-A", 2, 1250), new OrderItem("SKU-B", 1, 500)));
    }

    @Test
    void createStartsPendingAndComputesTotalInMinorUnits() {
        Order order = newOrder();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getTotalAmountMinor()).isEqualTo(3000);
        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getId()).isNotNull();
    }

    @Test
    void createRejectsEmptyItems() {
        assertThatThrownBy(() -> Order.create("c", "e@x.com", "USD", UUID.randomUUID(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void totalOverflowFailsLoudlyInsteadOfWrapping() {
        List<OrderItem> items = List.of(new OrderItem("SKU-A", Integer.MAX_VALUE, Long.MAX_VALUE));

        assertThatThrownBy(() -> Order.create("c", "e@x.com", "USD", UUID.randomUUID(), items))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void pendingOrderCanBeConfirmed() {
        Order order = newOrder();

        assertThat(order.confirm()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void pendingOrderCanBeRejectedWithReason() {
        Order order = newOrder();

        assertThat(order.reject("OUT_OF_STOCK")).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
        assertThat(order.getFailureReason()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void pendingOrderCanBeCancelledWithReason() {
        Order order = newOrder();

        assertThat(order.cancel("DECLINED")).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getFailureReason()).isEqualTo("DECLINED");
    }

    @Test
    void terminalStateIsFinalAndLateEventsAreIgnored() {
        Order order = newOrder();
        order.confirm();

        assertThat(order.cancel("LATE_PAYMENT_FAILED")).isFalse();
        assertThat(order.reject("LATE_STOCK_FAILURE")).isFalse();
        assertThat(order.confirm()).isFalse();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getFailureReason()).isNull();
    }
}
