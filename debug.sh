adb="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"

# 检查设备
"$adb" devices

# 编译
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./build.ps1 -Tasks assembleDebug

# 安装（编译成功后执行）
"$adb" install -r ./app/build/outputs/apk/debug/app-debug.apk

# 启动
MSYS_NO_PATHCONV=1 "$adb" shell am start -n dev.luma.monitor/.MainActivity