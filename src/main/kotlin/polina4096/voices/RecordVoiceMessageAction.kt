package polina4096.voices

import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.project.stateStore
import com.intellij.psi.PsiDocumentManager
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.io.File
import javax.sound.sampled.*
import javax.swing.*
import kotlin.concurrent.thread

class RecordVoiceMessageAction : AnAction() {
    override fun update(e: AnActionEvent) {
        val project: Project? = e.project
        val editor: Editor? = e.getData(CommonDataKeys.EDITOR)

        var menuAllowed = false
        if (editor != null && project != null) {
            menuAllowed = editor.caretModel.allCarets.isNotEmpty()
        }

        e.presentation.isEnabled = menuAllowed
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun getCommentPrefix(project: Project, document: Document): String {
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
        val language: Commenter? = LanguageCommenters.INSTANCE.forLanguage(psiFile?.language ?: Language.ANY)

        return language?.lineCommentPrefix?.trim() ?: "//"
    }

    override fun actionPerformed(e: AnActionEvent) = object : DialogWrapper(e.project!!) {
        val basePath = e.project!!.stateStore.projectBasePath
        val tempPath = basePath.resolve(".idea/recording.wav")
        var isRecording = false

        val format = AudioFormat(48000.0f, 16, 1, true, false)
        val info = DataLine.Info(TargetDataLine::class.java, format)
        val microphone = (AudioSystem.getLine(info) as TargetDataLine)
            .apply { open(format, this.bufferSize) }

        val out = AudioInputStream(microphone)

        init {
            title = "Record a Voice Message"
            init()
        }

        override fun doOKAction() {
            val project = e.project!!

            val name = java.time.Instant.now().toEpochMilli()
            val path = ".idea/${name}.wav"
            val dest = File(basePath.resolve(path).toUri())
            if (!File(tempPath.toUri()).renameTo(dest)) {
                project.error("Failed to record voice message!")
                return
            }

            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: run { close(OK_EXIT_CODE); return }

            val startOffset = editor.caretModel.offset
            val line = StringUtil.offsetToLineNumber(editor.document.text, startOffset)

            WriteCommandAction.runWriteCommandAction(e.project!!) {
                val prefix = getCommentPrefix(project, editor.document)
                editor.document.insertString(startOffset, "$prefix voice:${path}")

                val key = TextAttributesKey.createTextAttributesKey("voice_message")
                val highlighter = editor.markupModel.addLineHighlighter(key, line, HighlighterLayer.FIRST)

                val renderer = VoiceFoldRegionRenderer(editor, dest, startOffset)
                if (renderer != null) {
                    editor.makeVoiceFold(highlighter, renderer)
                    editor.makeVoiceComment(line) { renderer }
                }
            }

            close(OK_EXIT_CODE)
        }

        override fun doCancelAction() {
            if (microphone.isActive)
                microphone.stop()

            if (microphone.isOpen)
                microphone.close()

            close(CANCEL_EXIT_CODE)
        }

        override fun createLeftSideActions(): Array<Action> {
            return arrayOf(
                object : AbstractAction("Start recording") {
                    override fun isEnabled(): Boolean = AudioSystem.isLineSupported(microphone.lineInfo)

                    override fun actionPerformed(e: ActionEvent?) {
                        if (!isRecording) {
                            isRecording = true
                            isOKActionEnabled = false
                            putValue(Action.NAME, "Stop recording")

                            microphone.open()
                            microphone.start()

                            thread { AudioSystem.write(out, AudioFileFormat.Type.WAVE, File(tempPath.toUri())) }
                        } else {
                            isOKActionEnabled = true
                            isRecording = false
                            putValue(Action.NAME, "Start recording")

                            microphone.stop()
                            microphone.close()
                        }
                    }
                }
            )
        }

        override fun createCenterPanel(): JComponent {
            return JPanel(BorderLayout()).apply { preferredSize = Dimension(320, 0) }
        }
    }.show()
}
