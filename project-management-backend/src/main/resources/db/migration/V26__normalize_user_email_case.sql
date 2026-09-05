-- Канонизация почтовых адресов пользователей (см. EmailNormalizer).
--
-- Регистрация писала адрес ровно так, как его прислал клиент, а уникальный индекс по
-- users.email регистрозависим — поэтому Ivan@Company.com и ivan@company.com заводили два
-- аккаунта на один почтовый ящик: вход «не тем регистром» отвечал «неверный пароль»,
-- письмо о сбросе уходило в чужую из двух записей, а приглашения в проект (они всегда
-- хранились в нижнем регистре) не находили уже существующего пользователя.
--
-- Приложение теперь нормализует адрес на входе, но одного кода мало: старые строки уже
-- лежат в смешанном регистре, и любой будущий путь записи, забывший про нормализацию,
-- вернёт ту же ошибку. Поэтому здесь и разовый бэкофилл, и индекс, который делает
-- инвариант обязанностью БД.

-- Дубли по регистру должны развалить миграцию, а не «схлопнуться» молча: за каждой такой
-- парой стоят два аккаунта с разными паролями и, возможно, разным членством в проектах.
-- Выбрать из них выживший может только человек, и лучше остановить выкат, чем потерять
-- данные. Сообщение печатает сами адреса, чтобы разбираться было по чему.
DO $$
DECLARE
    duplicates text;
BEGIN
    SELECT string_agg(lower_email || ' (' || cnt || ')', ', ')
      INTO duplicates
      FROM (SELECT lower(email) AS lower_email, count(*) AS cnt
              FROM users
             GROUP BY lower(email)
            HAVING count(*) > 1) d;

    IF duplicates IS NOT NULL THEN
        RAISE EXCEPTION
            'Cannot normalize user emails: several accounts differ only by letter case (%). '
            'Merge or delete the duplicates by hand, then re-run the migration.', duplicates;
    END IF;
END $$;

UPDATE users
   SET email = lower(email)
 WHERE email <> lower(email);

-- Функциональный уникальный индекс, а не просто надежда на нормализацию в коде: он же
-- страхует и project_invitations, которые ищут пользователя по адресу в нижнем регистре.
-- Существующий уникальный constraint по email остаётся — после бэкофилла оба согласованы.
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));
