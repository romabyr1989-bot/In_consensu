package ru.example.cus.common.error;

/**
 * One entry of the {@code errors[]} array of a validation problem detail (§9).
 *
 * <p>The rejected value is deliberately not carried: request payloads may contain personal data, which must never leak
 * into error responses or logs (NFR-3).
 */
public record ValidationErrorItem(String field, String message) {}
