package dev.snapbudget.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class MerchantCategoryCatalogTest {
    @Test fun normalizesCaseAndWhitespaceUsingRootLocaleWhileKeepingPunctuationDistinct() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("istanbul cafe", MerchantCategoryCatalog.key("  ISTANBUL\t  CAFE  "))
            assertEquals("acme, inc.", MerchantCategoryCatalog.key(" Acme,   Inc. "))
            assertEquals("acme inc.", MerchantCategoryCatalog.key("Acme Inc."))
            assertEquals("a-b", MerchantCategoryCatalog.key("A-B"))
            assertEquals("a b", MerchantCategoryCatalog.key("A B"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
