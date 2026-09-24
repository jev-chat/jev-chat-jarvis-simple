# jev-chat-jarvis-simple（Android · 输入法版）

在**当前聊天 App 的键盘上**直接看到这句话的**意图、风险**和**候选回复**，点一下就进输入框。

不跳 App、不切后台、不申请无障碍、不申请录屏、不改任何聊天软件——**一个输入法打通所有聊天软件**（微信 / QQ / 钉钉 / 短信 / WhatsApp……）。这是它和本仓库姊妹项目「悬浮窗 + 无障碍树」路线的根本区别。

## 与其他版本的路线差异

| | macOS / Windows | Android（[完整版](https://github.com/jev-chat/jev-chat-jarvis)） | **本仓库（输入法版）** |
|---|---|---|---|
| 感知消息 | 抓窗口 + OCR | 无障碍树 / OCR 兜底 | **用户长按消息 → 复制**（剪贴板） |
| 展示候选 | 悬浮窗 | 悬浮窗 | **键盘面板**（就在输入框上方） |
| 填入回复 | 辅助功能 / 粘贴 | `ACTION_SET_TEXT` | **`commitText` 直插输入框** |
| App 适配 | 仅微信，布局常量易失效 | 每 App 一个适配器 | **零适配，任何 App 通用** |
| 权限 | 录屏 + 辅助功能 | 无障碍 + 悬浮窗 | **一个输入法开关** |

取舍是诚实版：用「长按 → 复制」一步手动，换来**快、稳、全站**——不依赖任何 App 的界面结构，微信改版也不影响。

## 用法

1. **装包**：`./tools/package.sh` 产出 `apk/jev-simple-v0.1.0-*.apk`，`adb install -r` 装到手机（Android 8.0+）。
2. **启用输入法**：打开 App →「开始」页 →「打开输入法设置」→ 打开「Jev 键盘」→ 回来点「立刻切换到 Jev 键盘」。
3. **配模型**：「模型」页选个预设（智谱 `glm-4-flash` 免费 / DeepSeek 最快 / OpenRouter 一个 key 全模型），填 Key，点「测试连接」。不填 Key 也能跑——自动走内置中转。
4. **到「试一试」页跑一条消息**，通了就是全通了。

日常使用（以微信为例）：

1. 长按对方那条消息 → **复制**
2. 输入框获得焦点，键盘切到 Jev → 点 **「分析剪贴板」**
3. 键盘上显示：意图 + 风险 0-9 + 行动建议 + 每话术 2 条候选（前稳后放）
4. 点中意的候选 → 文字直接进输入框 → **发送由你手动完成**（本项目永不自动发送）

另有「AI 分析输入框文字」按钮：分析你打到一半拿不准的话（读 `getTextBeforeCursor`）。

## 配置

全部在 App 内完成，改完即存、键盘即时生效（无需重启）：

- **生成层（必配）**：OpenAI 兼容（`/chat/completions`）或 Anthropic 兼容（`/v1/messages`）任一端点。地址带不带 `/v1`、填到动作段都能拼对。支持额外字段 JSON（默认带 `enable_thinking:false`，Qwen3 类模型必须关思考）。**别用思考型模型**，思考占满额度会 0 条候选（错误信息里会直接点名）。
- **判断层（核心）**：TypeSafe Jev（systemone 接口），一次调用同时出 8 类意图概率分布 + 0-9 风险分布，并给候选排序；内置 TypeSafe 直连 / OpenRouter / Vercel AI Gateway 三个预设。没配 key 时运行时自动退化为「盲起草」（只出候选），这不是配置开关而是兜底。
- **话术**：2 个槽位（手机屏幕就那么大），内置 12 种话术，支持自定义（说明写「什么语气 + 别变成什么」最管用）。每话术一次请求、出 2 条，多话术并发。

### 隐私边界

- 聊天内容只在点「分析」那一刻发往**你自己配置**的模型接口；无自建服务器、不落盘、不进日志
- API Key 存在本 App 私有 SharedPreferences，只有主 App 和你启用的这个输入法能读
- 输入法不监听、不上传按键；**没有申请**无障碍 / 录屏 / 存储权限（`INTERNET` + `ACCESS_NETWORK_STATE` 两条，就这些）
- 候选只写进输入框，发送永远由你手动完成

## 三端一致性

`core/` 是从 iOS 版 `Shared/` 逐字移植的单一来源，源头是 macOS 版：

| 口径 | 来源 | Android 对应 |
|---|---|---|
| 8 类意图 | `src/judge.py INTENTS` | `core/JevPrompts.kt INTENTS` |
| 风险量表 0-9 | `src/judge.py RISK_LEVELS` | `core/JevPrompts.kt RISK_LEVELS` |
| 行动建议 | `src/judge.py ACTION_MAP` | `core/JevPrompts.kt ACTION_MAP` |
| 话术库 | `src/styles.py BUILTIN` | `core/JevPrompts.kt BUILTIN_TONES` |
| 起草 prompt | `src/generate.py PROMPT_ONE` | `core/JevPrompts.kt PROMPT_ONE` |
| 候选清洗 | `src/generate.py _parse` | `CandidateParser`（同顺序：编号→引号→风格前缀→引号） |
| Jev URL 拼接 | `src/generate.py #42 单一规则` | `JevJudge.requestURL` |
| 阶段预算 | 社区 iOS 版真机教训 | `core/JevHttp.kt postJSON`（重试与超时不再相乘） |

## 工程结构

```
├── app/src/main/java/com/jev/simple/
│   ├── core/              # 与其它端共用的单一口径层（无 UI）
│   │   ├── JevPrompts.kt  # 意图 / 风险 / 话术 / prompt / 清洗
│   │   ├── JevModel.kt    # 配置模型 + SharedPreferences 存储
│   │   ├── JevHttp.kt     # 带总预算的 POST（429/5xx 退避重试）
│   │   ├── JevJudge.kt    # TypeSafe systemone：判断 + 排序
│   │   ├── JevDraft.kt    # OpenAI / Anthropic 起草
│   │   └── JevPipeline.kt # 判断 → 每话术并发起草 → 排序
│   ├── ime/JevImeService.kt   # 输入法面板（待机 / 话术 / 加载 / 结果 / 错误）
│   └── ui/                # 主 App：开始 / 模型 / 话术 / 试一试
├── tools/package.sh       # 打包脚本（与姊妹仓库同一套约定）
└── apk/                   # 打好的包
```

## 构建与打包

JDK 17 + Android SDK（platform 35 / build-tools 35）。

```bash
./tools/package.sh              # 有签名配置打 release，没有就打 debug
./tools/package.sh release      # 强制 release
./gradlew assembleDebug         # 直接调 gradle 也行
```

Release 签名配置放在**仓库外**，路径由 `JEV_KEYSTORE_PROPS` 指定（默认 `./keystore.properties`，已被 `.gitignore` 挡住）：

```properties
storeFile=/absolute/path/to/jev-release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

## 已知限制

- **复制的消息不含上下文**：剪贴板里只有那一条。微信「引用」后再复制会带上被引文字，可当简易上下文用。
- **发送键看 App 脸色**：输入法能触发宿主输入框的 editor action（微信这类「回车即发送」的真能发出去），但仍有不吃这套的 App；发完会回读输入框，按实际结果如实告诉你，不假装成功。
- **密码框等安全输入会强制系统键盘**（系统行为，不是 bug）。
- 横屏未适配；面板高度上限是半屏。

## 路线图

1. 多轮上下文（剪贴板里只有一条消息）
2. 键盘内完整 QWERTY（免切换打字）
3. 知识库 / 联系人档案（完整版已有）

## 许可

MIT。候选只插入输入框，**永不自动发送**；只读你自己账号里你自己看到的内容。
