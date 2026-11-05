package com.sw1.backend.ai.diagram.validation;

import com.sw1.backend.ai.agent.service.AgentIntentClassifier;
import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.diagram.dto.*;
import org.springframework.http.HttpStatus;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded, fail-closed grammar; never infer authorization from LLM output.
 * Keep parity with FastAPI semantic_safety.py. Structural validation is separate.
 */
public final class SemanticOperationValidator {
    private SemanticOperationValidator() {}

    private static final String DELETE = "(?:elimina|eliminar|elimine|elimines|borra|borrar|borre|borres|quita|quitar|quite|quites|suprime|suprimir|suprimas|remueve|remover|remueva)";
    private static final String CREATE = "(?:crea|crear|creame|cree|agrega|agregar|agregame|anade|anadir|genera|generar|incluye|incluir|define|definir)";
    private static final String UPDATE = "(?:renombra|renombrar|cambia|cambiar|actualiza|actualizar|modifica|modificar|edita|editar|relaciona|relacionar|conecta|conectar|marca|marcar|establece|establecer|asigna|asignar|convierte|convertir|transforma|transformar|implementa|implementar|hereda|heredar|extiende|extender)";
    private static final String ACTION = "(?:" + DELETE + "|" + CREATE + "|" + UPDATE + ")";
    private static final String CONVERT = "(?:convierte|convertir|convierta|transforma|transformar|transforme)";
    private static final String PREFIX = "(?:(?:por favor)\\s+|(?:quiero|necesito)\\s+que\\s+)?";
    private static final String CLASSIFIER = "(?:(?:la|el|una|un)\\s+)?(?:(?:entidad|clase(?:\\s+uml)?|interfaz|interface|enumeracion|enumeration|enum)\\s+)?";
    private static final String ENTITY = "(?:(?:la|el|una|un)\\s+)?(?:(?:entidad|clase(?:\\s+uml|\\s+asociativa)?|asociacion)\\s+)?";
    private static final String ATTRIBUTE = "(?:(?:el|un)\\s+)?(?:atributo|campo|propiedad)\\s+";
    private static final String METHOD = "(?:(?:el|un)\\s+)?(?:metodo|operacion|funcion)\\s+";
    private static final String PARAMETER = "(?:(?:el|un)\\s+)?parametro\\s+";
    private static final String LITERAL = "(?:(?:el|un)\\s+)?(?:literal|valor)\\s+";

