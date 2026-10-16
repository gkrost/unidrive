package org.krost.unidrive.internxt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The status of an API error reaches ProviderException.httpStatus, which the status report reads as http_<status>.
class InternxtApiExceptionStatusTest {
    @Test
    fun `an API error passes its status up`() {
        assertEquals(408, InternxtApiException("API error: 408 Request Timeout", statusCode = 408).httpStatus)
    }

    @Test
    fun `a failure without a response carries no status`() {
        assertNull(InternxtApiException("Connection error", 0, cause = java.io.IOException("x")).httpStatus)
        assertNull(InternxtApiException("Server closed connection", 503, cause = java.io.EOFException()).httpStatus)
    }

    @Test
    fun `an unreadable answer carries no status`() {
        assertNull(InternxtUnreadableResponse("cannot be read", null, RuntimeException()).httpStatus)
    }
}
