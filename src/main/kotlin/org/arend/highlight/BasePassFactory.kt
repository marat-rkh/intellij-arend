package org.arend.highlight

import com.intellij.codeHighlighting.DirtyScopeTrackingHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeInsight.daemon.impl.FileStatusMap
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiUtil

/**
 * If changed element is not a comment or whitespace, create usual pass. Otherwise, create [EmptyHighlightingPass].
 */
abstract class BasePassFactory<T : PsiFile>(private val clazz: Class<T>) : DirtyScopeTrackingHighlightingPassFactory {
    abstract fun createPass(file: T, editor: Editor, textRange: TextRange): TextEditorHighlightingPass?

    protected open fun allowWhiteSpaces() = false

    override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
        if (!clazz.isInstance(file)) {
            return null
        }

        val textRange = FileStatusMap.getDirtyTextRange(editor, passId)
        return if (textRange == null) {
            EmptyHighlightingPass(file.project, editor.document)
        } else {
            val psi = PsiUtil.getElementInclusiveRange(file, textRange)
            if (psi !is PsiComment && (psi !is PsiWhiteSpace || allowWhiteSpaces())) {
                /*
                var group: ArendGroup = file
                while (psi is ArendCompositeElement && psi !is ArendFile) {
                    if (psi is ArendGroup) {
                        group = psi
                        break
                    }
                    psi = psi.parent
                }
                */
                createPass(clazz.cast(file), editor, textRange)
            } else {
                EmptyHighlightingPass(file.project, editor.document)
            }
        }
    }
}
