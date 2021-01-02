package com.example.demo;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class WorkflowId {
    private String id;

    @NotNull
    public static WorkflowId workflowIdent(@NotNull String id) {
        WorkflowId ident = new WorkflowId();
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
        WorkflowId that = (WorkflowId) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
