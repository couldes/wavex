package com.wavex.agent.feature.catalog

import com.wavex.agent.feature.catalog.ui.SearchSettingsText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSettingsTextTest {

    @Test
    fun `source line names bing as built in`() {
        assertTrue(SearchSettingsText.SOURCE_LINE.contains("Bing"))
        assertTrue(SearchSettingsText.SOURCE_LINE.contains("内置"))
    }

    @Test
    fun `local note states traffic does not pass the relay`() {
        assertEquals("搜索请求由本机发起，不经过中转站", SearchSettingsText.LOCAL_NOTE)
    }
}
