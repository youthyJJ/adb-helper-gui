import PageVm.show
import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

sealed class Page {
    object Initial : Page()
    object DeviceList : Page()
    data class Function(val device: DeviceInfo) : Page()
}

data class DeviceInfo(
    val serial: String,
    val state: String,
)

object PageVm {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val env: Env = Env.pick()

    private val innerPageState = MutableStateFlow<Page>(Page.Initial)
    val pageState = innerPageState.asStateFlow()
    fun Page.show() {
        innerPageState.value = this
    }

    private val innerInitNote = MutableStateFlow("初始化中…")
    val initNote = innerInitNote.asStateFlow()

    init {
        Page.Initial.show()
        unzipPlatformTools { note -> innerInitNote.value = note }
        Page.DeviceList.show()
        refreshDeviceList()

        scope.launch {
            pageState.collect {
                if (it is Page.DeviceList) {
                    functionVmMap.values.forEach { vm ->
                        vm.scope.cancel()
                    }
                    functionVmMap.clear()
                }
            }
        }
    }

    private val functionVmMap = mutableMapOf<DeviceInfo, FunctionVm>()
    val DeviceInfo.functionVm: FunctionVm
        get() = (functionVmMap[this] ?: FunctionVm(
            CoroutineScope(Dispatchers.Default),
            env,
            this
        )).apply { functionVmMap[this@functionVm] = this }


    private fun unzipPlatformTools(note: (String) -> Unit) {
        scope.launch {
            if (env.platformToolsDir.exists()
                && env.platformToolsDir.listFiles()?.isNotEmpty() == true
            ) return@launch

            useResource(env.platformZip) { ris ->
                val buffer = ByteArray(8 * 1024)
                ZipInputStream(ris).use tag@{ zis ->
                    loop@ while (true) {
                        val entry = zis.nextEntry ?: return@tag
                        note("正在解压: ${entry.name}")
                        val dest = File(env.dataDir, entry.name)
                        if (entry.isDirectory) {
                            dest.mkdirs()
                        } else {
                            dest.parentFile.mkdirs()
                            dest.outputStream().use { fos ->
                                unzip@ while (true) {
                                    val readSize = zis.read(buffer)
                                    if (readSize == -1) break@unzip
                                    fos.write(buffer, 0, readSize)
                                }
                            }
                            dest.setExecutable(true, false)
                            dest.setReadOnly()
                        }
                    }
                }
            }
        }
    }

    private val innerDeviceListFlow = MutableStateFlow(listOf<DeviceInfo>())
    val deviceListFlow = innerDeviceListFlow.asStateFlow()

    fun refreshDeviceList() {
        scope.launch(Dispatchers.IO) {
            Runtime.getRuntime().exec(
                "${env.adb.absolutePath} devices"
            ).apply {
                val list = inputReader(Charset.forName("UTF-8")).use { reader ->
                    reader.readLines()
                        .asSequence()
                        .filter { it.isNotBlank() }
                        .map { it.trim() }
                        .filter {
                            it.endsWith("device") || it.endsWith("offline")
                        }.map {
                            val pair = it.trim().split("\t")
                            runCatching {
                                DeviceInfo(pair[0], pair[1])
                            }.getOrNull()
                        }.filterNotNull()
                        .toList()
                }
                innerDeviceListFlow.value = list
            }
        }
    }
}

@Composable
@Preview
fun App() {
    val pageState = PageVm.pageState.collectAsState()

    MaterialTheme {
        when (val state = pageState.value) {
            Page.Initial -> Initial()
            Page.DeviceList -> DeviceSelect()
            is Page.Function -> FunctionPanel(state.device)
        }
    }
}

@Composable
fun Initial() {
    val note by PageVm.initNote.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = note)
    }
}

@Composable
fun DeviceSelect() {
    val deviceList by PageVm.deviceListFlow.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
    ) {
        Button(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            onClick = {
                PageVm.refreshDeviceList()
            }
        ) {
            Text("刷新设备列表")
        }

        LazyColumn {
            items(deviceList) {
                DeviceItem(it)
            }
        }
    }
}

@Composable
fun DeviceItem(device: DeviceInfo) {
    Row(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(
            text = device.serial,
            modifier = Modifier.weight(2F)
                .align(Alignment.CenterVertically),
            fontSize = 18.sp,
            color = if (device.state == "device") Color.Black else Color.Gray
        )

        Button(
            modifier = Modifier.wrapContentWidth(),
            onClick = { Page.Function(device).show() },
            enabled = device.state == "device"
        ) {
            Text(text = "选择")
        }
    }
}

fun main() = application {
    val windowState = rememberWindowState(
        position = WindowPosition.Aligned(Alignment.Center),
        width = 300.dp,
        height = 400.dp,
    )

    Window(
        state = windowState,
        title = "ADB小助手",
        resizable = false,
        onCloseRequest = ::exitApplication
    ) {

        val scope = rememberCoroutineScope()
        scope.launch {
            PageVm.pageState.collect { page ->
                when (page) {
                    is Page.Function -> {
                        window.title = page.device.serial
                        windowState.size = windowState.size.copy(width = 450.dp)
                    }
                    else -> {
                        window.title = "ADB小助手"
                        windowState.size = windowState.size.copy(width = 300.dp)
                    }
                }
            }
        }
        App()
    }
}
