package com.orderflow.shared.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalizesAmountToCurrencyScale() {
        assertThat(Money.of(new BigDecimal("10"), "EUR").amount()).isEqualByComparingTo("10.00")
                .hasScaleOf(2);
        assertThat(Money.of(new BigDecimal("100"), "JPY").amount()).hasScaleOf(0);
    }

    @Test
    void equalAmountsWithDifferentScaleAreEqual() {
        assertThat(Money.of(new BigDecimal("10"), "EUR")).isEqualTo(Money.of(new BigDecimal("10.00"), "EUR"));
    }

    @Test
    void acceptsTrailingZerosBeyondCurrencyScale() {
        assertThat(Money.of(new BigDecimal("10.500"), "EUR").amount()).isEqualTo(new BigDecimal("10.50"));
    }

    @Test
    void rejectsMoreDecimalsThanCurrencyAllows() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("10.005"), "EUR"))
                .isInstanceOf(InvalidMoneyException.class)
                .hasMessageContaining("decimal places");
        assertThatThrownBy(() -> Money.of(new BigDecimal("100.5"), "JPY"))
                .isInstanceOf(InvalidMoneyException.class);
    }

    @Test
    void rejectsUnknownOrMissingCurrency() {
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "XYZ"))
                .isInstanceOf(InvalidMoneyException.class)
                .hasMessageContaining("Unknown currency");
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, " "))
                .isInstanceOf(InvalidMoneyException.class);
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, null))
                .isInstanceOf(InvalidMoneyException.class);
    }

    @Test
    void rejectsCurrencyWithoutMinorUnits() {
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "XXX"))
                .isInstanceOf(InvalidMoneyException.class)
                .hasMessage("Currency XXX is not supported");
    }

    @Test
    void rejectsMissingAmount() {
        assertThatThrownBy(() -> Money.of(null, "EUR"))
                .isInstanceOf(InvalidMoneyException.class);
    }

    @Test
    void addsAmountsInSameCurrency() {
        Money sum = Money.of(new BigDecimal("1.10"), "EUR").add(Money.of(new BigDecimal("2.25"), "EUR"));

        assertThat(sum).isEqualTo(Money.of(new BigDecimal("3.35"), "EUR"));
    }

    @Test
    void rejectsAddingDifferentCurrencies() {
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "EUR").add(Money.of(BigDecimal.ONE, "USD")))
                .isInstanceOf(InvalidMoneyException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void multipliesByQuantity() {
        assertThat(Money.of(new BigDecimal("19.99"), "EUR").multiply(3))
                .isEqualTo(Money.of(new BigDecimal("59.97"), "EUR"));
    }

    @Test
    void reportsWhetherPositive() {
        assertThat(Money.of(new BigDecimal("0.01"), "EUR").isPositive()).isTrue();
        assertThat(Money.of(BigDecimal.ZERO, "EUR").isPositive()).isFalse();
        assertThat(Money.of(new BigDecimal("-1"), "EUR").isPositive()).isFalse();
    }
}
