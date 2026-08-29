package com.pmtracker.project_management_backend.common.exception;

/**
 * Попытка восстановить подзадачу, родитель которой сам лежит в корзине (3.5).
 * Возвращать её некуда: она повисла бы под задачей, которой для приложения не существует.
 */
public class ParentTaskDeletedException extends RuntimeException {

    public ParentTaskDeletedException() {
        super("Restore the parent task first");
    }
}
