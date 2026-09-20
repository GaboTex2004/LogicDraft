package com.sw1.backend.generator.spring.render;

public final class JavaSourceWriter {
    private final StringBuilder content = new StringBuilder();
    private int indent;

    public JavaSourceWriter line(String value) {
        if (!value.isEmpty()) content.append("    ".repeat(indent));
        content.append(value).append('\n');
        return this;
    }

    public JavaSourceWriter open(String declaration) {
        line(declaration + " {");
        indent++;
        return this;
    }

    public JavaSourceWriter close() {
        indent--;
        return line("}");
    }

    @Override
    public String toString() {
        return content.toString();
    }
}
