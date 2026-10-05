# 实测记录 · 2026-10-05 · Axiom MCP 0.2.0

环境：Java 21、Minecraft 1.21.1、NeoForge 21.1.252 正式客户端库。在工作区新建独立超平坦创造世界，不加载用户存档。模组组合：Axiom 本地 NeoForge 版、Axiom MCP 0.2.0、LDLib2、Photon 和 Photon MCP。

构建成功；23 项单元测试，0 失败、0 错误。真实 HTTP 集成测试成功：242 次工具调用，覆盖全部 45 个 MCP 工具；8 次预期错误均被正确拒绝，客户端退出码 0。

## 原生创作验证

- 14 个笔触适配都实际运行原生初始化、pather 路径计算和释放收尾：Freehand Select、Painter、Noise Painter、Biome Painter、Gradient Painter、Script Brush、Freehand Draw、Rock、Weld、Melt、Smooth、Distort、Blend、Shatter。
- 两个稀疏控制点驱动连续体积笔触；原生撤销/重做恢复完整区域；临时设置不修改玩家工具参数。
- 原生石头蒙版限制金块刷涂，不影响周围空气。
- Noise Painter 多八度、多材质阈值，固定种子 7319 在撤销后重刷得到完全相同分布。
- 原生 Lua 笔刷返回绿宝石；Lua 异常在释放前被拒绝，不提交该笔触。
- 原生 Shape 的立方体、球体、旋转圆柱；原生 Path 的 Catmull-Rom、Bezier、道路平面与深度；原生 Modelling 的平面厚度与 Bezier 曲面。形状/曲面的撤销读回完整原区域。
- 复制区域实际转为 Painter 平铺/盖章画笔；原生 Stamp 一次放置两个完整构件，撤销恢复原区域。
- Groovy mcp.stroke(Map) 实际驱动原生笔触。
- 原生亭子示例：底座、四条立柱笔触、两条 Bezier 拱线、Bezier 曲面屋顶、曲线路面，共 9 次原生操作；读回超过 800 个非空气方块，截图并导出 schematic。未枚举建筑方块或使用助手 fill/setBlock。

原先 39 个工具保留代表回归：握手/UI、反射、选区、助手事务、失败不提交、箱子 NBT 与撤销、复制变换粘贴、schematic/structure、四种截图和 3014 个实际改变的庭院测试。

## 日志和边界

操作阶段无 ERROR/FATAL。最终退出阶段出现一次原生 Netty ClosedChannelException（Stopping! 之后），未影响工具结果与退出码；日志保留。未穷举全部参数/GUI 状态和多人协议。较复杂笔刷的测试验证执行及有历史时的回滚，不代表默认参数对每个输入都产生非空效果。

证据：reports/integration-result.json、reports/native-integration-driver.log、reports/integration-latest.log、reports/tool-catalog.json、reports/native-pavilion-scene.png、reports/native-pavilion.schem、reports/installation-result.json。

测试 JAR SHA-256：c55df6191514ec201ec9209b5440de67430c78659812e7752dcbc59a9f325c7a。
