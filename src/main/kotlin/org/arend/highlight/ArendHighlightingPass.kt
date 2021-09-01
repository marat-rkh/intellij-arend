package org.arend.highlight

import com.intellij.codeInsight.daemon.impl.HighlightInfoProcessor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.TextRange
import org.arend.naming.reference.*
import org.arend.naming.resolving.visitor.DefinitionResolveNameVisitor
import org.arend.psi.*
import org.arend.psi.ext.ArendIPNameImplMixin
import org.arend.psi.ext.ArendReferenceElement
import org.arend.psi.ext.PsiLocatedReferable
import org.arend.psi.ext.TCDefinition
import org.arend.psi.ext.impl.ReferableAdapter
import org.arend.psi.listener.ArendPsiChangeService
import org.arend.quickfix.implementCoClause.IntentionBackEndVisitor
import org.arend.resolving.ArendReferableConverter
import org.arend.resolving.ArendResolverListener
import org.arend.resolving.PsiConcreteProvider
import org.arend.term.concrete.Concrete
import org.arend.term.group.Group
import org.arend.typechecking.BackgroundTypechecker
import org.arend.typechecking.PsiInstanceProviderSet
import org.arend.typechecking.TypeCheckingService
import org.arend.typechecking.TypecheckingTaskQueue
import org.arend.typechecking.error.ErrorService
import org.arend.typechecking.execution.PsiElementComparator
import org.arend.typechecking.order.Ordering
import org.arend.typechecking.order.listener.CollectingOrderingListener

