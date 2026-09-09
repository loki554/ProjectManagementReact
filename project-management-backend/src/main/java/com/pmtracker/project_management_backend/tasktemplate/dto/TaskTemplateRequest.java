package com.pmtracker.project_management_backend.tasktemplate.dto;

import com.pmtracker.project_management_backend.task.TaskUrgency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Тело заведения и правки шаблона задачи (4.13). Один DTO на оба и полная замена, а не
 * PATCH, — как у спринта: шаблон правят в форме целиком, и снятый заголовок обязан доехать
 * снятым.
 *
 * <p>Чек-лист приезжает здесь же, списком строк в нужном порядке, а не отдельными
 * запросами на каждый пункт. Шаблон правят редко и целиком: открыли форму, дописали два
 * шага, поменяли местами три — и сохранили. Пер-пунктовые ручки означали бы десяток
 * запросов на одно такое редактирование и вопрос «что показывать, если третий из них
 * не прошёл».
 *
 * @param name        имя шаблона в списке выбора; единственное обязательное поле
 * @param title       заголовок будущей задачи; null — форма оставит своё поле как есть
 * @param items       пункты чек-листа по порядку; пустой список — шаблон без чек-листа
 */
public record TaskTemplateRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 255) String title,
        @Size(max = 20000) String description,
        TaskUrgency urgency,
        UUID tagId,
        UUID categoryId,
        // Потолок на число пунктов: чек-лист из сотни шагов — это уже не задача, а проект,
        // и разбивать его надо задачами, а не строками. Ограничение защищает заодно от
        // формы, отправленной скриптом.
        @Size(max = 50) List<@Valid TaskTemplateItemRequest> items
) {
}
