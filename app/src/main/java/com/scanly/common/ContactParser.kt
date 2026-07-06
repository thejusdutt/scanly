package com.scanly.common

/**
 * Heuristic business-card parser: turns OCR text into structured contact fields for the
 * system "add contact" intent and vCard export. Pure Kotlin/JVM — see ContactParserTest.
 *
 * Cards are messy and OCR is imperfect, so this is deliberately conservative: emails,
 * phones and websites come from strict patterns; the name is the first "human-looking"
 * line; the organization is a line with a legal/company suffix, else the line after the
 * name. Anything not confidently recognized is simply omitted — the contacts app lets
 * the user fix fields before saving.
 */
object ContactParser {

    data class Contact(
        val name: String? = null,
        val org: String? = null,
        val phones: List<String> = emptyList(),
        val emails: List<String> = emptyList(),
        val websites: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean
            get() = name == null && org == null &&
                phones.isEmpty() && emails.isEmpty() && websites.isEmpty()
    }

    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    // Note: plain space, not \s — a newline must never join two unrelated number runs
    // (street number + postcode) into one phantom phone number.
    private val PHONE = Regex("""\+?\d[\d() .\-/]{5,}\d""")
    private val WEBSITE = Regex(
        """(?i)\b((?:https?://|www\.)[^\s,;<>]+|[a-z0-9\-]+\.(?:com|net|org|io|co|de|fr|uk|in|es|it|nl|eu|ch|at|biz|info)(?:/[^\s,;<>]*)?)\b""",
    )
    private val ORG_SUFFIX = Regex(
        """(?i)\b(Inc|LLC|Ltd|GmbH|Corp|Co\.|Company|Pvt|Limited|AG|BV|SAS|Srl|LLP|PLC|Solutions|Technologies|Technology|Consulting|Group|Studio|Labs|Software|Systems)\b\.?""",
    )
    private val JOB_TITLE = Regex(
        """(?i)\b(CEO|CTO|CFO|COO|Founder|Director|Manager|Engineer|Developer|Designer|Consultant|President|Partner|Head|Lead|Officer|Sales|Marketing|Architect)\b""",
    )

    fun parse(text: String): Contact {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }

        val emails = EMAIL.findAll(text).map { it.value }.distinct().toList()
        val phones = PHONE.findAll(text)
            .map { it.value.replace(Regex("\\s+"), " ").trim() }
            .filter { candidate ->
                val digits = candidate.count(Char::isDigit)
                digits in 7..15 && emails.none { it.contains(candidate) }
            }
            .distinct()
            .toList()
        val websites = WEBSITE.findAll(text)
            .map { it.value.trimEnd('.', ',') }
            .filter { site ->
                // Not an email domain fragment, and not the tail of an email address.
                !site.contains('@') && emails.none { it.endsWith(site, ignoreCase = true) }
            }
            .map { it.lowercase() }
            .distinct()
            .toList()

        fun isContactLine(line: String): Boolean =
            EMAIL.containsMatchIn(line) || WEBSITE.containsMatchIn(line) ||
                PHONE.findAll(line).any { it.value.count(Char::isDigit) >= 7 }

        // Name: the first human-looking line near the top of the card.
        val name = lines.take(6).firstOrNull { line ->
            !isContactLine(line) &&
                !ORG_SUFFIX.containsMatchIn(line) &&
                !JOB_TITLE.containsMatchIn(line) &&
                line.length in 3..40 &&
                line.split(Regex("\\s+")).size in 2..4 &&
                line.count(Char::isLetter) >= line.length * 0.6
        }

        // Organization: a line with a company suffix, else the line right after the name.
        val org = lines.firstOrNull { it != name && ORG_SUFFIX.containsMatchIn(it) && !isContactLine(it) }
            ?: name?.let { n ->
                val after = lines.getOrNull(lines.indexOf(n) + 1)
                after?.takeIf {
                    !isContactLine(it) && !JOB_TITLE.containsMatchIn(it) &&
                        it.length in 2..50 && it.split(Regex("\\s+")).size <= 6
                }
            }

        return Contact(
            name = name,
            org = org,
            phones = phones.take(3),
            emails = emails.take(3),
            websites = websites.take(2),
        )
    }

    /** RFC 2426 (vCard 3.0) text for [c]; import-safe values (escaped , ; \ and newlines). */
    fun toVCard(c: Contact): String = buildString {
        fun esc(s: String) = s
            .replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;").replace("\n", "\\n")

        appendLine("BEGIN:VCARD")
        appendLine("VERSION:3.0")
        val display = c.name ?: c.org ?: "Contact"
        appendLine("FN:${esc(display)}")
        val parts = (c.name ?: "").split(Regex("\\s+")).filter { it.isNotBlank() }
        val family = if (parts.size > 1) parts.last() else ""
        val given = if (parts.size > 1) parts.dropLast(1).joinToString(" ") else parts.firstOrNull().orEmpty()
        appendLine("N:${esc(family)};${esc(given)};;;")
        c.org?.let { appendLine("ORG:${esc(it)}") }
        c.phones.forEach { appendLine("TEL;TYPE=WORK,VOICE:${esc(it)}") }
        c.emails.forEach { appendLine("EMAIL;TYPE=INTERNET:${esc(it)}") }
        c.websites.forEach { appendLine("URL:${esc(it)}") }
        appendLine("END:VCARD")
    }
}
