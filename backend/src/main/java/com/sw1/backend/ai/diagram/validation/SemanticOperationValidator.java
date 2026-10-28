package com.sw1.backend.ai.diagram.validation;

import com.sw1.backend.ai.agent.service.AgentIntentClassifier;
import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.diagram.dto.*;
import org.springframework.http.HttpStatus;

import java.text.Normalizer;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, fail-closed grammar; never infer authorization from LLM output.
 * Keep parity with FastAPI semantic_safety.py. Structural validation is separate.
 */
public final class SemanticOperationValidator {
    private SemanticOperationValidator() {}

    private static final String DELETE = "(?:elimina|eliminar|elimine|elimines|borra|borrar|borre|borres|quita|quitar|quite|quites|suprime|suprimir|suprimas)";
    private static final String CREATE = "(?:crea|crear|creame|cree|agrega|agregar|anade|genera)";
    private static final String ACTION = "(?:" + DELETE + "|" + CREATE + "|renombra|cambia|actualiza|modifica|relaciona|conecta)";
    private static final String CONVERT = "(?:convierte|convertir|convierta|transforma|transformar|transforme)";
    private static final String PREFIX = "(?:(?:por favor)\\s+|(?:quiero|necesito)\\s+que\\s+)?";
    private static final String ENTITY = "(?:(?:la|el|una|un)\\s+)?(?:(?:entidad|clase(?:\\s+uml)?)\\s+)?";
    private static final String ATTRIBUTE = "(?:(?:el|un)\\s+)?(?:atributo|campo)\\s+";

    public static void validate(String message, DiagramContext context, List<DiagramOperation> operations) {
        if (operations.isEmpty()) return;
        String text = message == null ? "" : message.replaceFirst("(?i)^\\s*@logicdraft\\b\\s*", "");
        if (AgentIntentClassifier.classify(text) == AgentIntentClassifier.Intent.INFORMATIONAL) {
            throw rejected("Una consulta no autoriza cambios. Aclara la modificación solicitada.");
        }
        List<String> clauses = statements(plain(text));
        for (DiagramOperation op : operations) {
            if ((op.type().name().startsWith("DELETE_")
                    || op.type() == com.sw1.backend.ai.diagram.model.DiagramOperationType.CREATE_ASSOCIATION
                    || op.type() == com.sw1.backend.ai.diagram.model.DiagramOperationType.CONVERT_MANY_TO_MANY_ASSOCIATION) &&
                    (AgentIntentClassifier.classify(text) != AgentIntentClassifier.Intent.MODIFICATION
                    || Pattern.compile("\\b(?:si|cuando|salvo|excepto|a menos que|no|nunca|jamas|evita)\\b").matcher(plain(text)).find())) {
                throw rejected("La eliminación es condicional o ambigua. Aclara el objetivo y la acción.");
            }
            boolean authorized = switch (op.type()) {
                case DELETE_ENTITY -> context.entities().stream().filter(e -> same(e.name(), op.entityName())).count() == 1
                        && context.associations().stream().noneMatch(a -> same(a.entityName(), op.entityName()))
                        && allows(clauses, ENTITY + named(op.entityName()));
                case DELETE_ASSOCIATION -> context.associations().stream()
                        .filter(a -> same(a.entityName(), op.associationEntityName())).count() == 1
                        && allows(clauses, "(?:(?:la|una)\\s+)?(?:asociacion|(?:clase|entidad)\\s+asociativa)\\s+"
                        + named(op.associationEntityName()));
                case DELETE_ATTRIBUTE -> {
                    var entities = context.entities().stream().filter(e -> same(e.name(), op.entityName())).toList();
                    String entity = ENTITY + named(op.entityName()), attribute = named(op.attributeName());
                    yield entities.size() == 1 && entities.getFirst().attributes().stream()
                            .filter(a -> same(a.name(), op.attributeName())).count() == 1
                            && (allows(clauses, ATTRIBUTE + attribute + "\\s+(?:de|en)\\s+" + entity)
                            || allows(clauses, "de\\s+" + entity + "\\s+" + ATTRIBUTE + attribute));
                }
                case DELETE_RELATIONSHIP -> {
                    var rel = op.relationship();
                    var pair = Set.copyOf(List.of(plain(rel.sourceEntity()), plain(rel.targetEntity())));
                    long matches = context.relationships().stream().filter(r ->
                            Set.copyOf(List.of(plain(r.sourceEntity()), plain(r.targetEntity()))).equals(pair)
                            && (rel.name() == null || same(r.name(), rel.name()))).count();
                    String body = "(?:(?:la|una)\\s+)?relacion\\s+"
                            + (rel.name() == null ? "" : named(rel.name()) + "\\s+") + "entre\\s+";
                    yield matches == 1 && (allows(clauses, body + named(rel.sourceEntity()) + "\\s+y\\s+" + named(rel.targetEntity()))
                            || allows(clauses, body + named(rel.targetEntity()) + "\\s+y\\s+" + named(rel.sourceEntity())));
                }
                case CREATE_ASSOCIATION, CONVERT_MANY_TO_MANY_ASSOCIATION -> {
                    // Both can consume an existing N:M edge in the server applier.
                    boolean create = op.association() != null;
                    String source = create ? op.association().sourceEntity() : op.conversion().sourceEntity();
                    String target = create ? op.association().targetEntity() : op.conversion().targetEntity();
                    String name = create ? op.association().associationEntityName() : op.conversion().associationEntityName();
                    String pair = "entre\\s+" + named(source) + "\\s+y\\s+" + named(target);
                    String association = "(?:(?:una|la)\\s+)?(?:(?:clase|entidad)\\s+asociativa\\s+)?(?:llamada\\s+)?" + named(name);
                    String command;
                    if (create) {
                        String explicitAssociation = "(?:(?:una|la)\\s+)?(?:asociacion|(?:clase|entidad)\\s+asociativa)\\s+(?:llamada\\s+)?" + named(name);
                        command = PREFIX + CREATE + "\\s+" + explicitAssociation + "\\s+" + pair;
                    } else {
                        long candidates = context.relationships().stream().filter(r ->
                                r.sourceCardinality() != null && r.targetCardinality() != null
                                && r.sourceCardinality().name().endsWith("MANY")
                                && r.targetCardinality().name().endsWith("MANY")).count();
                        String scoped = candidates == 1 ? "(?:\\s+" + pair + ")?" : "\\s+" + pair;
                        command = PREFIX + CONVERT + "\\s+(?:la\\s+)?(?:relacion\\s+)?n\\s*:\\s*m"
                                + scoped + "\\s+en\\s+" + association;
                    }
                    Pattern requested = Pattern.compile(command + "(?:,?\\s+con\\s+.+)?");
                    yield clauses.stream().anyMatch(c -> requested.matcher(c).matches());
                }
                case ADD_ENTITY -> {
                    // No silent downgrade of explicitly requested UML kinds until Stage B.
                    Pattern unsupported = Pattern.compile(PREFIX + CREATE
                            + "\\s+(?:(?:una?|la)\\s+)?(?:clase\\s+uml|class|interfaz|interface|enumeracion|enumeration|clase\\s+asociativa)\\b");
                    if (clauses.stream().anyMatch(c -> unsupported.matcher(c).lookingAt())) {
                        throw rejected("La creación de ese tipo UML aún no está soportada por el contrato del agente; usa el editor.");
                    }
                    yield true;
                }
                default -> true;
            };
            if (!authorized) throw rejected("Propuesta bloqueada: la eliminación no coincide con un objetivo explícito y único de tu solicitud. "
                    + "Aclara la clase, atributo o relación que deseas eliminar.");
        }
    }

