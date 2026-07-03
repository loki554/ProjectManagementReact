package com.pmtracker.project_management_backend.project;

/**
 * Порядок констант отражает убывающий уровень привилегий (OWNER — максимум),
 * это используется в {@link #isAtLeast(ProjectRole)} через сравнение ordinal.
 */
public enum ProjectRole {
    OWNER,
    ADMIN,
    MEMBER,
    VIEWER;

    public boolean isAtLeast(ProjectRole required) {
        return this.ordinal() <= required.ordinal();
    }
}
