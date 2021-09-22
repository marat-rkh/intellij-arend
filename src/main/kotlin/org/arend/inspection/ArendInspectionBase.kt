package org.arend.inspection

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiElementVisitor
import com.intellij.util.castSafelyTo
import org.arend.injection.InjectedArendEditor
import org.arend.psi.ArendFile
import org.arend.psi.ArendVisitor

abstract class ArendInspectionBase : LocalInspectionTool() {
    final override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        val injectionContextFile = holder.file.castSafelyTo<ArendFile>()?.injectionContext?.containingFile
        return if (InjectedArendEditor.isInjectedEditorFile(injectionContextFile)) PsiElementVisitor.EMPTY_VISITOR
        else buildArendVisitor(holder, isOnTheFly)
    }

    abstract fun buildArendVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): ArendVisitor
}