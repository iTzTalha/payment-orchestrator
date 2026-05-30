package com.bitforge.payments.orchestrator.dto;

import com.bitforge.payments.orchestrator.entity.PaymentMethod;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreatePaymentRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void validRequest_hasNoViolations() {
        CreatePaymentRequest request = new CreatePaymentRequest(
                "ord-1", 1000L, "USD", PaymentMethod.CARD);
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void blankMerchantReference_violatesNotBlank() {
        CreatePaymentRequest request = new CreatePaymentRequest(
                " ", 1000L, "USD", PaymentMethod.CARD);
        assertThat(fieldsWithViolations(request)).containsExactly("merchantReference");
    }

    @Test
    void merchantReferenceTooLong_violatesSize() {
        CreatePaymentRequest request = new CreatePaymentRequest(
                "x".repeat(65), 1000L, "USD", PaymentMethod.CARD);
        assertThat(fieldsWithViolations(request)).containsExactly("merchantReference");
    }

    @Test
    void missingAmount_violatesNotNull() {
        CreatePaymentRequest request = new CreatePaymentRequest(
                "ord-1", null, "USD", PaymentMethod.CARD);
        assertThat(fieldsWithViolations(request)).containsExactly("amountMinor");
    }

    @Test
    void zeroAmount_violatesPositive() {
        CreatePaymentRequest request = new CreatePaymentRequest(
                "ord-1", 0L, "USD", PaymentMethod.CARD);
        assertThat(fieldsWithViolations(request)).containsExactly("amountMinor");
    }

    @Test
    void negativeAmount_violatesPositive() {
        CreatePaymentRequest request = new CreatePaymentRequest(
                "ord-1", -1L, "USD", PaymentMethod.CARD);
        assertThat(fieldsWithViolations(request)).containsExactly("amountMinor");
    }

    @Test
    void lowercaseCurrency_violatesPattern() {
        CreatePaymentRequest request = new CreatePaymentRequest(
                "ord-1", 1000L, "usd", PaymentMethod.CARD);
        assertThat(fieldsWithViolations(request)).containsExactly("currency");
    }

    @Test
    void twoLetterCurrency_violatesPattern() {
        CreatePaymentRequest request = new CreatePaymentRequest(
                "ord-1", 1000L, "US", PaymentMethod.CARD);
        assertThat(fieldsWithViolations(request)).containsExactly("currency");
    }

    @Test
    void missingPaymentMethod_violatesNotNull() {
        CreatePaymentRequest request = new CreatePaymentRequest(
                "ord-1", 1000L, "USD", null);
        assertThat(fieldsWithViolations(request)).containsExactly("paymentMethod");
    }

    private static Set<String> fieldsWithViolations(CreatePaymentRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
