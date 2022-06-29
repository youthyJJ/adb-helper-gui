import PageVm.functionVm
import PageVm.show
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.nio.charset.Charset

enum class FuncCategory(
    val desc: String
) {
    WindowManager(
        "窗口管理器"
    );

    companion object {
        val enum
            get() = listOf(
                WindowManager
            )
    }
}

data class CommandHandler(
    val command: String,
    val desc: String,
    val pretty: (String) -> String = { it },
)

class FunctionVm(
    val scope: CoroutineScope,
    private val env: Env,
    private val device: DeviceInfo
) {
    private val innerSelectState = MutableStateFlow(FuncCategory.WindowManager)
    val selectState = innerSelectState.asStateFlow()

    private val innerCommandHandleResultMap = MutableStateFlow(mapOf<CommandHandler, String>())
    val commandHandleResult = innerCommandHandleResultMap.asStateFlow()

    fun handle(handler: CommandHandler) {
        scope.launch {
            runInterruptible {
                Runtime.getRuntime().exec(
                    "${env.adb.absolutePath} -s ${device.serial} ${handler.command}"
                ).apply {
                    inputReader(Charset.forName("UTF-8")).use { reader ->
                        val map = innerCommandHandleResultMap.value.toMutableMap()
                        val lines = reader.readLines()
                        map[handler] = handler.pretty(lines.joinToString(System.lineSeparator()))
                        innerCommandHandleResultMap.value = map
                    }
                }
            }
        }
    }

    val windowManagerCommandList = listOf(
        CommandHandler("shell wm size", "屏幕尺寸") {
            val lines = it.lines()
            fun CharSequence.dimension() = Regex(".* (\\d+x\\d+)").find(this, 0)!!.groupValues[1]
            runCatching {
                if (lines.size == 1) {
                    lines.first().dimension()
                } else {
                    val physical = lines[0].dimension()
                    val override = lines[1].dimension()
                    "$override[$physical]"
                }
            }.getOrNull() ?: it
        },
    )

}

@Composable
fun FunctionPanel(device: DeviceInfo) {
    val vm = device.functionVm
    Column {
        Row(modifier = Modifier.padding(vertical = 10.dp, horizontal = 5.dp).weight(1F)) {
            LazyColumn(modifier = Modifier.weight(1F)) {
                items(FuncCategory.enum) { category ->
                    Row(
                        modifier = Modifier.height(30.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        val isSelected = vm.selectState.value == category
                        Spacer(
                            modifier = Modifier.fillMaxHeight()
                                .width(5.dp)
                                .background(if (isSelected) Color.Blue else Color.Transparent)
                        )

                        Spacer(modifier = Modifier.width(15.dp))

                        Text(category.desc)
                    }
                }
            }

            Spacer(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color.Gray))

            Box(modifier = Modifier.weight(3F)) {
                FunctionContent(device)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(50.dp)) {
            NaviBar()
        }
    }

}

@Composable
fun NaviBar() {
    Column(modifier = Modifier.padding(horizontal = 5.dp)) {
        Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
        Spacer(modifier = Modifier.height(5.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            Button(onClick = {
                Page.DeviceList.show()
            }) {
                Text("返回")
            }
        }
    }

}

@Composable
fun FunctionContent(device: DeviceInfo) {
    val vm = device.functionVm
    val state = vm.selectState.collectAsState()
    when (state.value) {
        FuncCategory.WindowManager -> {
            WindowManagerPanel(device)
        }
    }
}

@Composable
fun WindowManagerPanel(device: DeviceInfo) {
    val vm = device.functionVm

    LazyColumn(modifier = Modifier.padding(horizontal = 5.dp)) {
        items(vm.windowManagerCommandList) { handler ->
            Row {
                Button(
                    modifier = Modifier.weight(2F),
                    onClick = { vm.handle(handler) }) {
                    Text(handler.desc)
                }

                Spacer(modifier = Modifier.width(5.dp))

                val result = vm.commandHandleResult.collectAsState()
                Text(
                    result.value[handler] ?: "",
                    modifier = Modifier.weight(4F)
                        .fillMaxHeight()
                        .align(Alignment.CenterVertically)
                )
            }
        }
    }


}