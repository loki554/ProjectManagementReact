package com.pmtracker.project_management_backend.tasktemplate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface TaskTemplateRepository extends JpaRepository<TaskTemplate, UUID> {

    /**
     * Шаблоны проекта по алфавиту. Этот порядок и есть порядок выпадающего списка при
     * заведении задачи: у шаблона нет ни статуса, ни срока, по которым его можно было бы
     * ранжировать осмысленнее, а «сначала недавно созданные» перетасовывало бы список ровно
     * тогда, когда человек привык брать в нём третий пункт сверху.
     *
     * <p>lower() в сортировке — чтобы «релиз» и «Релиз» стояли рядом, а не через весь
     * список: Postgres сортирует по кодам символов, и заглавные буквы иначе уезжают вперёд.
     *
     * <p>join fetch на тэг и категорию: их разворачивает каждая строка ответа.
     */
    @Query("""
            select t from TaskTemplate t
            left join fetch t.tag
            left join fetch t.category
            where t.project.id = :projectId
            order by lower(t.name) asc
            """)
    List<TaskTemplate> findByProjectId(UUID projectId);

    boolean existsByProjectIdAndName(UUID projectId, String name);

    /**
     * Число пунктов в каждом шаблоне проекта — одним запросом на весь список (тот же приём,
     * что у счётчиков категорий и спринтов). Сами пункты в списке не нужны: там выбирают
     * шаблон по имени, а «10 пунктов» — подпись под ним.
     */
    @Query("""
            select i.template.id as templateId, count(i.id) as itemCount
            from TaskTemplateItem i
            where i.template.project.id = :projectId
            group by i.template.id
            """)
    List<TemplateItemCount> countItemsByProjectId(UUID projectId);

    interface TemplateItemCount {
        UUID getTemplateId();

        long getItemCount();
    }
}
