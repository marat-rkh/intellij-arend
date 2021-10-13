package org.arend.util

import com.intellij.psi.PsiElement
import com.intellij.util.castSafelyTo
import org.arend.ArendTestBase
import org.arend.psi.ArendArgumentAppExpr
import org.arend.psi.childOfType
import org.arend.psi.ext.ArendFunctionalDefinition
import org.arend.psi.parentOfType
import org.arend.term.concrete.Concrete

class ArendBinOpUtilsTest : ArendTestBase() {
    fun `test parse bin op seq of class field alias`() {
        InlineFile("""
            \class M {
              | o \alias \infixr 5 ∘ : Nat -> Nat -> Nat
            }
            \func test {m : M} => 1 ∘ 2
        """.trimIndent())
        val element = myFixture.findElementByText("test", PsiElement::class.java)
        val appExprPsi = element.parentOfType<ArendFunctionalDefinition>()?.body?.expr?.childOfType<ArendArgumentAppExpr>()!!
        val appExpr = appExprToConcrete(appExprPsi) as Concrete.AppExpression
        val functionText = appExpr.function.data.castSafelyTo<PsiElement>()?.text!!
        val firstArgText = appExpr.arguments[0].expression.data.castSafelyTo<PsiElement>()?.text!!
        val secondArgText = appExpr.arguments[1].expression.data.castSafelyTo<PsiElement>()?.text!!
        assertEquals("1 ∘ 2", "$firstArgText $functionText $secondArgText")
    }
}