package dev.aadityaa.orderplatform.order.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateOrderRequest(
        @NotBlank @Size(max = 64) String customerId,
        @NotBlank @Email @Size(max = 255) String customerEmail,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a 3-letter ISO-4217 code, e.g. USD") String currency,
        @NotEmpty @Size(max = 100) List<@Valid Item> items) {

    public record Item(
            @NotBlank @Size(max = 64) String sku,
            @Min(1) @Max(1000) int quantity,
            @Positive long unitPriceMinor) {
    }
}
