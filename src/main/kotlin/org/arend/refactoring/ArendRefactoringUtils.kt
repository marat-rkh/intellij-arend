package org.arend.refactoring

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import org.arend.mapFirstNotNull
import org.arend.module.config.ArendModuleConfigService
import org.arend.prelude.Prelude
import org.arend.psi.*
import org.arend.psi.ext.ArendReferenceElement
import org.arend.psi.ext.PsiLocatedReferable
import org.arend.util.LongName
import java.util.Collections.singletonList

interface AbstractRefactoringAction {
    fun execute(editor: Editor?)
}

class ImportFileAction(private val importFile: ArendFile, private val currentFile: ArendFile, private val usingList: List<String>?) : AbstractRefactoringAction {
    override fun toString() = "Import file " + importFile.fullName

    private fun importFileCanBeFound(): Boolean {
        val modulePath = importFile.modulePath ?: return false
        val module = currentFile.module ?: return false
        return ArendModuleConfigService.getConfig(module).availableConfigs.mapFirstNotNull { it.findArendFile(modulePath) } == importFile
    }

    fun isValid() = importFileCanBeFound() || isPrelude(importFile)

    override fun execute(editor: Editor?) {
        val factory = ArendPsiFactory(importFile.project)
        val fullName = importFile.modulePath?.toString() ?: return

        var anchor: PsiElement = currentFile
        val relativePosition = if (currentFile.children.isEmpty()) PositionKind.INSIDE_EMPTY_ANCHOR else {
            anchor = currentFile.children[0]
            var after = false

            val currFileCommands = currentFile.namespaceCommands.filter { it.importKw != null }
            if (currFileCommands.isNotEmpty()) {
                val name = LongName(currFileCommands[0].path).toString()
                anchor = currFileCommands[0].parent
                if (fullName >= name)
                    after = true
            }

            if (after) for (nC in currFileCommands.drop(1)) {
                val name = LongName(nC.path).toString()
                if (fullName >= name)
                    anchor = nC.parent else break
            }

            if (after) PositionKind.AFTER_ANCHOR else PositionKind.BEFORE_ANCHOR
        }

        addStatCmd(factory, ArendPsiFactory.StatCmdKind.IMPORT, fullName, usingList?.map { Pair(it, null) }?.toList(), RelativePosition(relativePosition, anchor))
    }
}

class AddIdToUsingAction(private val statCmd: ArendStatCmd, private val idList: List<Pair<String, String?>>) : AbstractRefactoringAction {
    override fun toString(): String = "Add ${usingListToString(idList)} to the \"using\" list of the namespace command `${statCmd.text}`"

    private fun addId(id: String, newName: String?) {
        val project = statCmd.project
        val using = statCmd.nsUsing
        if (using != null) {
            val nsIds = using.nsIdList
            var anchor = using.lparen
            var needsCommaBefore = false

            for (nsId in nsIds) {
                val name = nsId.refIdentifier.referenceName
                if (name > id) break

                anchor = nsId
                needsCommaBefore = true
            }

            val factory = ArendPsiFactory(project)
            val nsIdStr = if (newName == null) id else "$id \\as $newName"
            val nsCmd = factory.createImportCommand("Dummy (a,$nsIdStr)", ArendPsiFactory.StatCmdKind.IMPORT).statCmd
            val newNsUsing = nsCmd!!.nsUsing!!
            val nsId = newNsUsing.nsIdList[1]

            if (nsId != null) {
                val comma = nsId.prevSibling //we will need the comma only once

                if (anchor == null) {
                    anchor = using.usingKw ?: error("Can't find anchor within namespace command")
                    anchor = anchor.parent.addAfter(newNsUsing.lparen!!, anchor)
                    anchor.parent.addBefore(factory.createWhitespace(" "), anchor)
                    anchor.parent.addAfter(newNsUsing.rparen!!, anchor)
                }

                if (anchor != null) {
                    if (!needsCommaBefore && !nsIds.isEmpty()) anchor.parent.addAfter(comma, anchor)
                    anchor.parent.addAfter(nsId, anchor)
                    if (needsCommaBefore) anchor.parent.addAfter(comma, anchor)
                }
            }
        }
    }

    override fun execute(editor: Editor?) {
        for (id in idList)
            addId(id.first, id.second)
    }
}

class RemoveRefFromStatCmdAction(private val statCmd: ArendStatCmd, val id: ArendRefIdentifier) : AbstractRefactoringAction {
    override fun toString(): String {
        val listType = when (id.parent) {
            is ArendStatCmd -> "\"hiding\" list"
            /* ArendNsUsing */ else -> "\"using\" list"
        }
        return "Remove " + id.referenceName + " from " + statCmdName(statCmd) + " import's $listType"
    }

    override fun execute(editor: Editor?) {
        val elementToRemove = if (id.parent is ArendNsId) id.parent else id
        val parent = elementToRemove.parent

        val prevSibling = elementToRemove.findPrevSibling()
        val nextSibling = elementToRemove.findNextSibling()

        elementToRemove.delete()

        if (prevSibling?.node?.elementType == ArendElementTypes.COMMA) {
            prevSibling?.delete()
        } else if (prevSibling?.node?.elementType == ArendElementTypes.LPAREN) {
            if (nextSibling?.node?.elementType == ArendElementTypes.COMMA) {
                nextSibling?.delete()
            }
        }

        if (parent is ArendStatCmd && parent.refIdentifierList.isEmpty()) { // This means that we are removing something from "hiding" list
            parent.lparen?.delete()
            parent.rparen?.delete()
            parent.hidingKw?.delete()
        }

        val statCmd = if (parent is ArendStatCmd) parent else {
            val grandParent = parent.parent
            if (grandParent is ArendStatCmd) grandParent else null
        }

        if (statCmd != null && statCmd.openKw != null) { //Remove open command with null effect
            val nsUsing = statCmd.nsUsing
            if (nsUsing != null && nsUsing.usingKw == null && nsUsing.nsIdList.isEmpty()) statCmd.delete()
        }
    }
}

