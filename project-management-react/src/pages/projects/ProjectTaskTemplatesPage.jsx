import { GripVertical, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useParams } from 'react-router-dom'
import { useCategories } from '../../api/categoriesQueries'
import { useProjectBySlug, useProjectMembers } from '../../api/projectsQueries'
import { useTags } from '../../api/tagsQueries'
import {
  useCreateTaskTemplate,
  useDeleteTaskTemplate,
  useTaskTemplate,
  useTaskTemplates,
  useUpdateTaskTemplate,
} from '../../api/taskTemplatesQueries'
import {
  Field,
  inputClass,
  primaryButtonClass,
  secondaryButtonClass,
} from '../../components/ui/FormKit'
import { TASK_URGENCIES, canWriteInProject } from '../../lib/constants'
import { getLocalizedErrorMessage } from '../../lib/errorMessage'
import { useAuthStore } from '../../stores/authStore'

const EMPTY_FORM = {
  name: '',
  title: '',
  description: '',
  urgency: '',
  tagId: '',
  categoryId: '',
}

/**
 * Шаблоны задач проекта (4.13).
 *
 * Форма здесь на useState, а не на react-hook-form с zod, как остальные формы приложения, —
 * и это единственное отклонение стоит объяснить. Половина этой формы не поля, а список
 * пунктов чек-листа, который добавляют, стирают и переписывают по одному; react-hook-form
 * умеет это через useFieldArray, но связка «массив полей + zod-схема + перерисовка при
 * каждом нажатии» здесь дороже, чем сам список из десяти строк. Валидировать при этом
 * нечего, кроме непустого имени: остальные поля необязательны по определению шаблона.
 */
