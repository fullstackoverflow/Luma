# Luma

一个围绕桌面卡片构建的精简 Android Komari 客户端。App 用 WebView 打开你服务端已部署的 LuminaPlus（或其他 Komari 主题），原生负责卡片配置、会话同步与后台数据请求。

## 使用

1. 在 Komari 服务端安装主题，确认手机浏览器可以正常访问。
2. 在 Luma 点「新增配置」，填写实例名称和 **HTTPS 服务根地址**，例如 `https://monitor.example.com`。保存后会在首页新增一条实例记录。不要填写 `/admin` 或某个节点详情地址。
3. 点击实例记录打开对应看板，在 App 内完成 Komari 登录。不要跳到外部浏览器登录，它与 WebView 不共享 Cookie。
4. 长按桌面 → 小部件 → Luma，添加桌面卡片；卡片配置由系统小部件入口打开。
5. 给每张卡片选择服务器实例、节点和刷新周期，点击线路按钮勾选最多 3 条 Ping 任务。可先开启明确标注的示例模式查看样式。

## 卡片

- 默认 4×3；小尺寸为紧凑版，大尺寸为节点总览，支持横纵缩放及系统深浅色模式。
- 大卡片显示 CPU / 内存 / 磁盘、1 分钟负载、上下行速率、累计上下行流量、运行时长和更新时间。
- 剩余流量使用节点的 `traffic_limit` 和 `traffic_limit_type`，按上行、下行、求和、取大或取小计费；显示已用 / 上限及使用进度。缺失额度或计数显示 —，额度为 0 显示不限量。
- 默认展示节点的前三条 Ping 任务，可在弹窗勾选最多 3 条。小卡片显示已选线路中的第一条。
- 线路显示最近延迟和丢包率。色条是当前数值的可视化，不是历史趋势：资源按百分比、延迟按 300ms 刻度、丢包条以红绿段表示失败与成功比例。
- 每张卡片独立选择节点、线路和刷新周期。丢包率统一查询已有的最近 1 小时样本，不重采样，不修改服务端任务，也不读取网页主题的时间窗口设置。
- 卡片配置页返回前台时自动加载节点与任务；网络错误时点击提示重试，会话过期时点击提示重新登录。
- 最新探测超时显示 `—`；无样本也显示 `—`，不把无数据当成 0% 丢包。
- 优先调用 `public:getPingMetricStats`，旧版本在方法不存在时回退 `common:getRecords`。
- 旧接口聚合记录的丢包率按原始样本数加权；服务端窗口统计可用时优先采用。
- 后台使用 WorkManager，周期最短 15 分钟，可能被安卓省电策略延后。手动刷新不改变周期。
- 网络失败保留上次成功结果并标注缓存；点击卡片进入节点详情，修改卡片可通过桌面的小部件重新配置入口。

## 鉴权与边界

- 网页完成登录，真正的身份验证和权限判断由 Komari 服务端执行。
- 原生只同步 `session_token` Cookie，不获取或保存账号密码，不向 JavaScript 暴露原生桥。
- 原生会话使用 Android Keystore AES/GCM 加密；禁用应用备份。
- 原生请求绑定一个确切 HTTPS origin，禁止重定向，不跳过证书验证。
- 会话过期需重新打开对应实例的网页登录。
- 支持保存多个实例；原生会话按实例存储，卡片绑定到各自的服务器。打开其他实例不会清除已有卡片或登录。相同网站的 WebView Cookie 遵循浏览器的域名规则。
- WebView 直接加载服务端页面，不打包第三方主题，因此网页初次加载需要网络。
- 第三方域名链接交给浏览器；跨域 OAuth、文件上传/下载、网页通知暂未适配。
- 原生卡片不依赖网页存活，不抓取 DOM，也不会采用主题生成的模拟 Ping。

## 构建

Android 9+；compile/target SDK 36，AGP 9.1，Gradle 9.3.1。

```powershell
# 配置 Android Studio 自带 JBR 或受支持的 JDK，以及本机 Android SDK
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\build.ps1
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。
Release 签名从环境变量 `KEYSTORE_FILE`（密钥文件路径）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD` 读取。未设置 `KEYSTORE_FILE` 时，本地 Gradle 可构建未签名 Release，也可以使用 Android Studio 的签名向导。项目不包含发布密钥。

### GitHub Actions 打包

仓库包含 `.github/workflows/build-apk.yml`，仅推送 `v*` 标签时触发 **Release APK** 工作流，普通分支提交和 PR 不触发。标签格式为 `v0.1.2` 或 `v0.1.2-beta.1`；带后缀的版本自动标记为预发布。

先在仓库 **Settings → Secrets and variables → Actions → Repository secrets** 设置四个 Secrets：

| 名称 | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | 发布密钥文件的 Base64 编码 |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | 密钥别名，例如 `luma` |
| `KEY_PASSWORD` | 密钥密码 |

工作流使用 JDK 21、Android SDK 36 和项目自带的 Gradle wrapper，构建已签名且经过混淆的 Release APK，运行现有单元测试及 Release Lint，并验证 APK 签名。缺少 Secrets 或检查失败时不会发布。

发布前递增 `app/build.gradle.kts` 中的 `versionCode`，并更新本地默认 `versionName`。CI 的 APK `versionName` 自动使用标签去掉 `v` 后的值。提交代码后推送标签：

```bash
git tag -a v0.1.2 -m "Release v0.1.2"
git push origin v0.1.2
```

成功后可在仓库 **Releases** 下载 `Luma-v0.1.2.apk` 和 `SHA256SUMS.txt`。Actions 的 APK 备份保存 30 天，检查报告及混淆映射保存 14 天。重跑同一标签的工作流会更新对应 Release 的同名附件。

每次发布继续使用同一套密钥，以便覆盖升级。密钥文件及密码应自行备份，不要提交到仓库。现有 Debug 安装包与 Release 签名不同，首次换装需要卸载 Debug 版本。

## 当前验证范围

单元测试覆盖 origin 隔离、无样本、超时、节点/任务过滤和丢包加权。Robolectric 原生渲染测试检查实际 RemoteViews 的文字边界、最小尺寸和放大字体，并生成卡片截图到 `app/build/outputs/previews/`。真实 Komari 会话与各厂商桌面的小部件效果仍需设备联调。

## 参考

- [LuminaPlus](https://github.com/shanyang242/Komari-Theme-LuminaPlus)：参考其公开 RPC 接口调用方式，App 实际加载用户部署的主题。
- [Komari](https://github.com/komari-monitor/komari)

Luma 未复制上述项目的应用代码或主题资源。Gradle wrapper 属于 Gradle，遵循 Apache-2.0。
