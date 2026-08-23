package com.pmtracker.project_management_backend.mail;

/**
 * «На этот адрес пришла попытка регистрации, а аккаунт уже есть». Единственный способ
 * сообщить об этом, не отвечая по-разному в HTTP (см. AuthService.register): узнать о
 * существовании аккаунта должен владелец ящика, а не тот, кто отправил форму.
 */
public record AccountAlreadyExistsEmailRequestedEvent(String email) {
}
