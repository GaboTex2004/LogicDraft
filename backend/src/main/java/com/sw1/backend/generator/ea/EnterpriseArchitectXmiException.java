package com.sw1.backend.generator.ea;

/** Safe, user-facing failure while projecting a diagram to supported UML XMI. */
public class EnterpriseArchitectXmiException extends RuntimeException {
    public EnterpriseArchitectXmiException(String message) { super(message); }
    public EnterpriseArchitectXmiException(String message, Throwable cause) { super(message, cause); }
}
