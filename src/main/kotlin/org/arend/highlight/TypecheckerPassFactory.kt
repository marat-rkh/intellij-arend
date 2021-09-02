package org.arend.highlight

import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.codeInsight.daemon.impl.DefaultHighlightInfoProcessor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import org.arend.psi.ArendFile
import org.arend.psi.listener.ArendPsiChangeService

class TypecheckerPassFactory : BasePassFactory<ArendFile>(ArendFile::class.java), TextEditorHighlightingPassFactoryRegistrar {
    private var myPassId = -1

    override fun registerHighlightingPassFactory(registrar: TextEditorHighlightingPassRegistrar, project: Project) {
        val service = project.service<ArendPassFactoryService>()
        myPassId = registrar.registerTextEditorHighlightingPass(this, intArrayOf(service.highlightingPassId, service.backgroundTypecheckerPassId), null, false, -1)
    }

    override fun allowWhiteSpaces() = true

    //\open Nat (+)
    //
    //{-
    //- Here is what happens when we modify code:
    //- 1. Both ArendHighlightingPassFactory and TypecheckerPassFactory are called.
    //- 2. ArendHighlightingPassFactory creates ArendHighlightingPass. That pass starts BackgroundTypechecker
    //- in applyInformationWithProgress. BackgroundTypechecker typechecks the file and marks it as "definitions modified".
    //- 3. TypecheckerPassFactory checks "definitions modified" flag. If it is true, it creates TypecheckerPass, which
    //- applies errors found by BackgroundTypechecker to the editor and ArendMessagesView. Otherwise, it only
    //- updates ArendMessagesView.
    //-
    //- Here is why the bug happens:
    //- 1. TypecheckerPassFactory sees "definitions modified" is false and updates ArendMessagesView. This action invalidates
    //- the editor in ArendMessagesView.
    //- 2. ArendHighlightingPass.applyInformationWithProgress is no executed because some editor was invalidated.
    //- As a result, BackgroundTypechecker is not started and "definitions modified" flag is not updated.
    //-}
    //
    //\func f : Nat => {?}
    override fun createPass(file: ArendFile, editor: Editor, textRange: TextRange) =
        if (file.lastDefinitionModification >= file.project.service<ArendPsiChangeService>().definitionModificationTracker.modificationCount || ApplicationManager.getApplication().isUnitTestMode) {
            TypecheckerPass(file, editor, DefaultHighlightInfoProcessor())
        } else {
            // If we comment out this line, editor is updated as needed,
            // but the right panel of the "Arend Errors" is not updated.
            TypecheckerPass.updateErrors(file)
            null
        }

    override fun getPassId() = myPassId
}