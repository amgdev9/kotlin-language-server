package org.javacs.kt

import org.javacs.kt.javaToKotlin.convertJavaToKotlin
import org.junit.Test
import org.junit.Assert.assertThat
import org.junit.Ignore
import org.hamcrest.Matchers.equalTo
import org.javacs.kt.lsp.KotlinLanguageServer

class JavaToKotlinTest : LanguageServerTestFixture("j2k") {
    // TODO: Seems to throw the same exception as
    // https://github.com/Kotlin/dokka/issues/1660 currently
    @Ignore @Test fun `test j2k conversion`() {
        val javaCode = workspaceRoot
            .resolve("JavaJSONConverter.java")
            .toFile()
            .readText()
            .trim()
        val expectedKotlinCode = workspaceRoot
            .resolve("JavaJSONConverter.kt")
            .toFile()
            .readText()
            .trim()
            .replace("\r\n", "\n")
        val compiler = KotlinLanguageServer.classPath.compiler
        val convertedKotlinCode = convertJavaToKotlin(javaCode, compiler).replace("\r\n", "\n")
        assertThat(convertedKotlinCode, equalTo(expectedKotlinCode))
    }
}