    public static void validate(String message, DiagramContext context, List<DiagramOperation> operations) {
        if (operations.isEmpty()) return;
        String text = AgentIntentClassifier.normalizeLeadingMention(message).replaceFirst("(?i)^\\s*@logicdraft\\b\\s*", "");
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
                case DELETE_ENTITY -> {
                    boolean existsInEntities = context.entities().stream()
                            .filter(e -> same(e.name(), op.entityName())).count() == 1;
                    boolean existsInReadOnly = context.readOnlyElements().stream()
                            .filter(e -> same(e.name(), op.entityName())).count() == 1
                            || context.readOnlyElementNames().stream()
                            .filter(n -> same(n, op.entityName())).count() == 1;
                    boolean notAssociation = context.associations().stream()
                            .noneMatch(a -> same(a.entityName(), op.entityName()));
                    yield (existsInEntities || existsInReadOnly)
                            && notAssociation
                            && allows(clauses, CLASSIFIER + named(op.entityName()));
                }
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
                case DELETE_METHOD -> {
                    boolean classifierExists = classifierExists(context, op.entityName());
                    String methodTarget = named(op.methodName()) + "(?:\\s*\\([^)]*\\))?";
                    String targetClassifier = CLASSIFIER + named(op.entityName());
                    yield classifierExists
                            && (allows(clauses, METHOD + methodTarget + "\\s+(?:de|en)\\s+" + targetClassifier)
                            || allows(clauses, "de\\s+" + targetClassifier + "\\s+" + METHOD + methodTarget));
                }
                case DELETE_METHOD_PARAMETER -> {
                    boolean classifierExists = classifierExists(context, op.entityName());
                    String paramTarget = named(op.parameterName());
                    String methodTarget = "(?:(?:del|de\\s+el|en\\s+el)\\s+)?(?:metodo\\s+)?"
                            + named(op.methodName()) + "(?:\\s*\\([^)]*\\))?";
                    String targetClassifier = "(?:\\s+(?:de|en)\\s+" + CLASSIFIER + named(op.entityName()) + ")?";
                    yield classifierExists
                            && allows(clauses, PARAMETER + paramTarget + "\\s+" + methodTarget + targetClassifier);
                }
                case DELETE_ENUM_LITERAL -> {
                    boolean classifierExists = classifierExists(context, op.entityName());
                    String litTarget = named(op.literal());
                    String targetClassifier = CLASSIFIER + named(op.entityName());
                    yield classifierExists
                            && (allows(clauses, LITERAL + litTarget + "\\s+(?:de|en)\\s+" + targetClassifier)
                            || allows(clauses, "de\\s+" + targetClassifier + "\\s+" + LITERAL + litTarget));
                }
                case DELETE_RELATIONSHIP -> {
                    var rel = op.relationship();
                    var pair = Set.copyOf(List.of(plain(rel.sourceEntity()), plain(rel.targetEntity())));
                    long multMatches = context.relationships().stream().filter(r ->
                            Set.copyOf(List.of(plain(r.sourceEntity()), plain(r.targetEntity()))).equals(pair)
                            && (rel.name() == null || same(r.name(), rel.name()))).count();
                    long umlMatches = context.readOnlyRelations().stream().filter(r ->
                            Set.copyOf(List.of(plain(r.sourceName()), plain(r.targetName()))).equals(pair)
                            && (rel.kind() == null || same(r.relationKind(), rel.kind()))).count();
                    long matches = (rel.kind() != null
                            && Set.of("GENERALIZATION", "REALIZATION", "DEPENDENCY").contains(rel.kind()))
                            ? umlMatches
                            : (multMatches + umlMatches);
                    String body = "(?:(?:la|una)\\s+)?(?:relacion|asociacion|herencia|generalizacion|realizacion|implementacion|agregacion|composicion|dependencia)(?:\\s+de\\s+(?:herencia|generalizacion|realizacion|implementacion|agregacion|composicion|dependencia))?\\s+"
                            + (rel.name() == null ? "" : named(rel.name()) + "\\s+") + "(?:entre|de)\\s+";
                    String sep = "(?:\\s+y\\s+|\\s+a\\s+|\\s+hacia\\s+|\\s+con\\s+)";
                    yield matches == 1 && (allows(clauses, body + CLASSIFIER + named(rel.sourceEntity()) + sep + CLASSIFIER + named(rel.targetEntity()))
                            || allows(clauses, body + CLASSIFIER + named(rel.targetEntity()) + sep + CLASSIFIER + named(rel.sourceEntity())));
                }
                case CREATE_ASSOCIATION, CONVERT_MANY_TO_MANY_ASSOCIATION -> {
                    // Both can consume an existing N:M edge in the server applier.
                    boolean create = op.association() != null;
                    String source = create ? op.association().sourceEntity() : op.conversion().sourceEntity();
                    String target = create ? op.association().targetEntity() : op.conversion().targetEntity();
                    String name = create ? op.association().associationEntityName() : op.conversion().associationEntityName();
                    String pair = "entre\\s+" + CLASSIFIER + named(source) + "\\s+y\\s+" + CLASSIFIER + named(target);
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
                    String actualKind = op.entity().kind() == null ? "ENTITY" : op.entity().kind();
                    // Prevent silent downgrade when user explicitly asked for CLASS/INTERFACE/ENUMERATION/ASSOCIATION_CLASS
                    // but operation has kind == null / ENTITY.
                    if ("ENTITY".equals(actualKind)) {
                        Pattern unsupported = Pattern.compile(PREFIX + CREATE
                                + "\\s+(?:(?:una?|la)\\s+)?(?:clase\\s+uml|class|interfaz|interface|enumeracion|enumeration|enum|clase\\s+asociativa)\\b");
                        if (clauses.stream().anyMatch(c -> unsupported.matcher(c).lookingAt())) {
                            throw rejected("La creación de ese tipo UML aún no está soportada como entidad simple; especifica el tipo UML correspondiente.");
                        }
                    }
                    yield true;
                }
                default -> true;
            };
            if (!authorized) throw rejected("Propuesta bloqueada: la eliminación no coincide con un objetivo explícito y único de tu solicitud. "
                    + "Aclara la clase, atributo o relación que deseas eliminar.");
        }
    }

    private static boolean classifierExists(DiagramContext context, String name) {
        if (name == null) return false;
        return context.entities().stream().anyMatch(e -> same(e.name(), name))
                || context.readOnlyElements().stream().anyMatch(e -> same(e.name(), name))
                || context.readOnlyElementNames().stream().anyMatch(n -> same(n, name));
    }

    private static boolean allows(List<String> clauses, String body) {
        Pattern command = Pattern.compile(PREFIX + DELETE + "\\s+" + body);
        return clauses.stream().anyMatch(c -> command.matcher(c).matches());
    }

    private static List<String> statements(String text) {
        boolean[] quoted = new boolean[text.length()];
        var quotes = Pattern.compile("\"[^\"]*\"|'[^']*'").matcher(text);
        while (quotes.find()) for (int i = quotes.start(); i < quotes.end(); i++) quoted[i] = true;
        var separators = Pattern.compile("[;]+|\\s*,?\\s+\\by\\s+(?=" + PREFIX + ACTION
                + "\\b)|,\\s*(?=" + PREFIX + ACTION + "\\b)").matcher(text);
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
