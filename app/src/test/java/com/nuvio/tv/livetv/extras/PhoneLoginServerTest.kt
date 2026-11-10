package com.nuvio.tv.livetv.extras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLoginServerTest {
    private val token = "abcdefghjkmnpq23"

    @Test fun `token check is exact`() {
        assertTrue(PhoneLoginServer.tokenOk(token, token))
        assertFalse(PhoneLoginServer.tokenOk(null, token))
        assertFalse(PhoneLoginServer.tokenOk("", token))
        assertFalse(PhoneLoginServer.tokenOk(token.dropLast(1), token))
        assertFalse(PhoneLoginServer.tokenOk(token + "x", token))
        assertFalse(PhoneLoginServer.tokenOk(token.uppercase(), token))
        assertFalse(PhoneLoginServer.tokenOk("x", ""))
    }

    @Test fun `routes need the token`() {
        assertEquals(PhoneLoginServer.Route.PAGE, PhoneLoginServer.route("/$token", token))
        assertEquals(PhoneLoginServer.Route.PAGE, PhoneLoginServer.route("/$token/", token))
        assertEquals(PhoneLoginServer.Route.LOGIN, PhoneLoginServer.route("/$token/login", token))
        assertEquals(PhoneLoginServer.Route.LOGIN, PhoneLoginServer.route("/$token/login?x=1", token))
        assertNull(PhoneLoginServer.route("/", token))
        assertNull(PhoneLoginServer.route("/login", token))
        assertNull(PhoneLoginServer.route("/wrongtoken/login", token))
        assertNull(PhoneLoginServer.route("/$token/other", token))
        assertNull(PhoneLoginServer.route("/$token/login/extra", token))
        assertNull(PhoneLoginServer.route("/favicon.ico", token))
    }

    @Test fun `random tokens are long and differ`() {
        val a = PhoneLoginServer.randomToken()
        val b = PhoneLoginServer.randomToken()
        assertEquals(PhoneLoginServer.TOKEN_LENGTH, a.length)
        assertTrue(a != b)
        assertTrue(a.all { it.isLetterOrDigit() })
    }

    @Test fun `parses url encoded forms`() {
        val f = PhoneLoginServer.parseForm("server=http%3A%2F%2Fexample.com%3A8080&username=john+doe&password=p%40ss%26word%3D1")
        assertEquals("http://example.com:8080", f["server"])
        assertEquals("john doe", f["username"])
        assertEquals("p@ss&word=1", f["password"])
    }

    @Test fun `parse form tolerates empty and odd pairs`() {
        assertTrue(PhoneLoginServer.parseForm("").isEmpty())
        val f = PhoneLoginServer.parseForm("a=&&b&=x&c=%ZZ")
        assertEquals("", f["a"])
        assertEquals("", f["b"])
        assertEquals("%ZZ", f["c"])
        assertFalse(f.containsKey(""))
    }

    @Test fun `server normalisation`() {
        assertEquals("http://example.com:8080", PhoneLoginServer.normalizeServer(" example.com:8080/ "))
        assertEquals("https://example.com", PhoneLoginServer.normalizeServer("https://example.com"))
        assertEquals("HTTP://example.com", PhoneLoginServer.normalizeServer("HTTP://example.com"))
        assertNull(PhoneLoginServer.normalizeServer(""))
        assertNull(PhoneLoginServer.normalizeServer(null))
        assertNull(PhoneLoginServer.normalizeServer("ftp://example.com"))
        assertNull(PhoneLoginServer.normalizeServer("http://"))
        assertNull(PhoneLoginServer.normalizeServer("exa mple.com"))
        assertNull(PhoneLoginServer.normalizeServer("javascript://alert(1)"))
    }

    @Test fun `validate requires all three fields`() {
        val ok = PhoneLoginServer.validate("host.tv:80", " user ", "pw").getOrNull()
        assertEquals(PhoneXtreamLogin("http://host.tv:80", "user", "pw"), ok)
        assertTrue(PhoneLoginServer.validate("", "u", "p").isFailure)
        assertTrue(PhoneLoginServer.validate("host.tv", "", "p").isFailure)
        assertTrue(PhoneLoginServer.validate("host.tv", "u", "").isFailure)
    }

    @Test fun `html escape`() {
        assertEquals("&lt;b&gt;&amp;&quot;&#39;", PhoneLoginServer.esc("<b>&\"'"))
    }
}
