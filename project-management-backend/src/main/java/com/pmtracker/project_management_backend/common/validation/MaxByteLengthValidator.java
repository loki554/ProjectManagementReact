package com.pmtracker.project_management_backend.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.nio.charset.StandardCharsets;

public class MaxByteLengthValidator implements ConstraintValidator<MaxByteLength, String> {

    private int max;

    @Override
    public void initialize(MaxByteLength constraint) {
        this.max = constraint.value();
    }

    // null пропускаем: обязательность поля — дело @NotBlank/@NotNull, как и у стандартного @Size.
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || value.getBytes(StandardCharsets.UTF_8).length <= max;
    }
}