    private static boolean allows(List<String> clauses, String body) {
        Pattern command = Pattern.compile(PREFIX + DELETE + "\\s+" + body);
        return clauses.stream().anyMatch(c -> command.matcher(c).matches());
    }

    private static List<String> statements(String text) {
        boolean[] quoted = new boolean[text.length()];
        var quotes = Pattern.compile("\"[^\"]*\"|'[^']*'").matcher(text);
        while (quotes.find()) for (int i = quotes.start(); i < quotes.end(); i++) quoted[i] = true;
        var separators = Pattern.compile("[;]+|\\s*,?\\s+\\by\\s+(?=" + ACTION
                + "\\b)|,\\s*(?=" + ACTION + "\\b)").matcher(text);
        List<String> clauses = new ArrayList<>();
        int start = 0;
        while (separators.find()) {
            if (!quoted[separators.start()]) {
                clauses.add(text.substring(start, separators.start()));
                start = separators.end();
            }
        }
        clauses.add(text.substring(start));
        return clauses.stream().map(s -> s.replaceAll("^[ .!]+|[ .!]+$", "")).toList();
    }

    private static String named(String name) {
        String escaped = Pattern.quote(plain(name));
        return "(?:" + escaped + "|\"" + escaped + "\"|'" + escaped + "')";
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && plain(left).equals(plain(right));
    }

    private static String plain(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("\\s+", " ").strip();
    }

    private static AiServiceException rejected(String message) {
        return new AiServiceException(HttpStatus.UNPROCESSABLE_ENTITY,
                AiServiceException.ErrorCategory.OPERATION_VALIDATION_ERROR, message, "SEMANTIC_TARGET_MISMATCH");
    }
}
