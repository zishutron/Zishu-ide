package com.zishu.ide.core.validation;

/** Thrown when an operation is refused because validation found errors. */
public final class ValidationException extends Exception {
    private final ValidationReport report;

    public ValidationException(ValidationReport report) {
        super(report.errors().isEmpty() ? "Validation failed" : report.errors().get(0).message);
        this.report = report;
    }

    public ValidationReport getReport() {
        return report;
    }
}
