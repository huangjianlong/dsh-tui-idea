# dsh-tui-idea

在 IntelliJ IDEA 右侧 **DeepSeek 工具窗**内嵌运行 **dsh-TUI**（DeepSeek Harness），
体验与 Claude Code 官方插件几乎一致：会话历史与运行中的终端同面板共存，
不占用底部集成终端；点开插件才加载，IDEA 启动时不会自动弹出任何会话。
本插件是 [dsh-tui-vscode](https://github.com/baobaolaodie/dsh-tui-vscode) 的
IntelliJ 版，行为语义与其严格对齐。

## 功能

- **一键会话**：工具窗/菜单启动新会话、恢复上次会话、多并发会话
  （每次点击在 DeepSeek 工具窗新增一个可关闭终端标签，关闭即结束该会话进程）。
- **会话历史**：第一个标签「会话」读取
  `~/.dsh/sessions/<group>/<id>/session.jsonl.zstd`
  （zstd 帧链，只做头部 64KB + 尾部 128KB 有界读取），按项目分组、最近使用排序
  （`~/.dsh-tui/last-used.json`），双击即开新标签恢复该会话。
- **会话管理**：归档/恢复（dsh 工作区域归档集 `storages/workspace.json`，与
  dsh web 会话列表同一来源）、重命名（向日志追加一帧 `session/title` 事件，
  读取侧最后标题生效）、永久删除（带 sessions 根路径包含校验）、复制会话 ID。
- **@引用**：编辑器右键「插入 @文件引用」，把当前文件/选中代码以
  `@绝对路径 L起-止` 形式键入运行中的 dsh-tui 输入框（不自动提交）；
  没有运行中的会话时回退复制到剪贴板。实验性「选区变化自动插入」（默认关）。
- **环境注入**：`DSH_TUI_LANG`（语言）、`DSH_HOME`（覆盖）、`VISUAL`
  （让 TUI 的外置编辑器拉起 IDE）、恢复指定会话用 `DSH_TUI_RESUME_SESSION`/
  `DSH_CC_RESUME_SESSION`。

## 前置条件与一键配置

插件内置**环境体检 + 一键配置**（Tools → DeepSeek → 一键配置环境，或工具窗未就绪时点「一键配置环境」按钮），自动检测并安装：

| 检测项 | 一键可修 | 说明 |
|---|---|---|
| Node.js | ✗（引导） | 需自行安装 LTS：https://nodejs.org/zh-cn |
| pnpm | ✓ | `npm install -g pnpm`（dsh 插件机制依赖） |
| dsh CLI | ✓ | `npm install -g @deepseek-ai/dsh` |
| dsh-tui profile | ✓ | `dsh plugin --profile dsh-tui add @deepseek-harness-tui/dsh-tui` |
| 全局皮肤/插入包缺失 | ✓ | 解析 `~/.dsh/cordis.patch.yml` 的 insert 条目，缺的自动补进 profile（这是「TUI 启动即退」的常见根因） |
| DeepSeek 凭据 | ✗（引导） | 运行一次 `dsh` 登录或设置 `DEEPSEEK_API_KEY` |

**启动不挑环境**：终端启动命令解析链为「设置命令（路径形式）→ PATH 上的 dsh-tui 启动器 → `dsh --profile dsh-tui`（等价回退）」，新机器即使没有手工放置 `dsh-tui.cmd` 启动器也能运行；「恢复上次会话」优先取 `~/.dsh-tui/last-used.json` 中最近的会话，通过 `DSH_TUI_RESUME_SESSION` 注入（与官方启动器语义一致），并注入 `NODE_ENV=production` 防止长会话 OOM。

因此**分发包在任何装有 IDEA 2026.1+ 的机器上即装即用**：打开 DeepSeek 工具窗 → 按提示一键配置 → 开始使用（仅 Node.js 与登录凭据需要手动）。

## 构建

```bash
gradlew buildPlugin        # 产出 build/distributions/dsh-tui-idea-<ver>.zip
gradlew test               # 单元测试（zstd 帧链解析、标题回退、过滤排序、启动规划、环境体检等）
gradlew runIde             # 沙箱 IDE 中试运行
```

构建依赖：JDK 21。默认用本机 IDEA（`gradle.properties` 的 `localIdeaHome`，换机器改路径或删掉该行走官方源下载）；`org.gradle.java.home` 同样按机器调整。目标平台 2026.1+（sinceBuild 261）。

## 安装

IDEA → Settings → Plugins → ⚙ → Install Plugin from Disk → 选择
`build/distributions/dsh-tui-idea-<ver>.zip` → 重启。
（0.2.x 升级到 0.3.x：若工具窗仍停在左侧，把 stripe 图标拖到右侧一次即可。）

## 使用

- 右侧工具窗 **DeepSeek**（首次点击才创建内容，IDEA 启动时不加载）：
  - 「会话」标签：当前项目工作区的会话列表，工具栏含
    新建会话 / 恢复上次 / 刷新 / 管理已归档。
  - 每个运行中的 dsh-tui 会话一个独立终端标签，点 × 关闭标签即结束该会话进程。
- 会话条目：双击恢复；右键：恢复 / 归档 / 重命名 / 删除 / 复制会话 ID。
- 编辑器内选中代码 → 右键 → **DeepSeek → 插入 @文件引用**。
- 设置：Settings → Tools → **DeepSeek Harness (dsh-tui)**
  （启动命令、附加参数、界面语言、外置编辑器、DSH Home、自动引用）。

## 实现说明

- 会话日志解析完全对齐 dsh-tui-vscode：zstd 帧链按 RFC 8878 结构化遍历
  （帧边界定位不依赖解压、支持坏帧重同步）、标题三级回退
  （日志 `session/title` → 存储账本 `session_projcache.json` → 首条人类提问）、
  工作区 cwd 匹配（相等或后代、容器目录仅精确、Windows 大小写不敏感）、
  纯启动会话与子代理会话过滤。
- 终端集成使用 2026.1 重构终端（Reworked Terminal）正式 API：
  `TerminalToolWindowTabsManager.createTabBuilder()` 以
  `shellCommand + envVariables + workingDirectory` 直接以 dsh-tui 为进程创建
  标签页（无「等 shell 就绪再投递」竞态），并通过 `contentManager(...)`
  把标签放进 DeepSeek 工具窗自己的 ContentManager（`requestFocus(false)`，
  避免激活底部 Terminal 窗口）；@引用通过 `TerminalView.sendText`
  键入（不执行）。启动守卫会在 IDEA 启动/终端标签恢复时关掉非本插件创建的
  同名标签，保证只有用户点开插件才会拉起会话进程。
- 插件自身只做本地文件解析与终端编排，不发起任何 HTTP 请求
  （DeepSeek API 调用由 dsh CLI 完成）。

## 许可

MIT
