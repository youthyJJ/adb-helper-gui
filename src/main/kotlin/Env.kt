import java.io.File

interface Env {
    val platformZip: String
    val userHomeDir: File
    val dataDir: File
    val platformToolsDir: File
    val adb: File

    companion object {
        fun pick(): Env {
            val osName = System.getProperty("os.name")
            return when {
                osName.startsWith("Mac") -> MacEnv
                else -> throw IllegalStateException("platform($osName) not support yet")
            }
        }
    }
}

private object MacEnv : Env {
    override val platformZip get() = "platform-tools_r33.0.2-darwin.zip"
    override val userHomeDir get() = File(System.getProperty("user.home"))
    override val dataDir get() = File(userHomeDir, ".adb_helper")
    override val platformToolsDir get() = File(dataDir, "platform-tools")
    override val adb get() = File(platformToolsDir, "adb")
}
