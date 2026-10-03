package com.mia.app.domain.accuracy

/**
 * A dependency-free JSON reader for the test fixtures.
 *
 * `:domain` deliberately has no parsing dependency (plan.md §4.3 — pure Kotlin, no framework),
 * so a ~100-line reader is cheaper than adding one for a single fixture file. It supports exactly
 * what the fixture needs: objects, arrays, strings, numbers, booleans and null, and it *fails
 * loudly* on anything else rather than guessing.
 */
internal object MiniJson {

    fun parse(text: String): Any? {
        val cursor = Cursor(text)
        cursor.skipWhitespace()
        val value = cursor.readValue()
        cursor.skipWhitespace()
        require(cursor.atEnd) { "trailing content at offset ${cursor.offset} of ${text.length}" }
        return value
    }

    private class Cursor(private val text: String) {
        var offset = 0
            private set

        val atEnd: Boolean get() = offset >= text.length

        fun skipWhitespace() {
            while (offset < text.length && text[offset].isWhitespace()) offset++
        }

        fun readValue(): Any? {
            skipWhitespace()
            require(!atEnd) { "unexpected end of JSON input" }
            return when (text[offset]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                offset++
                return out
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                out[key] = readValue()
                skipWhitespace()
                when (val c = peek()) {
                    ',' -> offset++
                    '}' -> {
                        offset++
                        return out
                    }
                    else -> error("expected ',' or '}' but found '$c' at offset $offset")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                offset++
                return out
            }
            while (true) {
                out += readValue()
                skipWhitespace()
                when (val c = peek()) {
                    ',' -> offset++
                    ']' -> {
                        offset++
                        return out
                    }
                    else -> error("expected ',' or ']' but found '$c' at offset $offset")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(!atEnd) { "unterminated string" }
                when (val c = text[offset++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(!atEnd) { "unterminated escape" }
                        when (val esc = text[offset++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(offset + 4 <= text.length) { "truncated \\u escape" }
                                sb.append(text.substring(offset, offset + 4).toInt(16).toChar())
                                offset += 4
                            }
                            else -> error("unsupported escape '\\$esc' at offset $offset")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun <T> readLiteral(literal: String, value: T): T {
            require(text.startsWith(literal, offset)) { "expected '$literal' at offset $offset" }
            offset += literal.length
            return value
        }

        private fun readNumber(): Double {
            val start = offset
            if (peek() == '-' || peek() == '+') offset++
            while (!atEnd && (text[offset].isDigit() || text[offset] in ".eE+-")) offset++
            require(offset > start) { "expected a number at offset $start" }
            val raw = text.substring(start, offset)
            return raw.toDoubleOrNull() ?: error("'$raw' at offset $start is not a number")
        }

        private fun peek(): Char {
            require(!atEnd) { "unexpected end of JSON input" }
            return text[offset]
        }

        private fun expect(c: Char) {
            skipWhitespace()
            require(!atEnd && text[offset] == c) {
                "expected '$c' at offset $offset"
            }
            offset++
        }
    }
}