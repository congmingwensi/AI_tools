package com.nahida.gptprobe

object JsonSupport {
    fun quote(value: CharSequence?): String {
        if (value == null) return "null"
        val output = StringBuilder(value.length + 16)
        output.append('"')
        for (character in value) {
            when (character) {
                '"' -> output.append("\\\"")
                '\\' -> output.append("\\\\")
                '\b' -> output.append("\\b")
                '\u000C' -> output.append("\\f")
                '\n' -> output.append("\\n")
                '\r' -> output.append("\\r")
                '\t' -> output.append("\\t")
                else -> {
                    if (character.toInt() < 0x20) {
                        output.append(String.format("\\u%04x", character.toInt()))
                    } else {
                        output.append(character)
                    }
                }
            }
        }
        output.append('"')
        return output.toString()
    }

    fun stringArray(values: List<CharSequence>): String {
        return values.joinToString(prefix = "[", postfix = "]", separator = ",") { quote(it) }
    }
}

