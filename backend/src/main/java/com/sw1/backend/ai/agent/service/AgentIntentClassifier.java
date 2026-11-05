package com.sw1.backend.ai.agent.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AgentIntentClassifier {
    public enum Intent { INFORMATIONAL, MODIFICATION, AMBIGUOUS }

    private static final Pattern QUESTION = Pattern.compile(
            "^(que|cual|cuales|cuanto|cuantos|cuanta|cuantas|como|por que|que pasaria si|"
            + "explica(?:me)?|describe|dime|muestra(?:me)?|opinas|lee|leer|lista|listar|enumera|consulta|consultar)\\b");
    private static final Pattern ADVISORY = Pattern.compile(
            "^(sugiere|sugerir|recomienda|recomendar|analiza|analizar|revisa|revisar|evalua|evaluar)\\b");
    private static final Pattern NEGATED = Pattern.compile(
            "^(no|nunca|jamas|evita|evitar)\\b|^(no quiero|no necesito)\\s+que\\b");
    private static final Pattern SUGGESTIVE = Pattern.compile(
            "^(podriamos|podria(?:mos)?|tal vez|quizas|quiza)\\b|\\b(que tal si|seria bueno|convendria)\\b");
    private static final Pattern EXPLICIT = Pattern.compile(
            "^(?:(?:por favor)\\s+)?(agrega|agregar|agregame|agregue|anade|anadir|anademe|anada|"
            + "crea|crear|creame|cree|genera|generar|generame|genere|incluye|incluir|incluya|"
            + "conecta|conectar|conecte|relaciona|relacionar|relacione|une|unir|"
            + "convierte|convertir|convierta|transforma|transformar|transforme|"
            + "edita|editar|edite|modifica|modificar|modifique|actualiza|actualizar|actualice|"
            + "cambia|cambiar|cambie|renombra|renombrar|renombre|elimina|eliminar|elimine|"
            + "borra|borrar|borre|quita|quitar|quite|suprime|suprimir|remueve|remover|remueva|"
            + "marca|marcar|marque|establece|establecer|establezca|define|definir|defina|"
            + "asigna|asignar|asigne|implementa|implementar|implemente|hereda|heredar|herede|"
            + "extiende|extender|extienda)\\b"
            + "|^(quiero|necesito)\\s+que\\s+(agregues|anadas|crees|generes|incluyas|conectes|"
            + "relaciones|unas|conviertas|transformes|edites|modifiques|actualices|cambies|"
            + "renombres|elimines|borres|quites|suprimas|remuevas|marques|establezcas|definas)\\b");
    private static final Pattern TECHNICAL = Pattern.compile(
            "\\b(ADD_ENTITY|DELETE_ENTITY|RENAME_ENTITY|UPDATE_CLASSIFIER|"
            + "ADD_ATTRIBUTE|DELETE_ATTRIBUTE|RENAME_ATTRIBUTE|CHANGE_ATTRIBUTE_TYPE|"
            + "SET_ATTRIBUTE_PRIMARY_KEY|SET_ATTRIBUTE_NULLABLE|SET_ATTRIBUTE_VISIBILITY|SET_ATTRIBUTE_STATIC|"
            + "ADD_METHOD|UPDATE_METHOD|DELETE_METHOD|"
            + "ADD_METHOD_PARAMETER|UPDATE_METHOD_PARAMETER|DELETE_METHOD_PARAMETER|"
            + "ADD_ENUM_LITERAL|RENAME_ENUM_LITERAL|DELETE_ENUM_LITERAL|"
            + "ADD_RELATIONSHIP|DELETE_RELATIONSHIP|UPDATE_RELATIONSHIP|"
            + "CREATE_ASSOCIATION|DELETE_ASSOCIATION|CONVERT_MANY_TO_MANY_ASSOCIATION)\\b");

    private AgentIntentClassifier() {}

    public static String normalizeLeadingMention(String message) {
        if (message == null) return "";
        return message.replaceFirst("(?i)^\\s*(?:\\\\?\\*){1,2}\\s*(@logicdraft)\\s*(?:\\\\?\\*){1,2}", "$1");
    }

    public static Intent classify(String message) {
        String stripped = normalizeLeadingMention(message).replaceFirst("(?i)^\\s*@logicdraft\\b\\s*", "");
        String plain = Normalizer.normalize(stripped.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('¿', ' ').replace('¡', ' ').strip();
        if (stripped.strip().startsWith("¿") || QUESTION.matcher(plain).find()
                || ADVISORY.matcher(plain).find()) return Intent.INFORMATIONAL;
        if (NEGATED.matcher(plain).find()) return Intent.AMBIGUOUS;
        if (SUGGESTIVE.matcher(plain).find()) return Intent.AMBIGUOUS;
        if (EXPLICIT.matcher(plain).find()) return Intent.MODIFICATION;
        return stripped.indexOf('?') >= 0 ? Intent.INFORMATIONAL : Intent.AMBIGUOUS;
    }

    public static boolean containsTechnicalOperation(String answer) {
        return TECHNICAL.matcher(answer).find();
    }
}
