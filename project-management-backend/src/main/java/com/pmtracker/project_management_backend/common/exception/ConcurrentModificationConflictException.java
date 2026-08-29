package com.pmtracker.project_management_backend.common.exception;

/**
 * Сущность изменили с тех пор, как её прочитал клиент (3.4).
 * <p>
 * Бросается в двух разных ситуациях, которые для пользователя выглядят одинаково:
 * <ul>
 *   <li>версия в запросе не совпала с текущей — то есть форму открыли до чужого сохранения
 *       (типичный случай: две вкладки, десять минут между открытием и отправкой);</li>
 *   <li>Hibernate не нашёл строку по её версии при UPDATE — два запроса пересеклись уже
 *       внутри транзакций, см. обработчик ObjectOptimisticLockingFailureException.</li>
 * </ul>
 * Имя длиннее обычного намеренно: {@code ConcurrentModificationException} есть в
 * java.util, и одноимённый класс в этом пакете гарантированно был бы когда-нибудь
 * импортирован по ошибке.
 */
public class ConcurrentModificationConflictException extends RuntimeException {

    public ConcurrentModificationConflictException() {
        super("The item was changed by someone else while you were editing it");
    }
}
