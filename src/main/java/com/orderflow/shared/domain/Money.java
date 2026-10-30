package com.orderflow.shared.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Amount in a currency, always stored with the currency's minor-unit scale (e.g. 2 for EUR, 0 for JPY).
 * Amounts with more decimals than the currency allows are rejected rather than rounded.
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        if (amount == null) {
            throw new InvalidMoneyException("Amount is required");
        }
        if (currency == null) {
            throw new InvalidMoneyException("Currency is required");
        }
        int fractionDigits = currency.getDefaultFractionDigits();
        if (fractionDigits < 0) {
            throw new InvalidMoneyException("Currency " + currency + " is not supported");
        }
        if (amount.stripTrailingZeros().scale() > fractionDigits) {
            throw new InvalidMoneyException(
                    "Amount " + amount + " has more than " + fractionDigits + " decimal places for " + currency);
        }
        amount = amount.setScale(fractionDigits);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, toCurrency(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money multiply(int factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)), currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new InvalidMoneyException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }

    private static Currency toCurrency(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new InvalidMoneyException("Currency is required");
        }
        try {
            return Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new InvalidMoneyException("Unknown currency: " + currencyCode);
        }
    }
}
