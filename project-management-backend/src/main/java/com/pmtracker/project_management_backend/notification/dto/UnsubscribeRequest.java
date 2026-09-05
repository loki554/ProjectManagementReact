package com.pmtracker.project_management_backend.notification.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Отписка по ссылке из письма (4.3). Токен в теле POST, а не в пути GET, и это не вкусовщина:
 * почтовые сканеры и превьюшники мессенджеров ходят по ссылкам из писем сами, и отписка,
 * срабатывающая на GET, срабатывала бы у них — человек переставал бы получать письма, ни на
 * что не нажав. Поэтому ссылка ведёт на страницу фронтенда, а действие происходит по кнопке.
 */
public record UnsubscribeRequest(@NotBlank String token) {
}
