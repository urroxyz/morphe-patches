package xyz.urro.gboard

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.*
import com.android.tools.smali.dexlib2.Opcode
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import org.w3c.dom.Element
import java.net.URI

private object Assets
private fun jsonString(value: String): String = buildString {
    append('"')
    for (character in value) when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        else -> if (character.code < 0x20 || character in '\uD800'..'\uDFFF') append("\\u%04x".format(character.code)) else append(character)
    }
    append('"')
}
private const val HOOK = "Lxyz/urro/gboard/ClipboardHooks;"
private const val ADAPTER = "Lfjk;"

private val clipBytecode = bytecodePatch {
    compatibleWith(gboardCompatibility)
    dependsOn(gboardSettingsHook)
    extendWith("extensions/gboard.mpe")
    // Community clipboard patches verify the stock method shape during execute.
    // Add our adapter delegates after those transformations have completed.
    finalize {
        fun method(owner: String, name: String, returns: String, params: List<String>) = Fingerprint(definingClass = owner, name = name, returnType = returns, parameters = params).match(classDefBy(owner)).method
        val count = method(ADAPTER, "gu", "I", emptyList())
        // Keep the stock method's registers: expanding them moved `this` and broke the verifier.
        count.addInstructions(0, """
            invoke-static {p0}, $HOOK->countOf(Ljava/lang/Object;)I
            move-result v0
            return v0
        """)
        val originalType = method(ADAPTER, "gT", "I", listOf("I"))
        val originalImpl = originalType.implementation!!
        val type = if (originalImpl.registerCount == 2) {
            // This getter reuses both parameter registers; preserve their original slots.
            val expanded = ImmutableMethod(
                originalType.definingClass, originalType.name, originalType.parameters,
                originalType.returnType, originalType.accessFlags, originalType.annotations,
                originalType.hiddenApiRestrictions,
                ImmutableMethodImplementation(4, originalImpl.instructions, originalImpl.tryBlocks, originalImpl.debugItems),
            ).toMutable()
            val methods = mutableClassDefBy(ADAPTER).methods
            check(methods.remove(originalType) && methods.add(expanded)) { "Could not expand the clipboard type getter" }
            expanded
        } else originalType
        check(type.implementation!!.registerCount >= 3)
        val restore = if (originalImpl.registerCount == 2) "move-object v0, p0\nmove v1, p1" else "nop"
        type.addInstructions(0, """
            invoke-static/range {p0 .. p1}, $HOOK->type(Ljava/lang/Object;I)I
            move-result v0
            if-ltz v0, :urro_native_type
            return v0
            :urro_native_type
            $restore
        """)
        val create = method(ADAPTER, "d", "Lkr;", listOf("Landroid/view/ViewGroup;", "I"))
        check(create.implementation!!.registerCount >= 4)
        create.addInstructions(0, """
            invoke-static/range {p0 .. p2}, $HOOK->header(Ljava/lang/Object;Landroid/view/ViewGroup;I)Ljava/lang/Object;
            move-result-object v0
            if-eqz v0, :urro_native_header
            check-cast v0, Lkr;
            return-object v0
            :urro_native_header
            nop
        """)
        val bind = method(ADAPTER, "p", "V", listOf("Lkr;", "I"))
        check(bind.implementation!!.registerCount >= 4)
        val returns = bind.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }
        for ((index, _) in returns.asReversed()) bind.addInstructions(index, "invoke-static/range {p0 .. p2}, $HOOK->localBound(Ljava/lang/Object;Ljava/lang/Object;I)V")
        bind.addInstructions(0, """
            invoke-static/range {p0 .. p2}, $HOOK->bind(Ljava/lang/Object;Ljava/lang/Object;I)Z
            move-result v0
            if-eqz v0, :urro_native_bind
            return-void
            :urro_native_bind
            nop
        """)
        for (name in listOf("N", "L")) {
            val sticky = method(ADAPTER, name, "V", if (name == "L") listOf("Landroid/support/v7/widget/RecyclerView;") else emptyList())
            // Hook at entry (index 0) before stock bytecode clobbers p0/v4
            sticky.addInstructions(0, "invoke-static/range {p0 .. p0}, $HOOK->sticky(Ljava/lang/Object;)V")
        }
        val popup = method("Lfkw;", "b", "Landroid/view/View;", listOf("Landroid/view/View;"))
        for ((index, instruction) in popup.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.asReversed()) {
            val register = (instruction as OneRegisterInstruction).registerA
            popup.addInstructions(index, "move-object/from16 p1, v$register\ninvoke-static/range {p0 .. p1}, $HOOK->popup(Ljava/lang/Object;Landroid/view/View;)V")
        }
    }
}

