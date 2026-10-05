# 能力覆盖与验证边界

0.2.0 优先以原生工具、笔触和控制点创作。45 个 MCP 工具包含 6 个新增原生创作入口，全部通过真实 HTTP 代表路径。查询仍基于安装的真实二进制。

新增验证：14 种原生笔触均运行初始化/pather/释放；原生蒙版实际限制刷涂，Noise Painter 固定种子分布复现，Lua 笔刷成功与失败分支；原生形状旋转、曲线路面、网格厚度、Bezier 曲面；复制区域转平铺/盖章画笔、Stamp 两构件放置与撤销。原生曲面亭子用 9 次工具操作完成，未枚举建筑方块。

| 原生创作 | 入口 | 边界 |
| --- | --- | --- |
| 14 种体积/选择笔触 | tool_stroke | 全部实际执行；不是每个参数组合和每种默认输入都有非空效果 |
| 形状/旋转 | native_shape | 18 种形状接入；cuboid/sphere/旋转 cylinder 已验证 |
| 曲线/道路/剪贴板截面 | native_path | Catmull-Rom/Bezier/道路平面深度验证，其余曲线/截面未逐项验证 |
| 曲面/凸包/三角形条带 | native_mesh | flat surface 厚度、Bezier 曲面与撤销验证；其他模式接口接入 |
| 区域画笔/整块蓝图 | clipboard_to_brush、stamp_clipboard | Painter 平铺/盖章及 Stamp 整构件验证；容器盖章 NBT 复用原版，未单独回归 |

笔触使用临时原生工具对象，保留玩家工具 UI 参数；材料、蒙版和选区为原生共享状态。Painter/Noise Painter 刷已有实体，建立体量用 Freehand Draw/Shape/Path/Mesh，空地放构件用 Stamp。Path/Mesh 保留原生控制点撤销信息。

原生操作各自立即提交历史，一串脚本后续失败不回滚先前操作。Lua 单笔失败在释放前拒绝。mcp.stroke/nativeShape/nativePath/nativeMesh/stamp 不和暂存助手混用。

以下为原有能力与回归边界：

| Axiom 能力 | 入口 | 当前验证 |
| --- | --- | --- |
| 编辑器开启、关闭、MCP 状态/设置 | 专用工具与原生顶部 MCP 菜单 | 游戏启动、面板注入、活动渲染帧、完整合成截图 |
| 32 个注册工具、工具选中和序列化设置 | list/get/select/update_tool；execute_code | 全部工具枚举、Painter 设置读取与原样更新、选择；没有穷举每种笔刷 |
| 方块状态与容器 NBT | get_blocks、list_blocks、set_blocks、mcp 助手 | 朝向方块查询、服务端读回、箱子物品 NBT 与撤销恢复 |
| 选区及集合修改 | set/move/clear_selection；原生 Selection API | 立方选区计数、移动、扩展/收缩；复杂异形集合可用原生 API |
| 填充、替换、空心、基本几何 | fill、build_shape、batch、mcp 助手 | 五种几何与批量代表调用、一次历史步骤、失败不提交 |
| 原生历史、撤销、重做 | get_history、undo_redo、Dispatcher API | 服务端方块撤销重做、方块实体 NBT 恢复 |
| 复制、旋转、翻转、粘贴 | clipboard 工具、Placement API | 复制 48 个方块、Y 轴旋转、X 翻转、确认粘贴；实体复制接口已接入但未作实体回归 |
| schematic / structure 文件 | import_schematic、export_schematic、export_structure | Sponge v2 导出后回读、vanilla NBT 文件；Litematic/legacy 加载接原版接口但未用样本验证 |
| 工具遮罩与 Lua | get/update_mask、tool_stroke | 表达式限制实际刷涂、Lua 笔刷与失败分支；Lua 蒙版未覆盖全部语义 |
| 排水、水淹、空心、填缝、重力、生物群系等 | native_operation、原生 operations 包 | 扩展/收缩/更新代表调用；其余复用原生 API，未逐项穷举 |
| 调色板、文字、显示实体、标注、工具预设、视图管理 | list_api_classes → api_lookup → execute_code | 类/方法/字段可发现，Groovy 原生对象访问实测；不是为每项提供单独 MCP schema，未逐项验证 |
| 摄像机与视觉反馈 | set_view、screenshot | 设置坐标/朝向，editor/scene/ui/screen 四种 GPU 读回 |
| 文件与其他 JVM 能力 | execute_code | 无 JVM 沙箱；助手历史不能回滚所有原生副作用 |
| 多人、独立服务端、其他版本 | 原生 Axiom 通信 | 未验证；本附加模组为客户端，助手事务明确要求单人 |

`set_blocks/fill/build_shape/batch` 和 `mcp.setBlock/fill` 助手是直接建筑指令，不应用工具遮罩、笔刷平滑或原生工具的全部输入状态。若需要这些行为，调用相应原生工具/操作并检查其上下文。GUI 操作以 `UserAction` 和原生类 API 为入口，不能保证每个依赖鼠标射线或渲染状态的 GUI 动作都可以仅凭一个 action 名称正确执行。

助手事务在暂存阶段失败时不会提交方块；提交一旦开始，遵循 Axiom 原生调度和历史语义。服务器规则、连接变化或原生执行异常可能影响结果，所以提交后仍应 flush 和读回。大批量作业分区执行，避免长时间占用游戏主线程。
