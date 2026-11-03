# 长文本输入诊断

`NativeLongInputComparisonUiTest.kt` 是独立的 Android 诊断样本，不属于默认设备门禁。它在空的自建模拟器上，以同一份测试 JSON 对比普通 Compose 输入框、普通弹窗、带滚动容器的弹窗和项目输入弹窗；最后一项使用 `Instrumentation.sendStringSync` 逐键同步发送。

需要复查时，可将该文件临时放入 `app/src/androidTest/java/io/legado/app/ui/config/`，编译 Android 测试包，在专用模拟器上按类或方法运行，然后移回本目录。结果写在测试应用缓存的 `native-long-input-*.json`；失败时另保存截图。测试 JSON 仅为合成书源，不连接外部服务。

设备对照还提供原始时间戳的逐事件注入与刷新时间戳注入：在所测API35模拟器上，一批232个事件共用一个时间戳，超过约10秒后的107个被系统判为过期；逐个刷新时间戳后1400个事件全部被接受，571字符完整。`Instrumentation.sendStringSync` 同样会刷新事件时间戳。原生长按粘贴也在当前正式包的所测场景通过。

这一结论针对模拟事件注入，不能代表所有实际输入法或设备。诊断保留原始失败；默认门禁使用 `LongNativeInputUiTest` 和同步逐键发送，独立核对完整字符及确认值。具体 APK 身份、系统日志与范围见实施记录。

可对照 Android 开源实现：[批量字符到按键](https://android.googlesource.com/platform/frameworks/native/+/716acde0fa2cd40a587ac88657c1ab48697e4a48/libs/input/KeyCharacterMap.cpp)、[输入命令](https://android.googlesource.com/platform/frameworks/base/+/80c54abf5a10/services/core/java/com/android/server/input/InputShellCommand.java)、[过期事件判断](https://android.googlesource.com/platform/frameworks/native/+/1dc3b4635c6ff343444bf0f5466c7db22c980825/services/inputflinger/dispatcher/include/InputDispatcherPolicyInterface.h)及[同步字符发送](https://android.googlesource.com/platform/frameworks/base/+/53284d5816f065b2de20dcb019fa1096b148eee4/core/java/android/app/Instrumentation.java)。这些代码解释机制，设备测试记录实际结果。
