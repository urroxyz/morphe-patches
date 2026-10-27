package xyz.urro.gboard

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.*
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import org.w3c.dom.Document
import org.w3c.dom.Element

internal const val ANDROID = "http://schemas.android.com/apk/res/android"
internal val gboardCompatibility = Compatibility(
    name = "Gboard", packageName = "com.google.android.inputmethod.latin",
    targets = listOf(AppTarget(version = "18.0.3.954559732-release-arm64-v8a")),
)

internal fun Element.android(name: String, value: String) = setAttributeNS(ANDROID, "android:$name", value)

/** Add a section without replacing the native settings screen or other patch entries. */
internal fun addSection(document: Document, title: String, key: String): Element {
    val entries = document.getElementsByTagName("*")
    for (i in entries.length - 1 downTo 0) {
        val element = entries.item(i) as Element
        if (element.getAttributeNS(ANDROID, "key") == key) element.parentNode.removeChild(element)
    }
    return document.createElement("androidx.preference.PreferenceCategory").also {
        it.android("key", key); it.android("title", title); document.documentElement.appendChild(it)
    }
}

internal fun Element.preference(title: String, key: String, summary: String = "", toggle: Boolean? = null) {
    val entry = ownerDocument.createElement(if (toggle == null) "androidx.preference.Preference" else "androidx.preference.SwitchPreferenceCompat")
    entry.android("key", key); entry.android("title", title); entry.android("persistent", "false")
    if (summary.isNotEmpty()) entry.android("summary", summary)
    if (toggle != null) entry.android("defaultValue", toggle.toString())
    appendChild(entry)
}

internal fun removeOldSettings(document: Document) {
    val entries = document.getElementsByTagName("*")
    for (i in entries.length - 1 downTo 0) {
        val element = entries.item(i) as Element
        if (element.getAttributeNS(ANDROID, "key") in setOf("urro_clipcascade", "urro_translation_shortcuts")) element.parentNode.removeChild(element)
    }
}

internal val gboardSettingsHook = bytecodePatch {
    compatibleWith(gboardCompatibility)
    extendWith("extensions/gboard.mpe")
    execute {
        val method = Fingerprint(definingClass = "Landroidx/preference/Preference;", name = "I", returnType = "V", parameters = emptyList()).method
        check(method.implementation!!.registerCount >= 2) { "No local register in Gboard preference handler" }
        method.addInstructions(0, """
            invoke-static/range {p0 .. p0}, Lxyz/urro/gboard/SettingsRoutes;->open(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :urro_existing_preference
            return-void
            :urro_existing_preference
            nop
        """)
        val read = Fingerprint(definingClass = "Landroidx/preference/Preference;", name = "aa", returnType = "Z", parameters = listOf("Z")).method
        read.addInstructions(0, "invoke-static/range {p0 .. p1}, Lxyz/urro/gboard/SettingsRoutes;->read(Ljava/lang/Object;Z)Z\nmove-result p1")
        val write = Fingerprint(definingClass = "Landroidx/preference/Preference;", name = "ad", returnType = "Z", parameters = listOf("Z")).method
        write.addInstructions(0, "invoke-static/range {p0 .. p1}, Lxyz/urro/gboard/SettingsRoutes;->write(Ljava/lang/Object;Z)V")
        val bind = Fingerprint(definingClass = "Landroidx/preference/Preference;", name = "a", returnType = "V", parameters = listOf("Lcdz;")).method
        bind.addInstructions(0, "invoke-static/range {p0 .. p0}, Lxyz/urro/gboard/SettingsRoutes;->bind(Ljava/lang/Object;)V")
    }
}

internal val translationResources = resourcePatch {
    compatibleWith(gboardCompatibility)
    finalize {
        for (file in listOf("res/xml/settings.xml", "res/xml/settings_legacy.xml")) document(file).use(::removeOldSettings)
        document("res/xml/setting_translate.xml").use { xml ->
            val section = addSection(xml, "Translation shortcuts", "urro_translation_section")
            section.preference("Simplify input field", "urro_simplify_translation_input", "Use a compact language-pair field. Reopen Translate after changing this.", false)
            section.preference("Language codes", "urro_translation_help", "Type es or en-es, then a space to confirm. ISO and regional codes work too.")
            (section.lastChild as Element).android("selectable", "false")
        }
    }
}

