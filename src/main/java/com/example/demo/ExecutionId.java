package com.example.demo;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public class ExecutionId {
    private String id;

    @NotNull
    public static ExecutionId executionIdent(@NotNull String id) {
        ExecutionId ident = new ExecutionId();
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
        ExecutionId that = (ExecutionId) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
