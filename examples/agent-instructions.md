# Axiom 创作 Agent 工作约定

以 Axiom 的原生创作工具作为主要操作方式。一次指令描述笔触、形状、曲线、曲面或复制区域，避免为整座建筑枚举方块。

1. `get_editor_state` 确认世界、握手和权限；`open_editor`；读施工范围和已有建筑。所有坐标为绝对世界方块坐标，高度不假设为 0。
2. `list_tools` 读取原生工具 `keybind`、设置和 `automation`，再 `get_tool` 查询准确参数。材料用 `list_blocks` 查询完整状态。
3. 建立体量优先用 `native_shape`（尺寸/旋转/空心）、`native_path`（曲线/截面/道路）、`native_mesh`（控制网格/曲面/凸包）或 `tool_stroke` 的 FreehandDraw/Rock。传控制点而不是方块列表。
4. 材质和细节用 Painter、NoisePainter、GradientPainter、ScriptBrush 的 `tool_stroke`。`update_mask` 设置原生表达式/Lua，选区同样限制原生笔刷。`points` 为少量路径控制点，连续插值由 Axiom 执行，`sampling:stamps` 为独立盖点。
5. 重复构件用 `set_selection → copy_selection → clipboard_to_brush`；Painter tiled/stamped 刷已有实体，空地放置整块构件用 `stamp_clipboard`。Stamp 的点是表面锚点，底座沿 `face` 偏移一格。保留原生蒙版、变换和方块实体 NBT。
6. 雕刻和修整可用原生 Smooth、Weld、Melt、Distort、Blend、Shatter 笔触；生物群系用 BiomePainter。笔触接口运行原生初始化、路径执行和释放收尾。`settings` 修改临时工具副本，不覆盖玩家 UI 的参数；`block` 设置当前材料。
7. 每次原生操作按原生历史提交。`flush` 直至 `queueEmpty`，读回关键区域；再 `set_view` 和 `screenshot` 多角度观察，按比例、曲面和材质效果迭代。一次估算包围体积至多 262144 方块；较大工程分完整笔触/构件处理。
8. `undo_redo` 使用原生撤销规则；选区历史、活动工具和预览可能影响撤销目标。Path/Mesh 保留可恢复控制点的原生附加撤销信息。
9. `execute_code` 中优先用 `mcp.stroke(Map)`、`mcp.nativeShape(Map)`、`mcp.nativePath(Map)`、`mcp.nativeMesh(Map)`、`mcp.stamp(Map)` 编排原生创作。一次脚本可提交多个原生历史步骤；后续失败不会自动撤销前面的原生步骤。不要把原生操作与暂存方块助手混在同一个脚本。
10. `set_blocks/fill/build_shape/batch` 用于精确修补、测试夹具或指定方块 NBT。这些助手不自动应用工具蒙版/噪声；助手事务要求单人并在成功后提交。
11. 未提供专用适配的功能，先 `list_api_classes → api_lookup → execute_code` 查询真实接口；不要猜私有字段或把可访问 API 当作已经验证的完整流程。
12. 完成后复制选区、`export_schematic` 或 `export_structure`；通过游戏正常保存存档。

原生示例：`native-pavilion.groovy`。Groovy 绑定包括 `client/world/player/axiom/configuration/selection/clipboard/placement/tools/tool/mcp/api/args`。未用 `def` 的变量可持久保留，`reset_context` 清除。不要在游戏主线程 sleep、等待 future 或运行无限循环。多人原生协议尚未回归；权限和授权仍由原版 Axiom 执行。
