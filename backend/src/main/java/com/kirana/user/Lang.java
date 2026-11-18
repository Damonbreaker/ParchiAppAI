package com.kirana.user;

import com.fasterxml.jackson.annotation.JsonValue;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** UI language. Stored and sent as lowercase "en" / "hi". */
public enum Lang {
    EN("en"), HI("hi");

    private final String code;

    Lang(String code) {
        this.code = code;
    }

    @JsonValue
    public String code() {
        return code;
    }

    @Override
    public String toString() {
        return code;
    }

    public static Lang fromCode(String code) {
        for (Lang l : values()) {
            if (l.code.equals(code)) {
                return l;
            }
        }
        throw new IllegalArgumentException("Unknown language: " + code);
    }

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<Lang, String> {
        @Override
        public String convertToDatabaseColumn(Lang lang) {
            return lang == null ? null : lang.code;
        }

        @Override
        public Lang convertToEntityAttribute(String code) {
            return code == null ? null : fromCode(code);
        }
    }
}
