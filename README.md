# 模块雷达 ModRadar

> 面向普通用户与 Android 极客的 **APK / Xposed 模块 / KernelSU 模块「扫描 + AI 逆向」工具**。
> 本地静态分析给出确定性结论与证据链，混淆或加固的目标交给 AI 结合工具循环深挖。

**只读分析工具**——不做 Hook 注入、不修改任何系统文件。

## 核心能力

### 分析流水线（S1–S6，单一流水线，不分快慢）

| 阶段 | 内容 |
|---|---|
| S1 装载 | 类型识别（APK / Xposed 模块 / KSU 模块）、整包 SHA256、包结构 |
| S2 结构提取 | AXML 清单解析、权限、组件暴露、签名（v1/v2/v3 + 证书指纹）、ABI、DEX、加固特征、`module.prop` |
| S3 能力链分析 | **SOURCE → TRANSFORM → SINK** 指令级扫描 + 调用图 ≤4 层可达性，回答「远端取文件 → 本地执行」 |
| S4 规则评分 | 0–100 分 + 分级 findings + 证据链，规则见 [`docs/计划.md`](docs/计划.md) §5 |
| S5 AI 研判 | 无界面工具循环自动研判；未配置 AI 时标注「未完成 AI 研判」并正常完成 |
| S6 报告 | 汇总为可读报告：评分环、分级统计、能力链、AI 结论、边界说明 |

### 能力链规则（S3）

| 规则 | 判定 | 扣分 |
|---|---|---|
| C1 | SOURCE 与 SINK 出现在同一方法 | −35 |
| C2 | 跨方法调用链可达（≤4 层） | −25 |
| C3 | 仅存在 SINK（有能力无证据） | −10 |
| C4 | 网络能力与执行/动态加载并存 | −15 |
| C5 | 字符串解密后立即加载/执行 | −30 |
| C6 | 壳 / VMP / 加固 → 不可审计 | −25 |
| C7 | KSU 脚本危险模式 | −30 |

每条命中都带证据（`类#方法` + API 明细 + 定位），报告里可追溯。

### 后台执行与断点续跑

- **前台服务**（`dataSync`）+ 常驻通知显示进度与暂停/继续/取消
- 覆写 `Service.onTimeout` 处理 Android 15+ dataSync 6 小时上限，超时前落盘
- **保活引导**：通知权限、省电优化豁免、厂商后台白名单（12 个厂商页面探测）、最近任务锁定
- **断点续跑**：原子提交（`.tmp → fsync → rename`），已完成阶段跳过不重算；进程被杀后可从断点恢复
- **暂停期仍可对话**：工作台与流水线解耦，工具按阶段门控返回 `data_not_ready`，
  prompt 强制「未扫描 ≠ 未发现」

### 已安装模块

- **Xposed 模块**：读 meta-data 识别（免 root）；激活状态无公开 API，显示「未知」
- **KSU / Magisk 模块**：zip 免 root 解析；root 下**只读**列出（`/data/adb/modules`、`/data/adb/ksu/modules`）
- 脚本危险模式检测（管道下载执行、`resetprop`、`bind` 挂载、`setenforce 0`、`dd` 写块设备…）

### AI 能力

- OpenAI 兼容接口，可配置地址 / Key / 模型 / 温度 / 思考等级，支持测试连接
- **function calling 工具循环**：工具注册表一次定义，内置工具与 MCP 共用
- 工具按阶段门控：缺阶段返回结构化 `data_not_ready`，不返回空结果
- **AI 结论不计入总分**；仅在引用本地已核验证据时可升级等级

## 界面预览

12 屏原型图（含运行时状态）见 [`docs/ui/README.md`](docs/ui/README.md)。

## 技术栈

Kotlin + Jetpack Compose + Material 3（纯黑深色主题）｜Room ｜ OkHttp ｜ kotlinx-serialization
逆向层：**dexlib2**（指令级分析）· **DexKit**（抗混淆搜索）· **jadx-core**（反编译）

| 项 | 值 |
|---|---|
| AGP / Gradle / Kotlin / KSP | 8.13.0 / 9.4.1 / 2.4.20 / 2.3.12 |
| compileSdk / minSdk / targetSdk | 36 / 24 / 36 |
| Compose BOM | 2026.02.01 |

## 构建

```bash
# 调试包
gradle assembleDebug

# 单元测试（70 个用例）
gradle :app:testDebugUnitTest

# 发布包（需 keystore，见 build.sh 顶部变量）
./build.sh
```

`build.sh` 会执行 `assembleRelease → zipalign → apksigner`，产出 `dist/ModRadar-<version>.apk`。
keystore 路径与口令通过环境变量 `KEYSTORE` / `KS_PASS` / `KEY_ALIAS` 覆盖，**密钥不入库**。

## 文档

| 文档 | 内容 |
|---|---|
| [`docs/计划.md`](docs/计划.md) | 产品与实施计划、评分权重表、能力链规则、Prompt 体系 |
| [`docs/后台执行与断点续跑.md`](docs/后台执行与断点续跑.md) | 后台服务、保活引导、检查点协议的实现说明 |
| [`docs/进度与续跑.md`](docs/进度与续跑.md) | 当前进度、环境版本、已知边界（接手/恢复入口） |
| [`PRIVACY.md`](PRIVACY.md) / [`TERMS.md`](TERMS.md) | 隐私政策 / 用户协议（同时随包发布，App 内可查） |

## 数据边界

- **本地优先、零遥测**：无账号、无统计 SDK、不上报任何数据
- 仅在**你主动配置 AI 并主动发起分析**时联网，且只向**你填写的接口**发送特征摘要与反编译片段
- **不上传 APK / 模块本体**；API Key 仅存本机
- root 路径**只读**：只执行 `ls` / `cat` / `test`，且路径经白名单校验（拒绝 `..` 穿越）
- 不配置 AI 时，本地分析功能完整可用

## 已知边界

- 壳 / VMP 保护的目标静态拆不开：会标记「不可审计」并计入风险，动态脱壳未实现
- 权限↔API 一致性检查未实现（报告 notes 中如实标注）
- Xposed 模块激活状态无法读取（无公开 API）
- 厂商后台白名单与最近任务锁定无法检测，只能引导
- 仅 `assembleRelease` 未开启 R8 压缩（依赖 keep 规则未验证）

## 许可

[GPL-3.0](LICENSE)。仅供学习交流，禁止商用与非法用途。
仅用于分析**你有权处理**的应用与模块。
