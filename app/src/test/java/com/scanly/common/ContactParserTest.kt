package com.scanly.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactParserTest {

    private val card = """
        John A. Smith
        Senior Sales Manager
        Acme Solutions GmbH
        +49 170 1234567
        Tel: (040) 555-0199
        john.smith@acme-solutions.de
        www.acme-solutions.de
        Musterstraße 12, 20095 Hamburg
    """.trimIndent()

    @Test
    fun `parses a typical business card`() {
        val c = ContactParser.parse(card)
        assertEquals("John A. Smith", c.name)
        assertEquals("Acme Solutions GmbH", c.org)
        assertEquals(listOf("john.smith@acme-solutions.de"), c.emails)
        assertEquals("www.acme-solutions.de", c.websites.single())
        assertEquals(2, c.phones.size)
        assertTrue(c.phones.any { it.contains("1234567") })
        assertTrue(c.phones.any { it.contains("555-0199") })
    }

    @Test
    fun `job title line is not mistaken for the name`() {
        val c = ContactParser.parse("Senior Sales Manager\nJane Doe\njane@x.com")
        assertEquals("Jane Doe", c.name)
    }

    @Test
    fun `short number runs are not phones`() {
        // Postcodes / house numbers must not become phone numbers.
        val c = ContactParser.parse("Main Street 123\n20095 Hamburg\n+1 415 555 0132")
        assertEquals(1, c.phones.size)
        assertTrue(c.phones.single().contains("415"))
    }

    @Test
    fun `email domains do not leak into websites`() {
        val c = ContactParser.parse("bob@widgets.com")
        assertEquals(listOf("bob@widgets.com"), c.emails)
        assertTrue(c.websites.isEmpty())
    }

    @Test
    fun `org falls back to the line after the name`() {
        val c = ContactParser.parse("Mary Poppins\nCherry Tree Nursery\n+44 20 7946 0958")
        assertEquals("Mary Poppins", c.name)
        assertEquals("Cherry Tree Nursery", c.org)
    }

    @Test
    fun `empty text parses to an empty contact`() {
        assertTrue(ContactParser.parse("").isEmpty)
        assertFalse(ContactParser.parse(card).isEmpty)
    }

    @Test
    fun `vcard has required structure and escaped values`() {
        val v = ContactParser.toVCard(
            ContactParser.Contact(
                name = "John Smith",
                org = "Acme, Inc; EU",
                phones = listOf("+49 170 1234567"),
                emails = listOf("j@acme.com"),
                websites = listOf("www.acme.com"),
            ),
        )
        assertTrue(v.startsWith("BEGIN:VCARD"))
        assertTrue(v.trimEnd().endsWith("END:VCARD"))
        assertTrue(v.contains("VERSION:3.0"))
        assertTrue(v.contains("FN:John Smith"))
        assertTrue(v.contains("N:Smith;John;;;"))
        assertTrue(v.contains("ORG:Acme\\, Inc\\; EU"))
        assertTrue(v.contains("TEL;TYPE=WORK,VOICE:+49 170 1234567"))
        assertTrue(v.contains("EMAIL;TYPE=INTERNET:j@acme.com"))
        assertTrue(v.contains("URL:www.acme.com"))
    }
}