class ArendHighlightingPass(file: ArendFile, editor: Editor, textRange: TextRange, highlightInfoProcessor: HighlightInfoProcessor)
    : BasePass(file, editor, "Arend resolver annotator", textRange, highlightInfoProcessor) {

    private val psiListenerService = myProject.service<ArendPsiChangeService>()
    private val concreteProvider = PsiConcreteProvider(myProject, this, null, false)
    private val instanceProviderSet = PsiInstanceProviderSet()
    private val collector1 = CollectingOrderingListener()
    private val collector2 = CollectingOrderingListener()
    private var lastModifiedDefinition: TCDefReferable? = null
    private val lastDefinitionModification = psiListenerService.definitionModificationTracker.modificationCount
    var lastModification: Long = 0

    init {
        myProject.service<TypeCheckingService>().initialize()
    }

    override fun collectInformationWithProgress(progress: ProgressIndicator) {
        if (myProject.service<TypeCheckingService>().isLoaded) {
            setProgressLimit(numberOfDefinitions(file).toLong())
            collectInfo(progress)
        }
    }

    private fun numberOfDefinitions(group: Group): Int {
        val def = group.referable
        var res = if (def is TCDefinition) 1 else 0

        for (subgroup in group.subgroups) {
            res += numberOfDefinitions(subgroup)
        }
        for (subgroup in group.dynamicSubgroups) {
            res += numberOfDefinitions(subgroup)
        }
        return res
    }

    private fun collectInfo(progress: ProgressIndicator) {
        val definitions = ArrayList<Concrete.Definition>()
        DefinitionResolveNameVisitor(concreteProvider, ArendReferableConverter, this, object : ArendResolverListener(myProject.service()) {
            override fun resolveReference(data: Any?, referent: Referable, list: List<ArendReferenceElement>, resolvedRefs: List<Referable?>) {
                val lastReference = list.lastOrNull() ?: return
                if ((lastReference is ArendRefIdentifier || lastReference is ArendDefIdentifier)) {
                    when {
                        (((referent as? RedirectingReferable)?.originalReferable ?: referent) as? MetaReferable)?.resolver != null ->
                            holder.createInfoAnnotation(lastReference, null).textAttributes = ArendHighlightingColors.META_RESOLVER.textAttributesKey
                        referent is GlobalReferable && referent.precedence.isInfix ->
                            holder.createInfoAnnotation(lastReference, null).textAttributes = ArendHighlightingColors.OPERATORS.textAttributesKey
                    }
                }

                var index = 0
                while (index < resolvedRefs.size - 1 && resolvedRefs[index] !is ErrorReference) {
                    index++
                }

                if (index > 0) {
                    val last = list[index]
                    val textRange = if (last is ArendIPNameImplMixin) {
                        last.parentLiteral?.let { literal ->
                            literal.longName?.let { longName ->
                                TextRange(longName.textRange.startOffset, (literal.dot ?: longName).textRange.endOffset)
                            }
                        }
                    } else {
                        (last.parent as? ArendLongName)?.let { longName ->
                            last.extendLeft.prevSibling?.let { nextToLast ->
                                TextRange(longName.textRange.startOffset, nextToLast.textRange.endOffset)
                            }
                        }
                    }

                    if (textRange != null) {
                        holder.createInfoAnnotation(textRange, null).textAttributes = ArendHighlightingColors.LONG_NAME.textAttributesKey
                    }
                }
            }

            private fun highlightParameters(definition: Concrete.GeneralDefinition) {
                for (parameter in Concrete.getParameters(definition, true) ?: emptyList()) {
                    if (((parameter.type?.underlyingReferable as? GlobalReferable)?.underlyingReferable as? ArendDefClass)?.isRecord == false) {
                        val list = when (val param = parameter.data) {
                            is ArendFieldTele -> param.fieldDefIdentifierList
                            is ArendNameTele -> param.identifierOrUnknownList
                            is ArendTypeTele -> param.typedExpr?.identifierOrUnknownList
                            else -> null
                        }
                        for (id in list ?: emptyList()) {
                            holder.createInfoAnnotation(id, null).textAttributes = ArendHighlightingColors.CLASS_PARAMETER.textAttributesKey
                        }
                    }
                }
            }

            override fun definitionResolved(definition: Concrete.ResolvableDefinition) {
                progress.checkCanceled()

                if (resetDefinition) {
                    (definition.data.underlyingReferable as? TCDefinition)?.let {
                        psiListenerService.updateDefinition(it, file, true)
                    }
                }

                (definition.data.underlyingReferable as? PsiLocatedReferable)?.let { ref ->
                    if (ref.containingFile == myFile) {
                        ref.nameIdentifier?.let {
                            holder.createInfoAnnotation(it, null).textAttributes = ArendHighlightingColors.DECLARATION.textAttributesKey
                        }
                        (ref as? ReferableAdapter<*>)?.getAlias()?.aliasIdentifier?.let {
                            holder.createInfoAnnotation(it, null).textAttributes = ArendHighlightingColors.DECLARATION.textAttributesKey
                        }
                    }
                }

                highlightParameters(definition)
                if (definition is Concrete.DataDefinition) {
                    for (constructorClause in definition.constructorClauses) {
                        for (constructor in constructorClause.constructors) {
                            highlightParameters(constructor)
                        }
                    }
                }

                definition.accept(IntentionBackEndVisitor(holder), null)
                if (definition is Concrete.Definition) {
                    definitions.add(definition)
                }

                advanceProgress(1)
            }
        }).resolveGroup(file, file.scope)

        concreteProvider.resolve = true

        val dependencyListener = myProject.service<TypeCheckingService>().dependencyListener
        val ordering = Ordering(instanceProviderSet, concreteProvider, collector1, dependencyListener, ArendReferableConverter, PsiElementComparator)
        val lastModified = file.lastModifiedDefinition?.let { concreteProvider.getConcrete(it) as? Concrete.Definition }
        if (lastModified != null) {
            lastModifiedDefinition = lastModified.data
            ordering.order(lastModified)
        }
        ordering.listener = collector2
        for (definition in definitions) {
            if (definition.data != lastModified?.data) {
                ordering.order(definition)
            }
        }
    }

    override fun applyInformationWithProgress() {
        synchronized(ArendHighlightingPass::class.java) {
            if (file.lastModification < lastModification) {
                file.lastModification = lastModification
            }
        }
        myProject.service<ErrorService>().clearNameResolverErrors(file)
        super.applyInformationWithProgress()

        if (collector1.isEmpty && collector2.isEmpty) {
            return
        }

        val typechecker = BackgroundTypechecker(myProject, instanceProviderSet, concreteProvider, lastDefinitionModification)
        if (ApplicationManager.getApplication().isUnitTestMode) {
            // DaemonCodeAnalyzer.restart does not work in tests
            typechecker.runTypechecker(file, lastModifiedDefinition, collector1, collector2, false)
        } else {
            myProject.service<TypecheckingTaskQueue>().addTask(lastDefinitionModification) {
                typechecker.runTypechecker(file, lastModifiedDefinition, collector1, collector2, true)
            }
        }
    }
}