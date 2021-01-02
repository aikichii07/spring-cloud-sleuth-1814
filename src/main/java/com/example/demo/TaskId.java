package com.example.demo;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class TaskId {
    private String id;

    @NotNull
    public static TaskId taskIdent(@NotNull String id) {
        TaskId ident = new TaskId();
        ident.id = id;
        return ident;
    }

    @Override
    public String toString() {
        return id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TaskId taskId = (TaskId) o;
        return Objects.equals(id, taskId.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
