package com.agora.service.ai.skill;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

/**
 * Rich response from an AI Skill, containing text and an optional inline keyboard.
 */
public class SkillResponse {

    private final String text;
    private final InlineKeyboardMarkup keyboard;
    private final String parseMode;

    private SkillResponse(String text, InlineKeyboardMarkup keyboard, String parseMode) {
        this.text = text;
        this.keyboard = keyboard;
        this.parseMode = parseMode;
    }

    public static SkillResponse text(String text) {
        return new SkillResponse(text, null, null);
    }

    public static SkillResponse html(String text) {
        return new SkillResponse(text, null, "HTML");
    }

    public static SkillResponse withKeyboard(String text, InlineKeyboardMarkup keyboard) {
        return new SkillResponse(text, keyboard, null);
    }

    public static SkillResponse htmlWithKeyboard(String text, InlineKeyboardMarkup keyboard) {
        return new SkillResponse(text, keyboard, "HTML");
    }

    public String getText() {
        return text;
    }

    public InlineKeyboardMarkup getKeyboard() {
        return keyboard;
    }

    public boolean hasKeyboard() {
        return keyboard != null;
    }

    public String getParseMode() {
        return parseMode;
    }

    public boolean hasParseMode() {
        return parseMode != null;
    }
}
