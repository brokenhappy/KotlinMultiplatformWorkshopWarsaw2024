package kmpworkshop.client

import io.ktor.http.URLProtocol
import kotlin.test.Test
import kotlin.test.assertEquals

class ClientConnectionDefaultsTest {
    @Test
    fun `local servers use plain websocket`() {
        assertEquals(URLProtocol.WS, defaultWorkshopProtocol("127.0.0.1"))
        assertEquals(URLProtocol.WS, defaultWorkshopProtocol("localhost"))
        assertEquals(URLProtocol.WS, defaultWorkshopProtocol("::1"))
    }

    @Test
    fun `hosted servers use secure websocket`() {
        assertEquals(URLProtocol.WSS, defaultWorkshopProtocol("workshop.example.com"))
    }
}