@Suppress("unused")
val clipCascadePatch = resourcePatch(
    name = "ClipCascade sync",
    description = "Adds pooled remote clipboard history, images and Send to all devices. Supports ClipCascade P2S/P2P. Configure the required server/account patch options before patching.",
    default = false,
) {
    dependsOn(clipBytecode)
    compatibleWith(gboardCompatibility)
    val server by stringOption(key = "clipCascadeServer", default = "", title = "Server URL", description = "Required. ClipCascade server base URL, including https:// (or http:// on a trusted local network). No /login suffix.", required = true)
    val username by stringOption(key = "clipCascadeUsername", default = "", title = "Username", description = "Required. Your ClipCascade username.", required = true)
    val password by stringOption(key = "clipCascadePassword", default = "", title = "Password", description = "Required. Your original ClipCascade password. Credentials are stored in the patched APK; do not share it.", required = true)
    val maximumBytes by stringOption(key = "clipCascadeMaxBytes", default = "8388608", title = "Maximum clipboard size (bytes)", description = "Local limit for text/images, up to 16777216 bytes. The P2S server's limit also applies. Default: 8 MiB.")
    val encryption by booleanOption(key = "clipCascadeEncryption", default = true, title = "End-to-end encryption", description = "Must match the other clients.")
    val salt by stringOption(key = "clipCascadeSalt", default = "", title = "Encryption salt", description = "Must match the other clients; stock default is empty.")
    val iterations by stringOption(key = "clipCascadeIterations", default = "664937", title = "Key derivation iterations", description = "Must match the other clients. Stock default: 664937.")
    execute {
        val address = server?.trim().orEmpty().trimEnd('/')
        val uri = URI(address)
        check(uri.scheme in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Enter a valid ClipCascade base URL" }
        check(!username.isNullOrBlank() && !password.isNullOrEmpty()) { "ClipCascade username and password are required" }
        val localMaximum = maximumBytes?.toIntOrNull()
        check(localMaximum != null && localMaximum in 1..16777216) { "Clipboard size must be between 1 and 16777216 bytes" }
        val rounds = iterations?.toIntOrNull()
        check(rounds != null && rounds in 1..10000000) { "Encryption iterations must be between 1 and 10000000" }
        val asset = get("assets/urro_clipcascade.json")
        asset.parentFile.mkdirs()
        asset.writeText("{\"server\":${jsonString(address)},\"username\":${jsonString(username!!)}," +
            "\"password\":${jsonString(password!!)},\"encryption\":${encryption == true}," +
            "\"salt\":${jsonString(salt.orEmpty())},\"iterations\":$rounds,\"maxBytes\":$localMaximum}")
        Assets.javaClass.getResourceAsStream("/gboard/clipcascade.js")!!.use { get("assets/urro_clipcascade.js").writeBytes(it.readBytes()) }
        if (uri.scheme == "http") {
            document("AndroidManifest.xml").use { xml ->
                val app = xml.getElementsByTagName("application").item(0) as Element
                val existing = app.getAttributeNS(ANDROID, "networkSecurityConfig")
                if (existing.startsWith("@xml/")) {
                    document("res/xml/${existing.removePrefix("@xml/")}.xml").use { network ->
                        val rule = network.createElement("domain-config"); rule.setAttribute("cleartextTrafficPermitted", "true")
                        val domain = network.createElement("domain"); domain.textContent = uri.host
                        rule.appendChild(domain); network.documentElement.appendChild(rule)
                    }
                } else {
                    check(existing.isBlank()) { "Unsupported network-security config reference" }
                    get("res/xml/urro_clipcascade_network.xml").writeText("<network-security-config><base-config cleartextTrafficPermitted=\"false\"/><domain-config cleartextTrafficPermitted=\"true\"><domain>${uri.host}</domain></domain-config></network-security-config>")
                    app.android("networkSecurityConfig", "@xml/urro_clipcascade_network")
                }
            }
        }
    }
    finalize {
        for (file in listOf("res/xml/settings.xml", "res/xml/settings_legacy.xml")) document(file).use(::removeOldSettings)
        document("res/xml/setting_clipboard.xml").use { xml ->
            val section = addSection(xml, "ClipCascade", "urro_clipcascade_section")
            section.preference("Connect to ClipCascade", "urro_clipcascade_enabled", toggle = true)
            section.preference("Allow local network access", "urro_clipcascade_local_network", "Required for nearby P2P devices on Android 17 and newer.")
            section.preference("Show Other devices section", "urro_show_remote_clipboard", toggle = true)
            section.preference("Confirm before sending", "urro_confirm_clipboard_send", toggle = true)
            section.preference("This device section name", "urro_local_section_name")
            section.preference("Other devices section name", "urro_remote_section_name")
            section.preference("Clear received history", "urro_clear_remote_history", "Only removes clips received on this device.")
            section.preference("Connection", "urro_clipcascade_status")
            (section.lastChild as Element).android("selectable", "false")
        }
        document("AndroidManifest.xml").use { xml ->
            val app = xml.getElementsByTagName("application").item(0) as Element
            val activities = app.getElementsByTagName("activity")
            for (i in activities.length - 1 downTo 0) {
                val activity = activities.item(i) as Element
                if (activity.getAttributeNS(ANDROID, "name") in setOf("xyz.urro.gboard.ClipCascadeSettings", "xyz.urro.gboard.TranslationSettings")) app.removeChild(activity)
            }
            val permissionName = "android.permission.ACCESS_LOCAL_NETWORK"
            val permissions = xml.getElementsByTagName("uses-permission")
            if ((0 until permissions.length).none { (permissions.item(it) as Element).getAttributeNS(ANDROID, "name") == permissionName }) {
                val permission = xml.createElement("uses-permission")
                permission.android("name", permissionName)
                xml.documentElement.insertBefore(permission, app)
            }
            val permissionActivity = xml.createElement("activity")
            permissionActivity.android("name", "xyz.urro.gboard.ClipPermissionActivity")
            permissionActivity.android("exported", "false")
            app.appendChild(permissionActivity)
            val provider = xml.createElement("provider")
            provider.android("name", "xyz.urro.gboard.ClipImages"); provider.android("authorities", "xyz.urro.gboard.clipimages." + java.util.UUID.randomUUID().toString().replace("-", ""))
            provider.android("exported", "false"); provider.android("grantUriPermissions", "true"); app.appendChild(provider)
        }
    }
}