class RenameReferenceAction(private val element: ArendReferenceElement, private val id: List<String>) : AbstractRefactoringAction {
    override fun toString(): String = "Rename " + element.text + " to " + LongName(id).toString()

    override fun execute(editor: Editor?) {
        val parent = element.parent
        val factory = ArendPsiFactory(element.project)
        val longNameStr = LongName(id).toString()
        val longNamePsi = factory.createLiteral(longNameStr).longName
        val offset = element.textOffset

        if (longNamePsi != null) {
            if (parent is ArendLongName) {
                parent.addRangeAfter(longNamePsi.firstChild, longNamePsi.lastChild, element)
                parent.deleteChildRange(parent.firstChild, element)
            } else if (parent is ArendPattern) {
                element.replace(longNamePsi)
            }
            editor?.caretModel?.moveToOffset(offset + longNameStr.length)
        }
    }
}

class ResolveReferenceAction(val target: PsiLocatedReferable,
                             private val targetFullName: List<String>,
                             private val statCmdFixAction: AbstractRefactoringAction?,
                             private val nameFixAction: AbstractRefactoringAction?) : AbstractRefactoringAction {

    override fun toString(): String = LongName(targetFullName).toString() + ((target.containingFile as? ArendFile)?.modulePath?.let { " in $it" } ?: "")

    override fun execute(editor: Editor?) {
        statCmdFixAction?.execute(editor)
        nameFixAction?.execute(editor)
    }
}

fun isPrelude(file: ArendFile) = file.modulePath == Prelude.MODULE_PATH && file.containingDirectory == null

fun statCmdName(statCmd: ArendStatCmd) =
        (statCmd.longName?.refIdentifierList?.lastOrNull()?.reference?.resolve() as? ArendFile)?.modulePath?.toString()
                ?: "???"

fun usingListToString(usingList: List<Pair<String, String?>>?): String {
    if (usingList == null) return ""
    val buffer = StringBuffer()
    buffer.append("(")
    for ((m, entry) in usingList.withIndex()) {
        buffer.append(entry.first + (if (entry.second == null) "" else " \\as ${entry.second}"))
        if (m < usingList.size - 1) buffer.append(", ")
    }
    buffer.append(")")
    return buffer.toString()
}

fun addStatCmd(factory: ArendPsiFactory, command: ArendPsiFactory.StatCmdKind, fullName: String, usingList: List<Pair<String, String?>>?, relativePosition: RelativePosition): PsiElement {
    val commandStatement = factory.createImportCommand(fullName + " " + usingListToString(usingList), command)
    val insertedStatement: PsiElement

    when (relativePosition.kind) {
        PositionKind.BEFORE_ANCHOR -> {
            insertedStatement = relativePosition.anchor.parent.addBefore(commandStatement, relativePosition.anchor)
            relativePosition.anchor.parent.addAfter(factory.createWhitespace("\n"), insertedStatement)
        }
        PositionKind.AFTER_ANCHOR -> {
            insertedStatement = relativePosition.anchor.parent.addAfter(commandStatement, relativePosition.anchor)
            relativePosition.anchor.parent.addAfter(factory.createWhitespace("\n"), relativePosition.anchor)
            relativePosition.anchor.parent.addAfter(factory.createWhitespace(" "), insertedStatement)
        }
        PositionKind.INSIDE_EMPTY_ANCHOR -> {
            insertedStatement = relativePosition.anchor.add(commandStatement)
        }
    }
    return insertedStatement
}

fun addIdToUsing(groupMember: PsiElement?,
                 targetContainer: PsiElement,
                 targetContainerName: String,
                 renamings: List<Pair<String, String?>>,
                 factory: ArendPsiFactory,
                 relativePosition: RelativePosition) {
    val siblingNsCmds = groupMember?.parent?.children?.filterIsInstance<ArendStatCmd>()?.filter {
        if (it.openKw != null) {
            val ref = it.longName?.refIdentifierList?.lastOrNull()
            if (ref != null) {
                val target = ref.reference?.resolve()
                target == targetContainer
            } else false
        } else false
    }

    if (siblingNsCmds != null && siblingNsCmds.isNotEmpty()) AddIdToUsingAction(siblingNsCmds.first(), renamings).execute(null)
    else addStatCmd(factory, ArendPsiFactory.StatCmdKind.OPEN,
            targetContainerName, renamings, relativePosition)
}

fun getImportedNames(namespaceCommand: ArendStatCmd, shortName: String?): List<Pair<String, ArendNsId?>> {
    if (shortName == null) return emptyList()

    val nsUsing = namespaceCommand.nsUsing
    val isHidden = namespaceCommand.refIdentifierList.any { it.referenceName == shortName }

    if (nsUsing != null) {
        val resultList = ArrayList<Pair<String, ArendNsId?>>()

        for (nsId in nsUsing.nsIdList) {
            if (nsId.refIdentifier.text == shortName) {
                val defIdentifier = nsId.defIdentifier
                resultList.add(Pair(defIdentifier?.textRepresentation() ?: shortName, nsId))
            }
        }

        if (nsUsing.usingKw == null) return resultList
    }

    return if (isHidden) emptyList() else singletonList(Pair(shortName, null))
}