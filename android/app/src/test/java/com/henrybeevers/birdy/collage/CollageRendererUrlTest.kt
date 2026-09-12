package com.henrybeevers.birdy.collage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollageRendererUrlTest {

    @Test
    fun `only http and https thumbnail urls are fetched`() {
        assertTrue(CollageRenderer.isHttpUrl("https://cdn.example/a.jpg"))
        assertTrue(CollageRenderer.isHttpUrl("HTTP://cdn.example/a.jpg"))
        assertFalse(CollageRenderer.isHttpUrl(""))
        assertFalse(CollageRenderer.isHttpUrl("file:///sdcard/x.jpg"))
        assertFalse(CollageRenderer.isHttpUrl("javascript:alert(1)"))
        assertFalse(CollageRenderer.isHttpUrl("data:image/png;base64,xx"))
    }
}
