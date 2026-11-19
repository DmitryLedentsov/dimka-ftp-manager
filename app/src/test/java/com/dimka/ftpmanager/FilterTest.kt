package com.dimka.ftpmanager

import com.dimka.ftpmanager.data.ImportFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterTest {
    @Test fun defaultFilterExcludesMediaAndSecrets() {
        val filter = ImportFilter("uploads/\n*.png\n.env\n*.log")
        assertTrue(filter.excluded("uploads/foo/bar.jpg", false))
        assertTrue(filter.excluded("app/logo.png", false))
        assertTrue(filter.excluded(".env", false))
        assertTrue(filter.excluded("var/error.log", false))
        assertFalse(filter.excluded("mg-plugins/imshop/imshop.php", false))
    }
}