export function ProjectTaskTemplatesPage() {
  const { t } = useTranslation()
  const { projectSlug } = useParams()
  const currentUser = useAuthStore((state) => state.user)

  const { data: project } = useProjectBySlug(projectSlug)
  const projectId = project?.id
  const { data: members } = useProjectMembers(projectId)
  const { data: templates, isLoading, isError, error } = useTaskTemplates(projectId)
  const { data: tags } = useTags(projectId)
  const { data: categories } = useCategories(projectId)

  const createTemplate = useCreateTaskTemplate(projectId)
  const updateTemplate = useUpdateTaskTemplate(projectId)
  const deleteTemplate = useDeleteTaskTemplate(projectId)

  // ADMIN, а не OWNER, как у тэгов и категорий: шаблон ничего не меняет в уже заведённых
  // задачах, он только заполняет пустую форму (см. TaskTemplateService).
  const myMembership = members?.find((member) => member.userId === currentUser?.id)
  const canManage = canWriteInProject(project, myMembership?.role, 'ADMIN')

  // Какой шаблон открыт на правку: null — форма закрыта, 'new' — заводим новый.
  const [editingId, setEditingId] = useState(null)
  const [form, setForm] = useState(EMPTY_FORM)
  const [items, setItems] = useState([])
  const [itemDraft, setItemDraft] = useState('')

  // Пункты чек-листа приезжают только с полным шаблоном — в списке их нет (там есть их
  // число). Поэтому правка ждёт загрузки: пока пункты не приехали, кнопка сохранения
  // выключена, иначе «сохранил, не дождавшись» стёрло бы чек-лист.
  const editingExisting = editingId && editingId !== 'new' ? editingId : null
  const { data: fullTemplate, isFetching: templateLoading } = useTaskTemplate(editingExisting)
  const loadedTemplateId = fullTemplate?.id === editingExisting ? editingExisting : null

  function startCreate() {
    setEditingId('new')
    setForm(EMPTY_FORM)
    setItems([])
    setItemDraft('')
  }

  function startEdit(template) {
    setEditingId(template.id)
    setForm({
      name: template.name,
      title: template.title ?? '',
      description: template.description ?? '',
      urgency: template.urgency ?? '',
      tagId: template.tag?.id ?? '',
      categoryId: template.category?.id ?? '',
    })
    // Пункты подставятся ниже, когда приедет полный шаблон.
    setItems([])
    setItemDraft('')
  }

  // Подставляем пункты ровно один раз на каждый открытый шаблон: когда полный шаблон
  // приехал, а в форме их ещё нет.
  const [itemsLoadedFor, setItemsLoadedFor] = useState(null)
  if (loadedTemplateId && itemsLoadedFor !== loadedTemplateId) {
    setItemsLoadedFor(loadedTemplateId)
    setItems(fullTemplate.items.map((item) => item.content))
  }

  function addItem(event) {
    event.preventDefault()
    const content = itemDraft.trim()
    if (!content) {
      return
    }
    setItems((previous) => [...previous, content])
    setItemDraft('')
  }

  function moveItem(index, delta) {
    const target = index + delta
    if (target < 0 || target >= items.length) {
      return
    }
    setItems((previous) => {
      const next = [...previous]
      const [moved] = next.splice(index, 1)
      next.splice(target, 0, moved)
      return next
    })
  }

  function onSubmit(event) {
    event.preventDefault()
    const payload = {
      name: form.name.trim(),
      title: form.title.trim() || null,
      description: form.description.trim() || null,
      urgency: form.urgency || null,
      tagId: form.tagId || null,
      categoryId: form.categoryId || null,
      items: items.map((content) => ({ content })),
    }
    const onSuccess = () => {
      setEditingId(null)
      setItemsLoadedFor(null)
    }
    if (editingId === 'new') {
      createTemplate.mutate(payload, { onSuccess })
    } else {
      updateTemplate.mutate({ templateId: editingId, payload }, { onSuccess })
    }
  }

  function onDelete(template) {
    if (!window.confirm(t('taskTemplates.deleteConfirm', { name: template.name }))) {
      return
    }
    deleteTemplate.mutate(template.id)
  }

  const saving = createTemplate.isPending || updateTemplate.isPending
  const mutationError = createTemplate.error ?? updateTemplate.error ?? deleteTemplate.error

  return (
    <div className="mx-auto max-w-2xl px-4 py-8">
      <h1 className="mb-2 text-2xl font-semibold text-gray-900 dark:text-gray-100">
        {t('taskTemplates.navLabel')}
      </h1>
      <p className="mb-6 text-sm text-gray-500 dark:text-gray-400">{t('taskTemplates.hint')}</p>

      {canManage && editingId === null && (
        <button type="button" onClick={startCreate} className={`mb-6 ${primaryButtonClass}`}>
          {t('taskTemplates.create')}
        </button>
      )}

      {editingId !== null && (
        <form
          onSubmit={onSubmit}
          className="mb-6 space-y-4 rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800"
        >
          <Field label={t('taskTemplates.name')}>
            <input
              type="text"
              className={inputClass}
              maxLength={100}
              value={form.name}
              onChange={(event) => setForm({ ...form, name: event.target.value })}
            />
          </Field>

          <Field label={t('taskTemplates.taskTitle')}>
            <input
              type="text"
              className={inputClass}
              maxLength={255}
              placeholder={t('taskTemplates.taskTitlePlaceholder')}
              value={form.title}
              onChange={(event) => setForm({ ...form, title: event.target.value })}
            />
          </Field>

          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <Field label={t('taskTemplates.urgency')}>
              <select
                className={inputClass}
                value={form.urgency}
                onChange={(event) => setForm({ ...form, urgency: event.target.value })}
              >
                {/* «Не задано» — законный выбор, а не пропуск: шаблон вправе ничего не
                    говорить о срочности, и тогда форма задачи оставит своё значение. */}
                <option value="">{t('taskTemplates.unset')}</option>
                {TASK_URGENCIES.map((urgency) => (
                  <option key={urgency} value={urgency}>
                    {t(`urgency.${urgency}`)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label={t('taskTemplates.tag')}>
              <select
                className={inputClass}
                value={form.tagId}
                onChange={(event) => setForm({ ...form, tagId: event.target.value })}
              >
                <option value="">{t('taskTemplates.unset')}</option>
                {tags?.map((tag) => (
                  <option key={tag.id} value={tag.id}>
                    {tag.name}
                  </option>
                ))}
              </select>
            </Field>
            <Field label={t('taskTemplates.category')}>
              <select
                className={inputClass}
                value={form.categoryId}
                onChange={(event) => setForm({ ...form, categoryId: event.target.value })}
              >
                <option value="">{t('taskTemplates.unset')}</option>
                {categories?.map((category) => (
                  <option key={category.id} value={category.id}>
                    {category.name}
                  </option>
                ))}
              </select>
            </Field>
          </div>

          <Field label={t('taskTemplates.description')}>
            <textarea
              rows={4}
              className={inputClass}
              maxLength={20000}
              value={form.description}
              onChange={(event) => setForm({ ...form, description: event.target.value })}
            />
          </Field>

          <div>
            <h2 className="mb-2 text-sm font-semibold text-gray-900 dark:text-gray-100">
              {t('taskTemplates.checklist')}
            </h2>
            {items.length === 0 ? (
              <p className="text-sm text-gray-400 dark:text-gray-500">{t('taskTemplates.checklistEmpty')}</p>
            ) : (
              <ul className="divide-y divide-gray-100 dark:divide-gray-700">
                {items.map((content, index) => (
                  <li key={`${index}-${content}`} className="flex items-center gap-2 py-1.5 text-sm">
                    <GripVertical className="h-4 w-4 shrink-0 text-gray-300 dark:text-gray-600" aria-hidden="true" />
                    <span className="min-w-0 flex-1 break-words text-gray-900 dark:text-gray-100">{content}</span>
                    {/* Стрелки, а не перетаскивание: пунктов в шаблоне единицы, и порядок
                        правят раз в полгода — drag-and-drop здесь стоил бы дороже пользы. */}
                    <button
                      type="button"
                      onClick={() => moveItem(index, -1)}
                      disabled={index === 0}
                      aria-label={t('taskTemplates.moveUp')}
                      className="shrink-0 px-1 text-gray-400 hover:text-purple-700 disabled:opacity-30 dark:hover:text-purple-400"
                    >
                      ↑
                    </button>
                    <button
                      type="button"
                      onClick={() => moveItem(index, 1)}
                      disabled={index === items.length - 1}
                      aria-label={t('taskTemplates.moveDown')}
                      className="shrink-0 px-1 text-gray-400 hover:text-purple-700 disabled:opacity-30 dark:hover:text-purple-400"
                    >
                      ↓
                    </button>
                    <button
                      type="button"
                      onClick={() => setItems(items.filter((_, i) => i !== index))}
                      aria-label={t('taskTemplates.removeItem')}
                      className="shrink-0 text-gray-400 hover:text-red-600 dark:hover:text-red-400"
                    >
                      <Trash2 className="h-4 w-4" aria-hidden="true" />
                    </button>
                  </li>
                ))}
              </ul>
            )}

            {/* Не <form>, а поле с кнопкой: вложенная форма внутри формы шаблона недопустима
                в HTML, а Enter здесь обрабатывается вручную по той же причине. */}
            <div className="mt-2 flex items-center gap-2">
              <input
                type="text"
                value={itemDraft}
                maxLength={500}
                placeholder={t('taskTemplates.itemPlaceholder')}
                aria-label={t('taskTemplates.addItem')}
                onChange={(event) => setItemDraft(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    addItem(event)
                  }
                }}
                className={`${inputClass} min-w-0 flex-1`}
              />
              <button type="button" onClick={addItem} className={secondaryButtonClass}>
                {t('taskTemplates.addItem')}
              </button>
            </div>
          </div>

          {mutationError && (
            <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(mutationError, t)}</p>
          )}

          <div className="flex items-center gap-3">
            <button
              type="submit"
              disabled={saving || !form.name.trim() || (Boolean(editingExisting) && templateLoading)}
              className={primaryButtonClass}
            >
              {saving ? t('taskTemplates.saving') : t('taskTemplates.save')}
            </button>
            <button
              type="button"
              onClick={() => {
                setEditingId(null)
                setItemsLoadedFor(null)
              }}
              className="text-sm text-gray-500 hover:underline dark:text-gray-400"
            >
              {t('taskTemplates.cancel')}
            </button>
          </div>
        </form>
      )}

      {isLoading && <p className="text-gray-500 dark:text-gray-400">{t('taskTemplates.loading')}</p>}
      {isError && <p className="text-sm text-red-600 dark:text-red-400">{getLocalizedErrorMessage(error, t)}</p>}

      {!isLoading && !isError && templates && (
        <ul className="divide-y divide-gray-200 rounded-lg border border-gray-200 bg-white dark:divide-gray-700 dark:border-gray-700 dark:bg-gray-800">
          {templates.length === 0 && (
            <li className="px-4 py-3 text-sm text-gray-400 dark:text-gray-500">{t('taskTemplates.empty')}</li>
          )}
          {templates.map((template) => (
            <li key={template.id} className="flex items-center justify-between gap-4 px-4 py-3">
              <div className="min-w-0">
                <p className="truncate font-medium text-gray-900 dark:text-gray-100">{template.name}</p>
                <p className="truncate text-xs text-gray-500 dark:text-gray-400">
                  {template.title || t('taskTemplates.noTaskTitle')}
                  {' · '}
                  {t('taskTemplates.itemCount', { count: template.itemCount })}
                </p>
              </div>
              {canManage && (
                <div className="flex shrink-0 items-center gap-3">
                  <button
                    type="button"
                    onClick={() => startEdit(template)}
                    className="text-xs text-purple-600 hover:underline dark:text-purple-400"
                  >
                    {t('taskTemplates.edit')}
                  </button>
                  <button
                    type="button"
                    onClick={() => onDelete(template)}
                    disabled={deleteTemplate.isPending}
                    className="text-xs text-red-600 hover:underline disabled:opacity-60 dark:text-red-400"
                  >
                    {t('taskTemplates.delete')}
                  </button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
