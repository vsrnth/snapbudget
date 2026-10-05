package dev.snapbudget.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryCatalogTest {
    @Test fun validatesTrimmedNamesAndRejectsUnsafeLengthsAndControls() {
        assertNull(CategoryCatalog.validationError("  Coffee  "))
        assertEquals("Enter a category name.", CategoryCatalog.validationError("   "))
        assertEquals("Use 60 characters or fewer.", CategoryCatalog.validationError("x".repeat(61)))
        assertEquals("Category names cannot contain control characters.", CategoryCatalog.validationError("Line\nBreak"))
    }

    @Test fun choicesKeepPredefinedOrderAndMergeCustomLegacyAndSelectedWithoutCaseDuplicates() {
        assertEquals(
            listOf("Food", "Groceries", "Transport", "Shopping", "Bills", "Health", "Entertainment", "Travel", "Education", "Other", "Coffee", "Legacy", "Current"),
            CategoryCatalog.choices(listOf("Coffee", "food"), listOf("Legacy", "COFFEE"), "Current"),
        )
        assertEquals("Food", CategoryCatalog.existingName(" fOoD ", CategoryCatalog.predefined))
    }
}
