# Axiom NeoForge MCP · Minecraft 1.21.1

独立客户端附加模组，把 Axiom 建筑编辑接入 MCP。配置界面以原生 ImGui 窗口注入 Axiom 顶部的 **MCP** 菜单；不替换 Axiom、Photon 或 LDLib 的 JAR。

目标环境：Minecraft **1.21.1** / NeoForge **21.1.252** / Java **21**，依赖相邻工程生成的 **Axiom 6.1.3 本地 NeoForge 适配版**。不保证其他 Axiom 或加载器版本的二进制 API/Mixin 兼容性。

成品：`build/libs/axiom-mcp-neoforge-1.21.1-0.2.0.jar`。使用时与 `Axiom-6.1.3-neoforge-1.21.1-local.jar` 一起放在实例的 `mods/` 中。Photon、Photon MCP 和 LDLib 是独立可选模组，不是本附加模组的依赖。

## 游戏内使用

启动指定 NeoForge 实例、进入允许使用 Axiom 的创造模式世界，按**右 Shift**打开 Axiom。顶部 **MCP → 连接 / 设置**（英文为 Connection / Settings）可以查看连接状态、最近客户端、请求数和错误，修改 Host/Port、启动/停止服务或复制地址。

默认服务在启动时开启，地址：`http://127.0.0.1:8766/mcp`。Photon MCP 默认使用 8765，两者可以并存。配置在实例的 `config/axiom-mcp.json`，只有两个字段：

```json
{"host":"127.0.0.1","port":8766}
```

采用与 Photon MCP 相同的无认证本地服务方式。Groovy 能调用 JVM API，不是沙箱；将 Host 改成网络接口后，能访问该端口的客户端也能执行工具和代码。

## 连接 MCP 客户端

传输为 Streamable HTTP，POST JSON-RPC，结果为 `application/json`。支持 initialize、initialized notification、ping、tools/list、tools/call、会话 DELETE；支持协议版本 2025-03-26、2025-06-18、2025-11-25。可选 GET SSE 流返回 405。

```json
{"mcpServers":{"axiom":{"url":"http://127.0.0.1:8766/mcp"}}}
```

在ccswitch中:
```json
{
  "type": "http",
  "url": "http://127.0.0.1:8766/mcp"
}
```

完整配置见 `examples/mcp-http.json`，可同时连接 Photon。仅支持 stdio 的客户端使用 `examples/mcp-stdio.json` 和 `bridge/axiom-mcp.mjs`，需要 Node.js 18+，不用安装 npm 依赖。

## 原生创作流程（0.2.0）

优先按「选工具 → 配置画笔/蒙版/材料 → 传笔触或控制点 → 原生提交 → 截图迭代」使用。MCP 不需要列举建筑里的每个方块。

- `axiom_tool_stroke`：执行原生笔刷。`points` 是少量绝对世界方块中心控制点，默认连续插值；`sampling:"stamps"` 为独立盖点。Axiom 自己计算体积、材质、噪声、蒙版和选区，执行松开画笔时的收尾与历史提交。
- `axiom_native_shape`：原生 ShapeTool 的形状、尺寸、旋转、空心等；包含球、圆柱、圆环、胶囊、锥体、多面体和平面形状。
- `axiom_native_path`：原生 PathTool 的曲线、道路和剪贴板截面，包括 Catmull-Rom、Bezier 和悬链线；保留原生可恢复控制点的撤销信息。
- `axiom_native_mesh`：原生 ModellingTool 的控制点网格、曲面、凸包和三角形条带；保留原生建模撤销信息。
- `axiom_clipboard_to_brush`：选区复制后转为 Painter 平铺/盖章画笔，或者加入 Stamp 蓝图。
- `axiom_stamp_clipboard`：原生 StampTool 按表面锚点批量放置整块复制区域，可用原生旋转/翻转、蒙版模式和容器 NBT。

`list_tools` 中的 `automation` 明确标出已接入的执行入口。14 种笔触适配：Freehand Select、Painter、Noise Painter、Biome Painter、Gradient Painter、Script Brush、Freehand Draw、Rock、Weld、Melt、Smooth、Distort、Blend、Shatter。工具名用返回的 `keybind` 或 Java 简类名，避免假设索引稳定。

```json
{
  "tool":"NoisePainterTool",
  "points":[[10,70,10],[24,73,16],[36,70,24]],
  "settings":{
    "BrushRadius":5,"MaskSurface":1,"NoiseSeed":"7319","Octaves":3,
    "NoiseScaleX":8,"NoiseScaleY":8,"NoiseScaleZ":8,
    "BlockThresholds":[
      {"Block":"minecraft:stone","Percentage":65.0},
      {"Block":"minecraft:andesite","Percentage":35.0}
    ]
  }
}
```

