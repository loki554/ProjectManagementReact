package com.pmtracker.project_management_backend.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Ограничение длины строки в БАЙТАХ UTF-8, а не в символах, как {@code @Size}.
 * <p>
 * Нужно ровно там, где ограничение задано внешним кодом именно в байтах — сейчас это пароль:
 * BCrypt (Spring Security 7) бросает {@code IllegalArgumentException("password cannot be more
 * than 72 bytes")} при хешировании более длинного пароля. {@code @Size(max = 72)} тут не спасает:
 * 40 символов кириллицы — это 80 байт UTF-8, то есть валидация бы прошла, а регистрация упала
 * бы 500-й ошибкой (BCrypt.checkpw при входе, наоборот, молча обрезает и не бросает — то есть
 * без этой проверки поведение регистрации и входа ещё и расходится).
 */
@Documented
@Constraint(validatedBy = MaxByteLengthValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxByteLength {

    int value();

    String message() default "must be at most {value} bytes long when encoded as UTF-8";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
