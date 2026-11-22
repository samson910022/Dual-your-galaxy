// MIT License - Original code by Galaxy-Dual-app-Plus contributors. No GPL content.
//
// E07 (PR #74 round 4): the hook-restart hint must be HONEST in every locale.
// It is an app-local advisory derived only from the App's own Invalid->v1
// repair decision — NEVER a guarantee about hook/device behavior (issue #75).
package com.galaxydual.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class RepairRestartHintStringTest {
    private fun template(directory: String): String {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$directory/strings.xml"))
        val strings = document.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val node = strings.item(i)
            if (node.attributes.getNamedItem("name").nodeValue == "policy_repair_hook_restart_required") {
                return node.textContent
            }
        }
        error("missing policy_repair_hook_restart_required in $directory")
    }

    @Test
    fun allLocales_hedgeRestartHint_noGuaranteeClaim() {
        val directories = listOf("values", "values-en", "values-b+zh+Hant")
        directories.forEach { dir ->
            val text = template(dir)
            assertFalse(
                "$dir must NOT claim a guaranteed effect after restart: $text",
                text.contains("才保證生效") || text.contains("保證生效") || text.contains("guaranteed", ignoreCase = true),
            )
            assertTrue(
                "$dir must include 'restart to verify': $text",
                text.contains("重新啟動 Hook 行程後再驗證") ||
                    text.contains("Restart the Hook process", ignoreCase = true),
            )
            assertTrue(
                "$dir must include a non-proof / may-not-have-applied disclaimer: $text",
                text.contains("這不代表新政策已套用") ||
                    text.contains("not proof", ignoreCase = true),
            )
        }
        // Default and zh-Hant must stay byte-identical (existing project contract).
        assertTrue(
            "default and zh-Hant hint strings must match",
            template("values") == template("values-b+zh+Hant"),
        )
    }
}
