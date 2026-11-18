package com.rutgersmit.frigastreamer

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards the localization: every translatable string in the default (English) resources
 * must have a Dutch translation, and the Dutch file must not contain stale keys.
 */
class StringResourcesTest {

    private fun resFile(dir: String): File {
        // Unit tests run with the module directory (app/) as working directory.
        val f = File("src/main/res/$dir/strings.xml")
        assertTrue("missing ${f.absolutePath}", f.isFile)
        return f
    }

    /** Names of translatable <string> and <plurals> entries, with a type prefix. */
    private fun keys(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = sortedSetOf<String>()
        for (tag in listOf("string", "plurals")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as Element
                if (e.getAttribute("translatable") == "false") continue
                result += "$tag/${e.getAttribute("name")}"
            }
        }
        return result
    }

    @Test
    fun `dutch translation covers every english key`() {
        val en = keys(resFile("values"))
        val nl = keys(resFile("values-nl"))
        assertTrue("no strings found", en.isNotEmpty())
        assertEquals("missing in values-nl", emptySet<String>(), en - nl)
        assertEquals("stale keys in values-nl", emptySet<String>(), nl - en)
    }
}