以上参数用于 `axiom_tool_stroke`，Noise Painter 会刷已有实体表面。先用原生 Shape、Path、Mesh 或 Freehand Draw 建立体量，再刷材质。全局 `update_mask` 同样参与原生笔触；例如 `block = minecraft:stone` 只改石头。

Painter 的平铺/盖章剪贴板模式属于刷材质，仍过滤实体目标及表面；往空地放置整块蓝图使用 Stamp。`stamp_clipboard.points` 为表面方块，实际底座沿 `face` 偏移一格，默认 up。

笔触使用临时原生工具副本，`settings` 在该副本合并，保持游戏中工具面板的设置。`block` 会设置原生当前材料。须先打开 Axiom 编辑器并关闭其他游戏屏幕；遵守 Axiom 权限、选区与过滤。每次限制已加载包围体积 262144 方块及 8192 路径采样点，较大工程分成若干完整笔触。`dispatch.queueEmpty` 为 false 时继续 flush。Script Brush 支持原生 Lua；`$once$` 点动作使用原生 API，笔触接口明确拒绝。

## 45 个专用工具

真实 schema 可通过 `tools/list` 获取，并保存在测试产物 `reports/tool-catalog.json`。

| 功能 | MCP 工具 |
| --- | --- |
| 原生创作（优先） | `axiom_tool_stroke`, `axiom_native_shape`, `axiom_native_path`, `axiom_native_mesh`, `axiom_clipboard_to_brush`, `axiom_stamp_clipboard` |
| 服务与编辑器 | `axiom_get_server_status`, `axiom_get_editor_state`, `axiom_open_editor`, `axiom_close_editor` |
| 原生 API | `axiom_execute_code`, `axiom_api_lookup`, `axiom_list_api_classes`, `axiom_editor_action` |
| 所有注册工具 | `axiom_list_tools`, `axiom_get_tool`, `axiom_select_tool`, `axiom_update_tool` |
| 选区 | `axiom_get_selection`, `axiom_set_selection`, `axiom_clear_selection`, `axiom_move_selection` |
| 方块与建筑 | `axiom_get_blocks`, `axiom_list_blocks`, `axiom_set_active_block`, `axiom_set_blocks`, `axiom_fill`, `axiom_build_shape`, `axiom_batch` |
| 历史与原生操作 | `axiom_get_history`, `axiom_undo_redo`, `axiom_native_operation`, `axiom_flush` |
| 剪贴板与文件 | `axiom_get_clipboard`, `axiom_copy_selection`, `axiom_transform_clipboard`, `axiom_paste_clipboard`, `axiom_import_schematic`, `axiom_export_schematic`, `axiom_export_structure` |
| 窗口与工具遮罩 | `axiom_editor_windows`, `axiom_get_mask`, `axiom_update_mask` |
| 相机与视觉反馈 | `axiom_set_view`, `axiom_screenshot` |

这 45 个工具不是能力白名单。Axiom 的所有已注册工具可以查询原生 class 与设置；其他已安装的原生 API 可通过反射查询和 Groovy 访问。原生 GUI 按钮可能依赖特定窗口、工具状态或授权，不能把“可访问 API”解释成每个组合已经验证。能力覆盖与测试边界见 `COVERAGE.md`。

## 建筑操作与撤销

坐标是**绝对世界方块坐标**，包围盒两端都包含。支持完整方块状态，如 `minecraft:oak_stairs[facing=east,half=top]`。`fill` 可使用明确的 min/max 或当前选区，支持空心和按完整状态替换；几何体包括 box、ellipsoid、Y 轴 cylinder、line、pyramid。

`set_blocks`、`fill`、`build_shape`、`batch` 与脚本 `mcp.setBlock/fill` 助手会先暂存改动，从单人服务端制作原状态与方块实体 NBT 快照，再通过 Axiom 的 `Dispatcher.push` 提交为一个原生历史步骤。批量参数错误或脚本抛异常不会提交助手暂存的数据。相同坐标最后一次设置生效。一次最多 **262144** 个不同方块；读回工具一次最多 **32768** 个方块。范围必须在已加载的区块内。

这套助手事务目前要求**单人世界**，以保证撤销快照来自真实服务端。多人场景会明确报错，原生 Axiom API 仍按其权限与协议工作，但多人兼容性没有完成验证。不能依靠脚本绕过 Axiom 授权或服务器权限。

`axiom_flush` 返回服务端/主线程屏障结果和 `pendingDispatchTasks`；队列未清空时需要继续等待并检查。提交后用 `get_blocks` 读回关键位置。原生撤销也会处理选区历史和粘贴预览，按原版规则执行，不是简单地撤销最后一条 MCP 请求。

## Groovy / execute code

内置 Groovy **4.0.28**，在 Minecraft 主线程执行。