private const val KEYBOARD = "Lcom/google/android/apps/inputmethod/libs/translate/TranslateKeyboard;"
private const val HOOK = "Lxyz/urro/gboard/TranslationHooks;"
private val attach = Fingerprint(definingClass = KEYBOARD, name = "k", returnType = "V", parameters = emptyList())
private val render = Fingerprint(definingClass = KEYBOARD, name = "l", returnType = "V", parameters = emptyList())
private val editor = Fingerprint(definingClass = KEYBOARD, name = "D", returnType = "Loqa;", parameters = listOf("Landroid/view/inputmethod/EditorInfo;"))

@Suppress("unused")
val translationShortcutsPatch = bytecodePatch(
    name = "Translation language shortcuts",
    description = "Changes translation languages with ISO/BCP language codes followed by whitespace. Adds an optional compact language-pair editor in Gboard settings.",
    default = false,
) {
    compatibleWith(gboardCompatibility)
    dependsOn(translationResources, gboardSettingsHook)
    extendWith("extensions/gboard.mpe")
    execute {
        // Reflection in the extension is version-bound; verify the native API before modifying it.
        for ((type, methods) in mapOf(
            "Lkas;" to listOf("b"), "Lkap;" to listOf("d", "f", "h"),
            "Lkbd;" to listOf("ag", "ai"), "Lgve;" to emptyList(),
        )) {
            val target = classDefBy(type)
            check(methods.all { name -> target.methods.any { it.name == name } }) { "Unsupported Gboard translation API: $type" }
        }
        // Stock methods reuse parameter registers. A wrapper preserves the receiver and
        // runs our callback only after Gboard has finished binding or updating its UI.
        fun afterNative(method: app.morphe.patcher.util.proxy.mutableTypes.MutableMethod, callback: String) {
            val owner = mutableClassDefBy(method.definingClass)
            val nativeName = "urroNative_" + method.name
            check(owner.methods.none { it.name == nativeName }) { "Translation hook already installed" }
            val native = ImmutableMethod(
                method.definingClass, nativeName, method.parameters, method.returnType,
                method.accessFlags, method.annotations, method.hiddenApiRestrictions, method.implementation,
            ).toMutable()
            val wrapper = ImmutableMethod(
                method.definingClass, method.name, method.parameters, method.returnType,
                method.accessFlags, method.annotations, method.hiddenApiRestrictions,
                ImmutableMethodImplementation(1, emptyList(), emptyList(), emptyList()),
            ).toMutable()
            wrapper.addInstructions(0, """
                invoke-virtual {p0}, ${method.definingClass}->$nativeName()V
                invoke-static {p0}, $HOOK->$callback(Ljava/lang/Object;)V
                return-void
            """)
            check(owner.methods.remove(method))
            owner.methods.add(native)
            owner.methods.add(wrapper)
        }
        afterNative(attach.method, "attach")
        afterNative(render.method, "render")
        afterNative(Fingerprint(definingClass = "Lkan;", name = "c", returnType = "V", parameters = emptyList()).method, "renderUi")
        // Embedded Gboard editors pretend to be focused whenever enabled. Restrict
        // that behavior to the selected editor only when our compact UI is attached.
        val embeddedEditor = "Lcom/google/android/libraries/inputmethod/widgets/EditTextOnKeyboard;"
        for (name in listOf("isFocused", "hasFocus", "hasWindowFocus")) {
            val original = Fingerprint(definingClass = embeddedEditor, name = name, returnType = "Z", parameters = emptyList()).method
            val nativeName = "urroNative_" + name
            val owner = mutableClassDefBy(embeddedEditor)
            val native = ImmutableMethod(original.definingClass, nativeName, original.parameters,
                original.returnType, original.accessFlags, original.annotations,
                original.hiddenApiRestrictions, original.implementation).toMutable()
            val wrapper = ImmutableMethod(original.definingClass, original.name, original.parameters,
                original.returnType, original.accessFlags, original.annotations,
                original.hiddenApiRestrictions, ImmutableMethodImplementation(2, emptyList(), emptyList(), emptyList())).toMutable()
            wrapper.addInstructions(0, """
                invoke-static {p0}, $HOOK->focusState(Ljava/lang/Object;)I
                move-result v0
                if-ltz v0, :native_focus
                return v0
                :native_focus
                invoke-virtual {p0}, $embeddedEditor->$nativeName()Z
                move-result v0
                return v0
            """)
            check(owner.methods.remove(original))
            owner.methods.add(native)
            owner.methods.add(wrapper)
        }
        val method = editor.method
        for ((index, instruction) in method.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.asReversed()) {
            val register = (instruction as OneRegisterInstruction).registerA
            method.addInstructions(index, """
                invoke-static/range {v$register .. v$register}, $HOOK->editor(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$register
                check-cast v$register, Loqa;
            """)
        }
    }
}
