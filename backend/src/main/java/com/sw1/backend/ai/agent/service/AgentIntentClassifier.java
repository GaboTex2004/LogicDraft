package com.sw1.backend.ai.agent.service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class AgentIntentClassifier {
    public enum Intent { INFORMATIONAL, MODIFICATION, AMBIGUOUS }

    private static final Pattern QUESTION = Pattern.compile(
            "^(que|cual|cuales|como|por que|explica(?:me)?|describe|dime|muestra|opinas)\\b");
    private static final Pattern SUGGESTIVE = Pattern.compile(
            "^(podriamos|podria(?:mos)?|tal vez|quizas|quiza)\\b|\\b(que tal si|seria bueno|convendria)\\b");
    private static final Pattern EXPLICIT = Pattern.compile(
            "^(?:(?:por favor)\\s+)?(agrega|anade|crea|conecta|relaciona|convierte|transforma)\\b"
            + "|^(quiero|necesito)\\s+que\\s+(agregues|anadas|crees|conectes|relaciones|conviertas|transformes)\\b");
    private static final Pattern TECHNICAL = Pattern.compile(
            "\\b(ADD_ENTITY|ADD_ATTRIBUTE|ADD_RELATIONSHIP|CONVERT_MANY_TO_MANY_ASSOCIATION)\\b");

    private AgentIntentClassifier() {}

    public static Intent classify(String message) {
        String plain = Normalizer.normalize(message.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('¿', ' ').replace('¡', ' ').strip();
        if (message.indexOf('?') >= 0 || QUESTION.matcher(plain).find()) return Intent.INFORMATIONAL;
        if (SUGGESTIVE.matcher(plain).find()) return Intent.AMBIGUOUS;
        return EXPLICIT.matcher(plain).find() ? Intent.MODIFICATION : Intent.AMBIGUOUS;
    }

    public static boolean containsTechnicalOperation(String answer) {
        return TECHNICAL.matcher(answer).find();
    }
}
