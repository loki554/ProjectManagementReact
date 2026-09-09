package com.pmtracker.project_management_backend.tasktemplate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TaskTemplateItemRepository extends JpaRepository<TaskTemplateItem, UUID> {

    List<TaskTemplateItem> findByTemplateIdOrderByPositionAsc(UUID templateId);

    /**
     * Стереть все пункты шаблона — первая половина «переписать чек-лист целиком»
     * (TaskTemplateService.update). Derived-delete, а не bulk-запрос: пунктов в шаблоне
     * десяток, и загрузить их ради удаления дешевле, чем заводить нативный DELETE в обход
     * persistence context, из-за которого следом вставленные пункты пришлось бы
     * согласовывать с сессией вручную.
     */
    void deleteByTemplateId(UUID templateId);
}