绑定：`client`, `world`, `player`, `axiom`, `configuration`, `selection`（Class）, `clipboard`, `placement`, `tools`, `tool`, `mcp`, `api`, `args`（Map）。脚本 `stdout` 和返回值转为 MCP JSON。绑定变量每次更新，未使用 `def` 的脚本变量可跨调用保留；`reset_context:true` 清除它们。

```json
{
  "code": "mcp.fill(0,64,0,8,64,8,'minecraft:stone_bricks'); for (int y=65;y<70;y++) mcp.setBlock(0,y,0,'minecraft:quartz_pillar'); println tools.size(); return [built:true]",
  "description": "MCP terrace"
}
```

`mcp.block(spec)` 返回原生 BlockState，`mcp.pos(x,y,z)` 返回 BlockPos，`mcp.setBlock(BlockPos,BlockState,CompoundTag)` 支持方块实体 NBT。`mcp.getBlock` 能看到本次助手事务中已经暂存的改变。`mcp.state()` 返回当前编辑器状态。

其他操作可直接导入 Axiom 类，用 `axiom_list_api_classes` 与 `axiom_api_lookup` 查询真实方法、重载和字段。`api.read(object,"field")` / `api.write(object,"field",value)` 可操作私有字段。

`mcp.stroke/nativeShape/nativePath/nativeMesh/stamp(Map)` 直接执行原生创作工具，各操作独立提交原生历史；脚本结束后返回调度屏障。它们不能和暂存助手混在一个脚本。只有 `mcp.setBlock/fill` 暂存的方块自动合并成一次历史记录；直接原生调用、选区、文件、GPU/UI 和其他副作用不会自动回滚。请求 30 秒超时无法安全中断已经开始运行的 JVM 代码，不要在游戏主线程运行无限循环、sleep 或等待 future。需要截图时先返回脚本，再调用截图工具。

## 截图

`axiom_screenshot` 返回标准 MCP `image/png` 内容，可选保存本地文件。默认等待 10 个完整帧，在最终 `RenderTarget.blitToScreen` 完成后采集。`wait_frames` 可设为 2..120；大规模修改或移动相机后可增加等待，让区块网格完成更新。

- `editor`：包含 Axiom 菜单、面板和世界视口的最终合成画面。
- `screen`：当前完整画面，编辑器可以关闭。
- `scene`：世界渲染视口，尺寸可能小于游戏窗口。
- `ui`：Axiom UI 独立渲染层，透明区域不含世界内容。

`max_size` 为 64..4096，保持比例缩小；`path` 为绝对路径或相对游戏实例的路径。

## 建筑示例

优先运行 `examples/native-pavilion.groovy`：通过原生形状、四条体积笔触、Bezier 拱线、Bezier 曲面屋顶和曲线路径生成亭子；不枚举建筑方块。通过 `execute_code` 传入 `args.origin`，每个原生操作都有独立历史步骤。


`examples/courtyard-villa.groovy` 生成带庭院、水池、两层窗户、柱廊、楼梯和屋檐的建筑，并作为一个助手历史步骤提交。

```powershell
python examples/build-villa.py --origin 32 -59 16 --output villa-result
```

运行前进入世界并确保施工范围已加载。示例会清空并替换原点附近 **25 × 20 × 20** 的体积，再建造、截图、导出 `.schem`；不要把默认测试坐标用于已有建筑。使用你准备的空地坐标。Agent 的迭代工作约定见 `examples/agent-instructions.md`。

## 构建与测试

先构建相邻的 `Axiom-NeoForge/` 工程，然后在这里执行：

```powershell
.\gradlew.bat build
.\gradlew.bat installMod
python tools/run_integration.py
```

`gameDir` 在 `gradle.properties` 中指定，可通过 `-PgameDir=...` 覆盖。Groovy 通过 Jar-in-Jar 携带，Axiom 不打包进本附加模组。

集成测试使用已安装的正式 NeoForge 库，并在 `run-production/` 新建独立创造世界；账号为合成离线身份，不加载用户存档。使用测试端口 18766 / 18765，不改变游戏实例的服务配置。正常游戏没有 `axiom.mcp.testing=true` 时不会运行自动测试世界流程。

测试记录与截图见 `reports/`，包含成品 JAR 的 SHA-256。单元测试覆盖协议错误、会话、配置、Groovy、NBT 类型保留和坐标边界。集成测试通过真实 HTTP 调用全部 45 个工具的代表路径，并检查服务端方块、撤销 NBT、脚本失败不提交、原生 UI 和 GPU 截图；没有逐项穷举所有原生工具与所有参数组合。

## 来源

MCP 传输、脚本/反射工具、NBT 转换和 stdio bridge 参考并改编工作区 `PhotonMCP-1.21` 的 Apache-2.0 代码，修改声明和归属保存在源码与 `NOTICE`。本项目使用 Apache-2.0。Axiom 为独立依赖，其许可和授权逻辑保持原样。
