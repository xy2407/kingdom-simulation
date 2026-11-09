# AUI 使用总结（kingdom 工程 · 页面适配 / 踩坑 / AUI 问题清单）

## 0.5 AUI 1.3.1 破坏性变更与我们已做的适配（2026-11-08）

> 升级到 `ApricityUI-neoforge-1.21.1-1.3.1.jar` 后，以下两处是"老代码静默/崩溃"的根因，已修完。

1. **`SlotDataBinder.bindingsByGlobalIndex` 的字段类型从 `Map` 变成了 `LinkedHashMap<Integer, SlotBinding>`。**
   Mixin 的 `@Accessor` 按字段**描述符**匹配，声明成 `Map` 会直接抛
   `InvalidAccessorException: No candidates were found`，而它发生在 `ApricityContainerScreen.init()`
   里 → **任何 AUI 容器屏（NPC 背包、建筑仓库）一打开就炸**。
   修法：访问器返回类型改成 `LinkedHashMap<Integer, ?>`（`AuiSlotScrollOffsetMixin`）。
   **判据**：以后 AUI 改字段类型，`@Accessor` 必须跟着改，泛型擦除后的裸类型（`List` / `LinkedHashMap`）才是描述符。
2. **`<canvas>` 自 1.3.0 起按"内容盒的设备分辨率"栅格化，不再等于 CSS 像素。**
   - `getWidth()/getHeight()` → **设计（CSS）尺寸**（`logicalWidth/logicalHeight` 取整）
   - `getBitmapWidth()/getBitmapHeight()` → **光栅像素尺寸**（= 设计尺寸 × `getDeviceScale()`）
   - `resizeSurface(w,h,false)` 传入设计尺寸，内部已乘设备缩放
   - `renderOperation(g -> …)` **不会**替你缩放，Graphics2D 坐标就是光栅像素
   → 旧写法"按 `getWidth()` 建数组、按设计坐标画"会让内容只占左上角 1/scale，表现为
   **地图大面积空白、头像/印章缩成小方块**。
   修法三件套：① 像素缓冲用 `getBitmapWidth/Height`；② 像素级坐标（每方块像素数）乘 `getDeviceScale()`；
   ③ `renderOperation` 里先 `graphics.scale(device, device)` 再按设计坐标画；④ `ImageDrawer` 的 UV 窗口
   用光栅尺寸并把 UV 偏移乘设备缩放（`WorldMapElement`）。
3. 其余 API 复核结论（2026-11-08，对 1.3.1 jar 逐个 `javap`）：我们 import 的 **37 个 AUI 类全部存在**；
   `Document.applyViewport(boolean)` / `screenToDocumentPosition` / `getViewportScaleX` / `inWorld`、
   `Element.setInnerHTML/addEventListener/setAttribute/isHover/parentElement/getScrollLeft`、
   `Input.getValue`（`setValue` 继承自 `Element`）、`Base.pushDocumentZOffset/commitDraws/pushDepthTest`、
   `ApricityUI.createDocument/getDocument/getAllDocument/removeDocument/createWorldWindow/removeWorldWindow`、
   `ApricityUIRegistry.scanPackage/register`、`WorldWindow.setFacing/setDepthTest/setScale/setPosition/render`
   均未变；`SlotDataBinder$SlotBinding` 仍是 record，`slotElement()/globalIndex()` 反射取值可用。
4. 仍待实测：`AuiSlotMenuOffsetMixin` / `AuiScissorOffsetMixin` 的偏移补偿是否与 1.3.1 新增的
   "resize 复用同一 Document" 冲突（若出现"看得见点不到"或裁剪偏半格，先查这两处）。

### 0.5.1 信封/择旗页（start.html）偏移与错误缩放的三个成因（2026-11-08 已修）

**① 自定义 `<canvas>` 元素：光栅尺寸变了却不重画。**
判断"要不要重画"用的是 `wantedWidth == getWidth()`，而 `getWidth()` 是**设计尺寸**，设备缩放变化时它不变
→ 首帧按 `device=1` 画好，之后设备缩放变 2/3，AUI 已把光栅改成 2/3 倍，内容却不再重画，只剩左上角一块。
**判据**：任何"自己 `resizeSurface` 的超采样画布"，重绘签名里**必须带 `getBitmapWidth/getBitmapHeight`**。
`KingdomShieldElement` / `KingdomSealElement` / `KingdomEntityAvatarElement` 已改。

**② 自定义 `<canvas>` 元素：绘制原点没做设备像素吸附。**
原生 `Canvas.drawCanvas` 用 `DevicePixelGrid.snap(x, scale)` 把原点吸到设备像素网格；自定义元素直接拿
`rectRenderer.getContentPosition()` 画，落在半像素上 → 整块纹理轻微偏移 + 发虚。
`DevicePixelGrid.snap` 是 public 静态方法，直接用。

**③ 绝对/固定定位元素自身的 `margin` 不参与绘制位置。**
`Position.forRender` 的循环里只有 `e != element` 时才累加 `Box#getMarginLeft/Top`，也就是**元素自己的外边距
不会被算进它自己的绘制定位**。于是 `left:50%; margin-left:-52px`（火漆印章、toast）这类"负外边距居中"
会整体右偏半个宽度。**修法**：改用 `left:calc(50% - 52px)`（`CssLength` 支持百分比+px 的 calc 加法）。
同类写法在别的页面里也要按这条排查。

**④ `width`/`height` 属性（1.3.0 起是"内在设计尺寸"）必须与 CSS 实际尺寸一致。**
信封页原来 `kingdom-shield width="40" height="48"` 装在 `80×96` 的 `.shield-wrap` 里、
`<kingdom-seal width="52" height="52">` 装在 `104×104` 的 `.wax-seal` 里。属性值与 CSS 不一致时，
布局/`syncDimensionsFromAttributes` 之间会互相打架，表现为"缩一半 + 偏到左上角"。
**规矩**：给自定义 canvas 元素写属性时，数值 = 它在 CSS 里的实际盒子尺寸；超采样倍数放在 Java 常量里，
不要用属性值。

**⑤ 按钮栏（`.actionbar`）的三个加固**（针对"按钮偏移"）：
- 居中从 `margin:0 auto` 改成父级 `display:flex; justify-content:center` + `width:100%; max-width:1100px`；
- `.btn` / `.actionbar-inner` 显式 `box-sizing:border-box`，不依赖 `*` 通配是否命中；
- `.sel-info` 的 `flex:1` 展开成 `flex-grow/flex-shrink/flex-basis`（简写没被完整解析时按钮会挤到左边）。
- `.overlay` 的 `inset:0` 补上 `left/right/top/bottom` 四边（`inset` 简写本身是支持的，这里是双保险）。

### 0.5.2 全量 UI 复核（2026-11-08，10 个页面 + 10 个 mixin + 全部自定义元素）

#### 已修

**① `backpack.html` 的 `html,body` 缺 `height:100%`。**
body 上的 `justify-content:center` 只在 body 比内容高时才有空间可分配；只写 `overflow` 会让 body 高度塌成内容高度
→ 卡片贴文档顶部、下方空出一大块，观感就是"整体偏上"。`builder / city_hall / identify` 都有这一句，只有背包漏了。
**规矩**：fixed 模式页面要在 body 里做居中，`html,body{height:100%}` 是前提。

**② 停用 `AuiSlotScrollOffsetMixin`（已从 mixin 配置移除）。**
1.3.1 的 `Slot#getSlotVisualBounds()` → `getBoundingClientRect()` → `Rect.position = Position.forRender(element)`，
而 `forRender` 里对每一层祖先都减了 `getScrollLeft()/getScrollTop()`。**滚动补偿已经在 AUI 内部做完了**，
我们的 mixin 再减一次等于算两遍 → 容器屏滚动后槽位可点击区与画面错开（滚动越多偏得越远）。
**判据**：给 AUI 打滚动补丁前，先看 `Position.forRender` 是否已经减掉 scroll。

#### 核对无误、不需要改的

| 检查项 | 结论 |
|---|---|
| 10 个 mixin 的注入目标 | `ApricityScreen.render` / `Document.applyViewport` / `ApricityContainerScreen.drawLinkedDocument`(private) / `captureFloatingItem` / `Mask.enableScissor(double×4)` / `SlotDataBinder.syncAllSlotPositions(Document,int,int,boolean)` / `LayoutMeasureCache.begin,end` —— 签名全部匹配，`defaultRequire:1` 下没报错即为注入成功 |
| `@Accessor` 字段类型 | `floatingItemNodes: List<RenderNode$ItemNode>`、`slotBinder: SlotDataBinder`、`bindingsByGlobalIndex: LinkedHashMap` 均已按 1.3.1 实际类型声明 |
| `Graph.beginBatch/drawFillRect/endBatch`、`Rect.of/drawShadow/drawBody/drawBorder/getBodyRectPosition/getBodyRectSize/getContentPosition`、`Base.commitDraws/pushDepthTest/popDepthTest`、`Box.of/innerSize`、`Element(Document,String)` | 全部存在，`npc-bar / npc-triangle / structure-preview / kingdom-npc-model` 无需改 |
| `left:50% + transform:translateX(-50%)`（`city_hall`/`identify` 的分隔菱形） | **正确写法**。`Base.computeWorldTransform` 调 `prepareTransform(e, rect.getShadowSize())`，百分比基准是元素自身尺寸，符合 CSS 规范。**不要**改成 calc |
| `AuiSlotMenuOffsetMixin` | **必须保留**。`syncAllSlotPositions` 内部只算 `rect.x * viewportScaleX`，**没有**加居中偏移，槽位需要这个 mixin 补 `documentToGuiPosition(0,0)` |
| `AuiScissorOffsetMixin` | **必须保留**。`Mask.enableScissor` 收的是文档坐标（内部乘 `scissorScale`），文档被整体平移时裁剪框要同样平移 |
| `AuiMeasureCacheGateMixin` / `AuiSlotSyncMeasureCacheMixin` | 1.3.1 的缓存条目自带 `layoutDependency` 版本校验，跨帧保留不会返回过期值，保留无害 |
| fixed 页面的居中（`AuiDocumentCenterMixin` + `AuiScreenDocCenterMixin`） | 依然需要：1.3.1 的 `applyViewport` 恒把 offset 写成 0，AUI 自己不居中。窗口 resize 后 `ApricityScreen.init()` 会复用 Document 但**仍会重跑 `applyViewport(false)`**，偏移会跟着更新 |
| 各页 `mode=fixed` 的 `scale` | `backpack/builder/city_hall` 用 `fit` ✓；`identify` 是 **WorldWindow**（世界内 3D 卡片，`inWorld=true`，渲染走 `CARD_SCALE`），`scale=1` 是文档像素基准，**不能改成 fit** |
| 自定义元素尺寸 | 除 `start.html` 的盾徽/印章（属性值 40×48 / 52 与 CSS 的 80×96 / 104 不一致，已修）外，其余全部由 CSS `width/height:100%` 决定，`world-map / kingdom-entity-avatar` 不带尺寸属性也没有问题 |
| `structures/viewer.html` 的 `.dock/.side/.building-tip` | `mode=gui` 下 `position:fixed` 相对视口，`left/right/bottom` 四边齐全，无负 margin |



本文件是 `assets/原始html/` 里那三份网页稿，适配成 AUI 可运行页面的**完整规范 + 踩坑记录**。

**证据规则**：所有结论来自**实读 AUI 源码 / 反编译产物 / 实测日志**，不凭记忆。证据统一写成
`文件#方法`，**不写行号**——源码一改行号就烂（本次核验发现整章 `Flex` 行号全部失效）。
需求方给的 `AUI-snow/docs/guide/*.md` 是官方文档，**与源码冲突时以源码为准**（已实测一处：槽位交互能力优先级，见 §7.9）。
查证方法见 §14。

> **核验状态（2026-10-02，对照 `AUI-snow` 源码：kingdom 用的 `ApricityUI-neoforge-1.21.1-1.2.5.jar`
> 对应 `targets/neoforge-1.21.1` + `common`）** —— 本文件所有结论分三档，按档信任：
>
> | 档 | 含义 | 覆盖 |
> |---|---|---|
> | ✅ **源码实证** | 逐行核过源码；写的是源码行为，不是现象猜测 | §3 全部、§4.4、§7.2/7.3/7.6/7.7/7.8/7.9/7.10/7.11/7.12、§8.1/8.3、§9.5、§13.3/13.6/13.10、§14、§16 的 D1–D25（标 🕐 的除外） |
> | 🕐 **历史实测** | 当初有截图/看门狗报告/浏览器对比支撑，本轮没再回源码复核；结论可信，但证据是现象而非代码 | 横排 flex `gap`、grid `fr`、`aspect-ratio`（§2、坑 8/19/20/21）、Zpix 字形表（§6.2）、看门狗三份报告的栈与 CPU 数字（坑 17/18） |
> | ⚠ **待实测确认** | 归因链完整、缺"开关式"验证，正文已就地标注 | ① `isQuadVisible` 判定矩形少挪半个面板是否真会误剔（§16 D24）；② "世界窗口只在固定角度可见"是否就是 `facingEnabled` 默认 false（§16 D25，判别法：勾调试面板的 `facing-enabled`）；③ 登记窗口后是否双渲染（§11 坑 36） |
>
> **本轮修正/补精确的结论**（旧版本写错或写粗的，别按记忆）：
> `scale=fit` 的基准是**逻辑视口**（§3.2）；裁剪换算在 `Mask#applyScissor → #enableScissor`（§13.10）；
> `Flex` 行号全废、改方法名（§7.6/§7.7）；槽位交互能力**HTML 属性优先于 CSS**（§7.9）；
> 根元素 `overflow` 是**条件雷**不是绝对禁令（§4.4）；`viewer.html` 就在 kingdom 资源里（§1）；
> 实体数据源的距离约束在 `EntityDataSource#stillValid`（`distanceToSqr ≤ 64` = 8 格，§7.3）。
>
> **最近修订（2026-10-11）**：市政厅「政务处理」区改为渲染可办理的政务行（§11.A 坑 51）；世界地图加入右键**逐格划选**与购买确认弹窗（§11.A 坑 52/53/54）；领地模型改为**区块集合**，支持任意形状，不再按矩形扩张；地图**拖动改为 uv 窗口平移的静态快照**，拖动期间零重绘零上传（§11.A 坑 55）；
> `structures/viewer.html` 改为**贴边隐藏式双栏**（底栏 + 左栏，鼠标贴边才展开，底栏层级高于左栏、左栏上抬让位，§11.A 坑 56），
> 底栏 **8 等分**（住宅/农业/养殖/商业/工业/军事/仓库/其它，点一格进该分类）、左栏 **4 等分**（人口/建筑/队伍/道路），等分尺寸由 Java 按视口算好写进 inline style（§11.A 坑 57）；
> 分类里是**建筑卡片**（3D 预览 + json 的 `name`），左上角搜索框按名称过滤，**分类严格按蓝图所在目录**、不看 json 的 `type`；
> HUD 覆盖层收不到 AUI 的字符事件，搜索框的输入靠自建 GLFW 输入代理（§11.A 坑 58）；
> 左栏支持「索引页 ↔ 内容页」两态，人口页（市民条形卡片 + 搜索 + 相机跳转）与建筑页同构，两个搜索框共用一份输入代理（§11.A 坑 59）；
> 同时修掉建筑迁移投影丢旋转：`BuildingSyncPacket` 补 `rotation` 字段后，投影按建筑原朝向起手（`client/rts/ClientBuildingController#tryPick`）；
> 新增**世界道路地图页**（`ui/world_road.html` + `WorldRoadScreen`，道路系统阶段 4）：`<world-map>` 右键按页分流（市政厅买地框选 ↔ 世界道路画折线），折线常驻层画在 `drawOverlay`；Screen 页按键要防外部 `InputEvent.Key` 监听双触发（§11.A 坑 61）；
> 订正了"主题要不要按 §2 大砍一遍"的口径 —— 官方 `ore.css` 自己就在用 `transform` / `@keyframes` / `calc()`，那批清单只针对会卡死的那几条（§11.A 坑 60）；
> 视觉改走**我们自己的像素中世纪主题**（令牌与组件类内联自 `aui经验/theme/pixel-medieval.css`，见 `aui经验/theme/AUI适配指南.md`）。
> **最近修订（2026-10-09）**：定稿页面的 `scale` 从**固定数字倍率**统一改成 **`fit`**。
> 固定数字走 `ApricityViewport#fixed` 的 `else` 分支（不读窗口）⇒ `renderScale` 是常量 ⇒
> 窗口一缩就"页面不跟着缩 + 居中偏移失义"（文档甚至可能比视口还大被裁）。
> 涉及 `city_hall.html`（`0.45`）、`backpack.html` / `storage.html`（`0.4864`）三页。
> 详见 **§11.A 坑 50**；已同步 §0 铁律 1、§3.2、§13.3、§1 表格、§12 checklist。
>
> **结构**：**§11 = 踩坑速查表**，分 **11.A 我们自己的坑**（可直接修）与 **11.B AUI 侧行为造成的坑**（只列现象与绕过）；
> **§16 = AUI 侧问题清单**（D1–D25，含 **§16.6 世界窗口专项**：朝向 / 剔除 / 矩阵 / 登记）；
> **§15 = 像素风中世纪主题 HTML 编写与 AUI 适配指南**；**§17 = 官方文档核验**（对照 `doc.sighs.cc/ApricityUI/`）；**§18 = 遗留事项**（原 §15）。

---

## 0. 先看这条：最容易翻车的铁律

1. **像素字体必须接近 1:1**。像素字体（Zpix）被非整数缩放会重采样成"糊/发虚"，像素感消失。
   普通页面一律 `zoom=1,min-zoom=1,max-zoom=1`；**定稿整稿页**（`Screen` / 容器屏，§13.3）用
   `scale=fit`（倍率随窗口变，见铁律 2 的例外），代价是字会略糊——**这比"窗口一变就错位"值得**：
   固定数字倍率是绝对倍率，会让页面不随窗口缩放、居中偏移也随之失义（§11.A 坑 50）。
2. **尺寸必须按"页面形态"分别对待**（以前的版本把这条写成"绝不写死尺寸"，与 §13 的整稿方案自相矛盾）：
   - **普通屏页面**（`start.html`、`identify.html`）：逻辑视口 = `窗口尺寸 ÷ min(GUI Scale, 5)`，
     GUI Scale 2 时 1086×568 的窗口只有 **543×284** 逻辑像素——这类页面的尺寸由 Java 按
     `Document.getViewportSize()` 现算（§4.3）。
   - **容器屏整稿页面**（`backpack.html`）：按"固定设计尺寸 + 固定倍率"整稿缩放（§13.3），
     尺寸写死在设计稿的坐标系里是**预期行为**，但必须整稿装得进视口、且不能滚动（§4.4）。
3. **只用"在这页里已被验证能用"的机制**。可用：flex（纵向）、显式 px、`box-shadow` 多段、
   `repeating-linear-gradient`、固定列数的 `grid`（§7 的槽位阵列）、
   **`position:fixed` + `%` 宽高（`mode=gui` 的纯 DOM 页面里可用，`residential.html` 的侧边栏就是这么写的）**。
   不要碰：`fr` 分配、`calc()`、`vw`、`transform`、`filter`、`canvas`、`<script>`（§2）。
   注意区分：上面"不要碰"里的 `position:fixed` 只指**配 `left:50%` + 负 margin 做居中**那种用法（坑 3）；
   fixed 本身在纯 DOM 页是好的，别一刀切。
4. **容器屏上任何 `flex-wrap` 都不能留**——它不是"样式问题"，是把客户端**卡死**（§7.6、§9）。
5. **有真实槽位的页面绝对不能写 `<meta name="aui-mouse-events" content="intercept">`**：
   它会吃掉原生点击，槽位全部点不动（§11 坑 32）。
6. **文档居中改造必须成对**：输入映射、画面平移、裁剪框、菜单槽位坐标四处缺一不可（§10、§13.10）。
7. **改完立刻回读校验**：本仓库出现过"同一批替换一半生效一半没生效"（编辑器旧缓冲区写回）。
   关键文件用一次性原子改写后，回读关键行确认（§10）。

---

## 1. 三份页面的对应关系与现状

| 原始稿（`assets/原始html/`） | AUI 版（`assets/apricityui/apricity/ui/`） | 宿主 | viewport meta（实测现状） | Java 侧 |
|---|---|---|---|---|
| `start.html`（立国建邦） | `start.html` | `ApricityScreen` | `mode=gui,zoom=1,min-zoom=1,max-zoom=1,user-scalable=false` + `aui-mouse-events: intercept` | `client/faction/FactionSelectScreen.java`（`PAGE` 在 `FactionSelectPage.java`） |
| `identify.html`（NPC 身份卡） | `identify.html` | `WorldWindow`（世界内窗口） | `mode=fixed,width=420,height=704,scale=1,zoom=1,min-zoom=1,max-zoom=1,user-scalable=false` | `client/aui/NpcIdentifyWindow.java`（`WorldWindow(PAGE, anchor, DISPLAY_RANGE)`） |
| `backpack.html`（市民行装） | `backpack.html`（1:1 还原版，§13） | `ApricityContainerScreen`（真实菜单） | `mode=fixed,width=1200,height=1182,scale=fit,zoom=1,min-zoom=1,max-zoom=1,user-scalable=false`（§13.3、§11.A 坑 50）；**不写** `aui-mouse-events` | `common/npc/NpcBackpackMenu.java`（`PAGE="ui/backpack.html"` + `new PendingMenu(player, PAGE).bind(b -> b.entity(npc.getId(), NpcInventory.SLOT_COUNT).player())`）、`client/npc/NpcBackpackScreenDriver.java`（只填数据）、`client/npc/NpcBackpackClientState.java`、`common/network/npc/NpcBackpack*` |

- 旧的 `npc_backpack.html`（自适应版）**已删除**，资源目录现只有 `backpack.html` / `identify.html` / `start.html` + 本说明。
- **建筑预览栏页面在 kingdom 自己的资源里**：`assets/apricityui/apricity/structures/viewer.html`（面板元素仍是 AUI 的；它是"空流 + HUD 栏"形态，版式结论与 §4.4 强相关，改它前先读那条注释）。**2026-10-11 起该页改为"贴边隐藏式双栏"**——底栏两种形态：① 默认 **8 等分分类卡**（住宅/农业/养殖/商业/工业/军事/仓库/其它，格内列出该类结构名，**点整格进该分类**）；② 进分类后是**建筑卡片**（`<structure-preview>` 3D 预览 + json 的 `name` + `[加载]`），左上角搜索框按名称过滤、`[← 返回]` 回分类态，此时底栏钉在展开态（`is-cards`）。左栏同样是两态：**索引态**是 4 等分分区（人口/建筑/队伍/道路），点人口 / 建筑后**整栏换成内容页**（搜索框 + 条形卡片；人口页带 24×24 的 `<kingdom-entity-avatar>` 头像，点一行即把 RTS 相机跳到该市民 / 该建筑）。鼠标贴到屏幕下/左边缘才展开；层级、避让与等分尺寸算法见 §11.A **坑 56 / 坑 57**，搜索框的输入通路见 **坑 58**，两态与双搜索框的焦点分发见 **坑 59**；配色与组件类**内联自 `aui经验/theme/pixel-medieval.css`**（首个"主题 → AUI"的页面，见 `aui经验/theme/AUI适配指南.md`）。改版时**删掉了页首那行 `<link rel="stylesheet" href="/apricityui/theme/ore/ore.css">`**：kingdom 资源里根本没有 `theme/` 目录（`assets/apricityui/apricity/` 下只有 `ui/` 与 `structures/`），它是死链，一直没起作用。
- **RTS 指挥面板（2026-11-07 新增）**：`assets/apricityui/apricity/ui/rts_ops.html` + `client/rts/RtsOpsPanel.java`。同一 HUD 形态：底部 `#rtsops-dock`（`position:fixed; bottom:0`，高度 = 视口 1/4 由 Java 写 inline style，`translateZ(405px)`），内部 **7 等分分组框**（宽度按视口由 Java 算，等分思路同坑 57）+ 底部四按钮（选中全部 / 坚守 / 进攻 / 清除记录；坚守=原地被动防守，进攻=进入主动索敌状态，两者都是 `NpcState.COMBAT` 下的子状态）。分组框内成员头像用 **固定列 grid**（`grid-template-columns:repeat(N,28px)`，N 由 Java 按框宽算——坑 69 的轨道列表写法）+ `<kingdom-entity-avatar>` 24×24（坑 64 配方，class 选择器不走标签选择器，D7）。**待实测项**：① `:hover` 驱动的 `.ops-btn:hover` 与既有页面同款（只改颜色，低风险）；② grid `gap` 在 HUD 页的表现（坑 19 只实测过 flex 横排 gap，grid gap 按 §13.8 属可用例外）；③ 头像 `<kingdom-entity-avatar>` 出现在 HUD 空流文档（此前只出现在 Screen 页与 viewer 左栏，形态同类，预期无差）。交互：Tab 三态循环（viewer 底左栏 → 本面板 → 全关），面板元素 id 一律 `rtsops-` 前缀并已加入 `RtsClientEvents#insideViewer` 的 AUI 命中判定（防世界点击穿透，坑 59 同源）。

  **2026-11-08 增补：分组战斗姿态常驻高亮 + 落盘。** 每个分组框带一个姿态（未下达 / 坚守 / 进攻），点「坚守」「进攻」时按「与当前选中市民有交集」的框写入，框头冒 `ops-box-state` 徽标（坚守=金底 `on-hold`、进攻=红底 `on-attack`），底部对应按钮加 `on-hold` / `on-attack` 常驻高亮；高亮跟随「焦点框」——待录入框优先，否则取与当前 RTS 选中重合最多的框（所以不点框、直接框选也亮）。分组 UUID 列表与姿态**只存在内存**（2026-11-09 改为临时指挥所语义：关面板再开还在，断开连接/换世界由 `RtsOpsPanel#reset` 整块清空，不落任何文件）。徽标显隐沿用 `start.html` `.overlay` 的同一手法（`display:none` ↔ `display:block` 由 class 切换）；若哪天徽标不显，改 `max-width` 方案即可，不必动 Java。

  **2026-11-08 二次调整（布局 / 头像 / 滚动）：**
  1. **按钮移出底框**：四颗操作按钮改成独立的 `#rtsops-foot.ops-actions`（`position:fixed; left:0; right:0; display:flex; justify-content:center; transform:translateZ(406px)`），`bottom` 由 Java 按「底框高度 + 8px」写 inline；底框 `#rtsops-dock` 里只剩标题与七个分组框。
  2. **头像不渲染的真因**：`<kingdom-entity-avatar>` 的 `skin` 为空时**不是走默认皮肤**，而是回落到 `NpcIdentifyWindow.currentTarget()`（身份卡目标）；RTS 面板没有身份卡 → 返回 null → `renderFace` 直接 return，表现为"框在、脸没有"。修法：skin 为空一律填 `NpcSkins.DEFAULT_SKIN`（`male1`）。同时给 `.ops-avatar` 与 `.ops-chip kingdom-entity-avatar` 两条尺寸规则、并在标签上写 `width="24" height="24"`（与 CSS 一致）做兜底。
  3. **头像墙支持滚轮**：`.ops-faces` 由 `overflow:hidden` 改 `overflow-y:auto`，成员不再按可视容量裁剪（单框上限 240，超出才折叠）；AUI 的滚轮默认行为会自动沿路由找可滚动祖先（`resolveScrollTarget`），无需 Java 监听。滚动条宽 7px，Java 算列数时**预留 8px**，否则最后一列会被压在滚动条下。
  4. **编组只收市民**：录入前按 `entity instanceof NpcEntity && !isTouristEntity()` 过滤，玩家假人（`RtsFakePlayerEntity`）不再混进七个框。
  5. **hover 标题改命中测试**：原来逐格 `isHover` 遍历，框上限提到 240 后是 7×240 次 DOM 查询/帧；改成一次 `document.hitTest(screenToDocumentPosition(...))` 解析 `rtsops-chip-<组>-<序>`，并加鼠标静止短路。

  **2026-11-09 增补：viewer 左栏「队伍」页改成队伍卡片（巡逻入口）。** 原来点「队伍」直接打开阵型编辑器，现在先渲染卡片列表（`StructureDebugKeybind#renderTeamEntries`）：每张卡是 `.side-item.side-team`（`flex-direction:column`）—— 标题行 `.side-team-row`（队名 + 人数）+ 按钮行 `.side-team-actions` 内两颗 `.side-btn`（`阵型` / `安排巡逻`·`解除巡逻`）。注意两点：① 队伍页的 DOM 结构与其他分区不同，`renderSideList()` 开头必须按 `isTroopPage()` 分流，否则侧栏搜索框一敲字就会被人口/建筑那套条形列表覆盖；② 卡片整块不再可点（`.side-team` 置 `cursor:default`），点击全部走按钮，避免"点卡片=进阵型"和"点按钮=巡逻"打架。巡逻进行中的按钮用 `.side-btn.on`（金底金框）常驻高亮，状态来自服务端 `RtsTeamSyncPacket.Entry.patrolling()`，卡片只在「队伍集合 / 人数 / 巡逻开关」签名变化时重建。

### 1.0 背包页与另外两页本质不同（容器 GUI）

**`backpack.html` 是"真实容器 GUI"页面**（`ApricityContainerScreen` + 真实菜单槽位）；`start.html`、`identify.html`
是**纯 DOM 页面**，差别不是"版式风格"，而是整条交互链路：

| | `backpack.html`（容器 GUI） | `start.html` / `identify.html`（纯 DOM） |
|---|---|---|
| 槽位 | 真实菜单槽位（`ApricityContainerMenu`），物品/点击/拖拽全走原版链路 | 无槽位 |
| 坐标基准 | MC **GUI 坐标**（`leftPos/topPos` + `menuSlot.x/y`）+ 文档坐标，两套并存 | 只有文档坐标；`mode=gui` 时恰好等于 GUI 坐标 |
| 点击判定 | 原生 `AbstractContainerScreen#mouseClicked/#isHovering`（会被 `aui-mouse-events=intercept` 吃掉，§11 坑 32） | 全在 AUI 文档内（`HitTest` + DOM 事件） |
| 尺寸 | 必须整稿塞进视口、**不许滚动**（滚动 → 图标与点击错位） | 可滚动，尺寸可随视口现算 |

因此下面这些规则**只对容器 GUI 页成立**：不写 `aui-mouse-events=intercept`（§11 坑 32）、一处 `flex-wrap`
都不能留（§7.6）、居中平移要额外补菜单槽位坐标与裁剪框（§10、§13.10）、版式整稿固定（§13.3）。
纯 DOM 页面按 §4.3 的"尺寸交给 Java 现算"走，规则宽松得多——**别把容器页的约束无差别套到它们身上**。

### 1.1 目录与资源约定

```
assets/apricityui/apricity/
├── ui/          ← 页面（HTML）。文件名即加载路径：ui/xxx.html
├── buildings/   ← 六个建筑面板页（farm / residential / industry / …）
├── structures/  ← RTS 建筑栏（viewer.html）
├── tex/         ← 羊皮纸/程序化贴图，HTML 里写 url('/tex/xxx.png')
├── font/        ← zpix.ttf，HTML 里 @font-face src:url('/font/zpix.ttf')
└── ...
```

**页面实际引用的贴图**（2026-10-02 清点，之前那张"五张都在用"的表已过时）：

| 页面 | 用了哪些 |
|---|---|
| `backpack.html` | `/tex/card_gen.png`、`/tex/pane_gen.png`、`/tex/slot_gen.png`（§13.2 的程序化移植产物） |
| `identify.html` | `/tex/parchment_card.png`、`/tex/parchment_panel.png`、`/tex/parchment_status.png`、`/tex/parchment_avatar.png` |
| `start.html` | 无贴图，纯 CSS + 自定义元素 |

> **构建产物同步**：NeoForge 运行时读 `kingdom/build/resources/main/...`。改完源码没走 `processResources`，
> 游戏里还是旧页面。排查"改了没反应"第一步就是比对两处是否一致（或直接 `Copy-Item` 覆盖后重启）。

---

## 2. AUI 不支持 / 高风险特性清单

| 不要用 | 原因 | 替代 |
|---|---|---|
| `<script>`、`<canvas>`、Rhino JS | JS 环境不全（`btoa` 等缺失），参考稿的程序化贴图/文字二值化跑不起来 | 贴图放 `tex/`；复杂图形写 Java 自定义元素（§8） |
| `mode=gui` 下的 `scale=fit` | 像素字体被非整数缩放重采样 → 糊 | 普通页面 1:1；**容器屏整稿**用 `mode=fixed` + 固定 `scale`（§13.3） |
| flex 容器的 `gap`（横排） | 行为不一致：竖排生效、横排实测不生效，格子粘死并被拉伸 | 间距用 `margin-right/bottom` + `flex:0 0 auto` 锁尺寸（§13.5）。**例外**：固定列 `grid` 的 `gap` 可用（§13.8） |
| `grid` 的 `fr` 分栏 | 实测 `1fr 2fr` 塌成单列通栏 | flex + 显式 px，数值取原比例的精确值（§13.4） |
| `aspect-ratio` | 完全不实现，高度失控 | 写成设计宽度下的精确 `height`（§13.4） |
| `%`、`calc()`、`vw/vh`、`min()/max()` | 支持不可靠，实测会塌；且会参与布局测量循环（§7.7） | 显式 px；需要随视口变就由 Java 按 `getViewportSize()` 算（§4.3） |
| `position:fixed` + `left:50%` + 负 margin 居中 | 不可靠（信封没居中、弹窗错位都是它） | 父级 `display:flex; justify-content:center`（§4.2） |
| `transform`、`filter`、`transition`、`@media`、`@keyframes` | 未实现或行为不确定；`transform`/`filter` 还会引入重采样 | 真实子元素 + `box-shadow` 做装饰 |
| `<img blur="true">` | AUI 唯一的平滑采样入口 | 不写该属性（默认最近邻，§5.2） |
| **容器页面上任何 `flex-wrap`** | 换行布局把"文字测量 ↔ 父级尺寸 ↔ 滚动条槽宽 ↔ 计算样式"按行数反复重跑；容器屏 init 又对每个槽位求一次位置 → 客户端彻底卡死 | 显式行 / 纵向排列；槽位阵列用固定列 `grid`（§7.6、§13.8） |
| `slot` 上写 `--aui-slot-render-bg:0` | 会把槽位背景 + 阴影 + 边框**整体**关掉（§7.2） | 不写它；要单独关用 `render`/`render-bg`/`--aui-slot-render-item`（§7.10） |
| 依赖"元素会自然显示" | `.modal{display:none}` 之后没规则打开就永远不显示（§11 坑 2） | 显式写 `.overlay.open .modal{display:block}` |
| **容器页写 `aui-mouse-events=intercept`** | 吃掉原生点击 → 所有槽位点不动（§11 坑 32） | 只有纯 DOM 交互页才写（`start.html`） |

> ⚠ **本表有 3 条与官方文档冲突**（`grid` 的 `fr`、横排 flex `gap`、`aspect-ratio`）：官方与源码都标"支持"，
> 我们的"不生效"是 🕐 历史实测 ⇒ 已降级为**待重测**（§17.1）。**当前页面的等价值写法保持不动**，但别再当结论传播。
> 另外官方明确的支持范围 / 禁用项与本表不完全重合，写页面前请并看 **§15.9** 与 **§17.2**。

---

## 3. viewport meta 与坐标系（源码依据）

源码：`common/src/main/java/com/sighs/apricityui/viewport/ApricityViewport.java`（`#resolveBase` / `#gui` / `#fixed` /
`#browserReference` / `#applyZoom` / `Spec`）。

### 3.1 模式

| `mode` | 逻辑视口 | renderScale | 备注 |
|---|---|---|---|
| `gui` / `mc` / `default` / 空 | `窗口物理尺寸 ÷ min(GUI Scale, 5)` | `min(GUI Scale,5) ÷ 实际 GUI Scale`（GUI Scale ≤ 5 时 = **1.0**） | 文档坐标 = MC GUI 坐标。`start.html` 用这个 |
| `fixed,width=W,height=H` | 恒为 W×H | 见下表 | `identify.html`/`backpack.html` 用这个 |
| `browser` / `css` / `web` | 跟随 GUI 视口 | `guiWidth ÷ 浏览器布局宽` | 高度按 `窗口高 ÷ cssScale` 反推（高 DPI 相关） |
| `window` / `native` / `screen` / `fullscreen` | 同 browser | `cssScale ÷ GUI Scale` | |

### 3.2 `mode=fixed` 的 `scale` 取值（`ApricityViewport#fixed`）

| `scale=` | renderScale |
|---|---|
| `fit` / `contain` | `min(逻辑视口宽 ÷ W, 逻辑视口高 ÷ H)` —— **注意基准是逻辑视口（`screenWidth ÷ guiScale`），不是物理窗口** |
| `window` / `native` | `1 ÷ 实际 GUI Scale` |
| `gui` / `mc` | `1.0` |
| 其它数字（如 `0.4864`） | 就用这个数字 —— ⚠ **绝对倍率**，见下方警告 |
| 缺省 | `1.0` —— ⚠ **绝对倍率** |

`scissorScale = 实际 GUI Scale × renderScale`（§13.10 的裁剪换算要用到）。

> ⚠ **实践结论（2026-10-09）**：`scale` 只有 **`fit`** 是"相对倍率"（每帧按当前视口重算），
> 其余取值（数字 / `gui` / `mc` / 缺省）都是**绝对倍率**——文档尺寸与窗口无关。
> 定稿页面写绝对倍率会**同时**导致"窗口缩放时页面不跟着缩"和"居中偏移失义"
> （文档甚至可能比视口还大、被硬推出视口裁掉）。**定稿页一律用 `fit`**，详见 §11.A 坑 50。
> `scale=1` 只适用于尺寸由宿主另定的页面（如 `WorldWindow` 里的 `identify.html`）。

### 3.3 缩放（zoom）

- 选项：`zoom / min-zoom / max-zoom / zoom-step / user-scalable`，缺省 `zoom=1、min-zoom=0.5、max-zoom=3、step=0.1、user-scalable=true`。
- 最终值 `clamp(sanitizeZoom(zoom, initialZoom), minZoom, maxZoom)`；写 `zoom=1,min-zoom=1,max-zoom=1` 可把页面**钉死在 1 倍**，
  连历史存下来的缩放值也会被夹回 1。
- **zoom 语义（`#applyZoom`）**：`layout = base ÷ zoom`（**四舍五入成整数**）、`renderScale = base × zoom`、`scissorScale = base × zoom`。
  即"定稿内容按 zoom 放大、视口按 1/zoom 收缩"，超出部分居中溢出、没有文档级裁剪。
- **AUI 会按页面路径记住缩放值**（`ApricityViewport#readStoredZoom/writeStoredZoom/zoomStorePath`，落在 config 目录）。
  所以"像素怎么发虚了"先怀疑历史缩放，`Ctrl+0` 或写死 `min-zoom=max-zoom=1` 复位。

### 3.4 GUI Scale → 逻辑视口对照（以 1086×568 窗口为例）

| GUI Scale | 逻辑视口 | 1080×810 的网页稿能放下吗 |
|---|---|---|
| 1 | 1086×568 | 宽勉强、**高差 242px → 放不下** |
| 2（该玩家实际设置） | **543×284** | 完全放不下（所以走整稿缩放方案，§13.3） |
| 3 | 362×189 | 差更多 |

> 查玩家设置：`.minecraft/versions/<实例>/options.txt` 里的 `guiScale:`。

---

## 4. 布局与尺寸

### 4.1 为什么普通页面不能写死尺寸

网页稿的设计尺寸是"浏览器尺寸"（1080×810），普通屏页面拿到的却是逻辑视口（常见 543×284），
差 1.9～3 倍，写死必然"每个部分都被放大 / 只剩一角 / 多出滚动条"。
**容器屏整稿页面是另一回事**：固定设计尺寸 + 固定倍率是有意为之（§13.3），但它必须能整稿装进视口。

### 4.2 用 flex + 显式 px

`grid` 被 `normalizeDisplay` 保留、`layout/Grid.java` 也实现了 `fr/minmax/repeat`，但**实测分栏比例与预期不符**
（1:2 变成约 1.26:1，左右列反过来）。结论：**分栏用 flex + 显式 px**，别依赖 `fr` 的分配结果。
（**槽位阵列是例外**：固定列数 `grid` 是 AUI 自己 `preset:player` 与 `ContainerExpander` 用的机制，见 §13.8。）

- 两列：`display:flex` + `.left{width:229px;flex-shrink:0}` + `.right{width:459px}`
- 槽位阵列：显式行（`<div class="slot-row">` 内逐个 `<slot>`）或固定列 `grid`——**都不要 `flex-wrap`**（§7.6）

### 4.3 普通屏页面：尺寸交给 Java 现算

先算格子、卡片随格子走（原稿的比例关系是"格子撑满面板"，卡片宽度从格子宽度长出来）：

```java
Size viewport = document.getViewportSize();          // 逻辑视口
double availH = viewport.height() - 12 - 6;          // 留边 + 面板间缝
double unit = Math.floor((availH - fixedChrome) / 7);
double bagSlot = Math.max(16, Math.min(34, unit + 4));
double playerSlot = Math.max(16, Math.min(38, unit));
// 卡宽/卡高同理由格子反推，全部取整数 px
```

要点：
- 全部取**整数** px（避免半像素重采样）；
- **绝不单独收一边**：只收格子会让它缩在宽面板一角；只压卡片会裁掉档案区下半（两个方向都实测翻车）；
- 打开瞬间就要套用一次，否则第一帧画默认尺寸、下一帧跳变：在 **`ScreenEvent.Init.Post`** 里判断并立刻 apply（§9.3）；
- 日志里打一行诊断（`viewport / card / slot / bio`），出问题时有硬数据可查。

> `backpack.html` **不用**这套：它是整稿固定版式（§13）。这段代码保留给普通屏页面与新页面参考。

### 4.4 滚动：由 `overflow-y` 决定

`Interaction#allowsUserScroll(resolveOverflowY(style))`（`common/.../style/Interaction.java`，另有
`allowsUserScrollX/Y`、`resolveOverflowY`、`clipsOverflow`）——**是否允许用户滚动取决于 `overflow-y`**。

- **根元素上的 `overflow` 是"条件雷"，不是无条件禁令**：`html,body{overflow:hidden}` 是浏览器里关滚动条的惯例，
  在 AUI 里**只有"空流文档"（根盒子高度为 0）才会炸**。触发链（2026-10-02 逐环节核过，全部仍在源码里）：
  1. `render/Drawer.java` 对 `Interaction#clipsOverflow(style)` 为真（任何非 `visible` 取值）的元素生成
     `RenderNode.MaskPushNode`；
  2. `Mask#pushMask` 执行 `currentClip = currentClip.intersection(new AABB(x, y, w, h))`（SCISSOR 与 stencil/NONE
     两个分支都做，见 L212 / L224-225）；
  3. 若该元素盒子高度为 0（流内没有任何有尺寸内容，典型是全 `position:fixed` 的 HUD/overlay：实测根盒子 474×0），
     交集不满足 `AABB#isValid()`（`width > 0 && height > 0`）→ `Mask.currentClip` 失效；
  4. `RenderNode.ElementPhaseNode` / `ElementBackgroundNode` 等 5 处入口的
     `if (!currentClip.isValid() || !rect…intersects(currentClip)) return;`（L155/201/218/241/396）把整页每个节点
     直接跳过 → **一个像素都不画，零日志零报错**。
  最小复现：工程根目录 `aui-hud-ok.html` / `aui-hud-broken.html`（逐字节相同，只差根元素这条 `overflow:hidden`）；
  同源证据见 `structures/viewer.html` 的 CSS 注释（建筑预览栏 = 整页全 `fixed` 的空流页面）。
- **反例（解释"为什么有时又没事"）**：`backpack.html` 的 `html,body{overflow-x:hidden;overflow-y:hidden}` 一直在跑、
  渲染正常——因为它 `body` 有 padding、流内有大量块级内容 ⇒ **根盒子非零** ⇒ 遮罩合法；
  `identify.html`（`html,body{height:100%}`）、`start.html`（`width:100%;min-height:100%`）都给了根元素显式高度。
  ⇒ 写成规则：**要禁滚动就别在根元素上写 `overflow`；一定要写就必须保证根盒子非零（给它确定高度）；
  空流文档（HUD / overlay / 建筑预览栏）一律不写**，需要裁剪就写在自己有确定尺寸的容器上。
- **`width/height:100%` 是另一码事**：百分比尺寸真正的代价是参与"父尺寸 ↔ 文本测量"循环（§7.7），
  与"整页不渲染"无关（`identify.html` / `start.html` 就是这么写的，页面正常）。
- 要靠 `translateZ` 盖过原版聊天栏时，**挂到有确定尺寸的容器上**（如底边栏），不要挂到 `body`。
  根因：原版 HUD 全程开着深度测试，聊天先画先写深度，而 AUI 文档面只抬到 z≈1，同深度后画的像素过不了深度测试；
  400（原版 tooltip 同层）实测能稳压聊天。已应用：`structures/viewer.html` 的 `.viewer-bar` / `.building-tip`
  都带 `transform:translateZ(400px)`（2026-10-03）。
- 禁掉整页滚轮滚动：**什么都不用写**（根节点没有 overflow 就不会滚）。
- 想局部可滚：只在那一块写 `overflow-y:auto`（该元素必须有确定尺寸）。
- 注意别在注释里出现 `--aui-...` 之类的自定义属性名，解析器不剥注释时会误读。
- **容器屏页面禁止纵向滚动**：原版物品图标/点击热区画在屏幕坐标（`leftPos + menuSlot.x/y`），
  不随文档滚动——页面一滚就"图标错位、点击失效"（实测）。版式必须整套塞进视口（§4.3、§13.3）。

---

## 5. 质感与像素化（羊皮纸贴图 + 硬边描边）

### 5.1 贴图与配方

**参考稿的颗粒质感来自 JS 程序化贴图，AUI 跑不了**。两条路：`identify.html` 用 `tex/parchment_*.png` 四张；
`backpack.html` 用 §13.2 逐行移植生成的三张 `*_gen.png`。

配方（照抄 `identify.html`）：

```css
.pane{
  background-image:url('/tex/parchment_panel.png');
  background-size:100% 100%;
  background-repeat:no-repeat;
  background-color:#c8ac7c;              /* 贴图失败时的兜底色 */
  border:2px solid #4a3218;              /* 外框 */
  box-shadow:
    0 0 0 1px #6a4a20,                   /* 外描边 */
    inset 0 0 0 1px #8a6e3c,             /* 内描边 */
    inset 2px 2px 0 0 #d8bc8c,           /* 内高光（左上） */
    inset -2px -2px 0 0 #6a4a20;         /* 内暗边（右下） */
  overflow:hidden;                       /* 裁掉边框外的溢出背景 */
}
```

- **`overflow:hidden` 是必须的**：否则元素背景/贴图会在边框外多出一圈。
- 纯色 / `repeating-linear-gradient` 只能做近似，做不出参考稿的颗粒与磨损边缘。

### 5.2 像素化方案：AUI 的采样事实 + 本项目的做法

**AUI 侧查实的事实**：所有图片都经 `ImageDrawer` 提交（`common/.../render/ImageDrawer.java`），
**背景图、`<img>`、九宫格三条路径都传 `blur=false`**：背景走 `drawComplexBackground/drawTiledPart` 内的 `innerBlit(..., false, true)`；
`<img>` 走 `draw(...)` 时用 `"true".equals(element.getAttribute("blur"))` 判定（默认 false）；九宫格 `drawNineSlice` 同样 false。
`blur=false` 一路传到平台层 `new TextureStateShard(location, blur, false)`
（`targets/neoforge-1.21.1/.../render/`，`RenderService`/`SmoothRenderType` 一侧）。

**本项目的像素化做法（六条，按重要性排序）**：

1. **尺寸整数 + 缩放 1:1（或整稿一个固定倍率）**：像素化的地基，非整数缩放会让下面五条全失效；
2. **贴图直接当背景铺**：`background-size:100% 100%` + `background-repeat:no-repeat`。
   拉伸不糊（最近邻），但**会改变像素格大小、非等比拉伸会把方像素拉成长方** → 贴图按目标显示尺寸设计或用整数倍；
   想保持原始像素密度写 `background-size:auto`（`ImageDrawer#resolveRenderSize` 里 `auto` = 贴图原始宽高）；
3. **硬边靠 `border` + 多层 `box-shadow`**（§5.1）：像素描边，不用渐变、不用模糊阴影；
4. **字形先验**：符号没字形就是空白（§6）；
5. **自定义元素里画像素图**：Java2D + `VALUE_INTERPOLATION_NEAREST_NEIGHBOR`（§8.4），印章/盾徽/血条/头像走这条路；
6. **禁用会引入重采样的东西**：`<img blur="true">`、`transform`、`filter`、非整数 `%` 尺寸。

> 一句话：**糊不糊 = 采样方式 × 是否整数缩放**。AUI 已把最近邻做成默认值，
> 要做的只是"别把缩放搞成非整数、别开 `blur`、别用会重采样的 `transform/filter`"。

---

## 6. 字体与字形（最容易"整块空白"的地方）

页面字体是 `font/zpix.ttf`（Zpix，GBK 系像素字）。**只覆盖拉丁/GBK 与部分几何图形区，Dingbats、Misc Symbols 基本没有**。

### 6.1 实测方法（不编译、不启动游戏）

```powershell
Add-Type -AssemblyName PresentationCore
$p = '...\assets\apricityui\apricity\font\zpix.ttf'
$map = ([Windows.Media.GlyphTypeface]::new([System.Uri]::new($p))).CharacterToGlyphMap
$map.ContainsKey(0x2720)     # False 表示没有该字形 → 游戏里是空白/豆腐块
```

### 6.2 本项目实测结论

原选的 16 个纹章符号里**只有 2 个有字形**（`★` U+2605、`†` U+2020），
`✠ ✦ ⚜ ✚ ✝ ☩ ❖ ✧ ♆ ☘ ⚔ ⚑ ♔ ♕` 全部为 False；装饰符 `❦ ❖ ✦`、关闭按钮 `✕` 同样没有。

已替换为实测存在的安全符号：

```
★ ☆ ◆ ◇ ● ○ ■ □ ▲ △ † ※ ♥ ◎ 〓 ⊕      （纹章 16 个）
×                                        （关闭/删除）
※ ♥ ★                                    （信封与信纸装饰）
```

> 规则：**任何新符号、装饰字符、图标先用上面那段实测一遍**，`ContainsKey` 为 False 的一律不要用。
> 真正的纹章母题（十字/鸢尾/狮子/锚）字体这条路走不通 —— 只能写 Java 自定义元素用 Java2D 画（§8）。

---

## 7. 槽位与容器（背包类页面的核心）

### 7.1 HTML 侧两种写法

```html
<!-- A. 自动铺：容器空着，AUI 按 size 自动生成槽位并绑定 -->
<container id="entity" bind="entity" size="24" primary="true"></container>

<!-- B. 显式写：每个槽位自己写 slot-index（本项目两处都用这种，原因见下） -->
<container id="entity" bind="entity" size="24" primary="true">
  <slot id="slot0" class="slot" slot-index="0"></slot>
  <!-- ... -->
</container>
```

- 两种都合法，但**推荐 B**：A 在浏览器里预览是一片空白（槽位只有进游戏才生成），排查样式时看不到格子；
  B 在浏览器里也能看到格子。**一旦手写了一个 slot，就不会再自动补齐**，所以要写满。
- **手写 slot 的硬性要求**（官方 `docs/guide/container.md`）：`slot-index` 正确 + 是 `<container>` 的**直接子元素**。
  代码侧 `SlotDataBinder#bindSlotsFromDocument` 与 `ContainerExpander#collectOwnedSlots` 走的是 `findAncestor(Container.class)`，
  嵌套在普通 div 里也能绑上（当前 `#entity` 就是嵌在 `.slot-row` 里，实测正常）；但既然官方把"直接子元素"写成硬要求，
  **新页面按直接子元素写**（`#player` 已按此改造，§13.8）。
- 槽位坐标是**逻辑坐标**，框架会按 viewport 缩放同步给菜单——不要在脚本/Java 里手动乘缩放系数。

### 7.2 千万不要写 `--aui-slot-render-bg:0`

`Slot#shouldRenderBackground` 的解析顺序是 **CSS `--aui-slot-render-bg` → HTML `render-bg` → HTML `render`**：

```java
public boolean shouldRenderBackground() {
    Boolean cssFlag = parseBooleanLike(getCustomPropertyInherit("--aui-slot-render-bg"));
    if (cssFlag != null) return cssFlag;        // 写了 0 → 直接 false
    ...
}
public void drawPhase(...) { if (!shouldRenderBackground() && (SHADOW | BODY | BORDER)) return; } // 边框/阴影也跳过
```

也就是说：**它为 false 时，背景、`box-shadow`、`border` 三个阶段会一起被跳过 → 槽位彻底空白**。
正解：不写这个属性，槽位背景用自身 CSS 画（浅色内部 + 像素描边）：

```css
.slot{
  display:block; width:74px; height:74px;
  background-image:url('/tex/slot_gen.png');
  background-size:100% 100%;
  background-repeat:no-repeat;
  --aui-slot-size:74;          /* 见 §11 坑 28，不写就是 18 */
}
```

### 7.3 服务端绑定（AUI 已内置整条链路）

槽位点击取放、拖拽、shift-click、槽位位置同步**全都不用自己写**，只要：

```java
// common/npc/NpcBackpackMenu.java 的实际写法
new PendingMenu(player, "ui/backpack.html").bind(binding -> binding
        .entity(npc.getId(), NpcInventory.SLOT_COUNT)   // 实体物品能力，id 与页面 <container id="entity"> 一致
        .player());                                     // 玩家背包，页面里是 <container id="player">
```

- **容器 id 必须与页面里的 `id` 同名**（`entity` / `player`；`bind(...)` 的类型决定的是数据源类型，
  对应关系见官方文档的固定 id 表）。
- **绑定顺序决定 primary**：先绑的成为主容器 → **shift-click 会把物品优先送进它**。
  行装页先绑 entity，所以 shift-click 是"从玩家背包塞进 NPC 行装"。
- 实体数据源的有效性 = `EntityDataSource#stillValid`（`player.distanceToSqr(entity) <= 64.0`，即** 8 格内**），
  由 `ApricityContainerMenu#stillValid` 调它判定 ⇒ 走远自动关界面（方块实体版是 `BlockEntityDataSource#stillValid`，同样是 64 ⇒ 8 格）。
- 只发"打开界面"的包**不够**：那样槽位没有真实数据源，点不动也拿不出东西。

### 7.4 物品能力必须注册

AUI 的实体数据源按服务端实体 id 取 `Capabilities.ItemHandler.ENTITY`：

```java
@SubscribeEvent
public static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
    event.registerEntity(Capabilities.ItemHandler.ENTITY, ModEntities.NPC.get(),
            (npc, context) -> npc.getNpcInventory());
}
```

没注册 → 界面能打开，但槽位是摆设。

### 7.5 数据持久化（本项目选择）

市民实体是 shell（重建时不带 NBT），所以 24 格行装**序列化成 SNBT 存进 `NpcData.inventory` 列**，
由 `NpcManager#flushDirty` 批量落库；改动时 `markDirty`，不要只依赖实体 NBT。

---

### 7.6 槽位样式：不要用 `slot` 标签选择器（灰皮肤来源）+ 绝对不要用 `flex-wrap`

**坑 A：`#entity slot{...}` 这种标签选择器的颜色不生效。**
槽位上能看到"原版风格的灰色层"——先别急着骂原版：AUI 的 `mixin/AbstractContainerScreenMixin#apricityui$cancelVanillaRenderSlot`
（`at = renderSlot HEAD, cancellable`）已经把原版 `renderSlot` 对 `ApricityContainerScreen` 整个 cancel 掉了。
那层灰是 **AUI `Slot` 元素自己的默认皮肤**：我们的颜色写在 `#entity slot{...}` 标签选择器里，
AUI 对 `slot` 这种自定义标签的标签选择器匹配不可靠（同页 `.slot-row` 的 class 选择器就正常生效）。

修法：给槽位显式加 class（本项目用 `class="slot"` / `class="slot-icon"`），颜色规则写 `.slot{...}`；
最稳的兜底：把关键数值直接写进 driver 的 inline style（inline 优先级最高，已被证实生效）。

**坑 B：槽位容器绝对不要用 `flex-wrap`（会把客户端卡死在布局里）。**

**这是本项目实际踩过的致命坑**：行装页原本让 `#entity`（24 格）与 `#player`（36 格）用
`display:flex; flex-wrap:wrap` 自动换行，结果一右键 NPC，客户端直接卡死到连日志都没有。

看门狗报告（`logs/kingdom-watchdog/`）里的栈底就是入口（方法名保留，行号已失效，别按行号找）：

```
Position.of(slotElement)                      ← SlotDataBinder#syncAllSlotPositions（每个槽位调一次）
 → Flex.computeChildPosition → Flex.getOrComputeLayout
 → Flex.computeWrappedRowLayout               ← 只有 flex-wrap 容器走这条路
 → Flex.buildWrappedRowLines → Flex.closeWrappedRowLine
 → Flex.computeWrappedRowContentSize → Flex.computeContentSize
 → Size.natural ↔ Flex.participantSize ↔ Flex.computeContentSize 相互递归（栈里重复成百上千层）
 → 顶端停在 Flex.computeBaselineMetrics
```

判读要点：**卡死发生在 `ApricityContainerScreen.init()` 同步槽位坐标的阶段**——不是绘制、也不是自定义元素
（报告里"当前所在阶段"为空正好印证）。`SlotDataBinder#syncAllSlotPositions` 会对**每一个**绑定槽位求一次位置

```java
// SlotDataBinder#syncAllSlotPositions（写法已按源码核对：先算坐标，再判脏写回）
for (SlotBinding binding : bindingsByGlobalIndex.values()) {
    ...
    Position position = Position.of(slotElement);                     // 24 + 36 = 60 次
    int elementX = (int) Math.round(position.x * viewportScaleX) - leftPos;
    int elementY = (int) Math.round(position.y * viewportScaleY) - topPos;
    if (force || menuSlot.x != elementX || menuSlot.y != elementY) { menuSlot.x = elementX; menuSlot.y = elementY; }
    ...
}
```

flex-wrap 容器每次求位置都会重跑"换行布局"那条深递归 → 60 次相乘 → 渲染线程纯自旋。
（看门狗第 4 份报告：15 秒里渲染线程 CPU 涨了 14.97s，心跳增量 0 = 彻底停住。）

**正确做法：槽位按"显式行"或"固定列 grid"排。**

```html
<container id="entity" bind="entity" size="24" primary="true">
  <div class="slot-row"><slot class="slot" slot-index="0"></slot>…共 8 个…</div>
  <div class="slot-row">…</div>
  <div class="slot-row">…</div>
</container>
```
```css
#entity{ display:flex; flex-direction:column; }   /* 容器纵向排"行"，不换行 */
#entity .slot-row{ display:flex; }                /* 每行 8 个，间距用 margin */
```

- 每行几个由**行里放几个槽位**决定，不再靠"容器宽度刚好放得下 8 个"来间接换行；
- 行/格尺寸由 Java 按视口算（普通屏）或写死在设计坐标系里（整稿，§13）；
- `flex-direction:column` + `gap` 在本项目已多处使用，可用（横排 `gap` 不安全，§13.5）。

> **推论（第二次实测修正，很重要）**：**容器页面上任何一处 `flex-wrap` 都不能留**，与它是不是槽位容器无关。
> 第一版只改了槽位容器，jar 里已确认 `slot-row` 铺开、`#entity/#player` 不再换行，**结果照样卡死**——
> 第二份报告的栈换成了"换行容器 + 文字"那条链：
>
> ```
> Flex.buildWrappedRowLines → Text.of → Size.getTextSize → Text.resolveTextIndent
>  → Size.getScaleWidth → Box.innerSize → ScrollModel.getHorizontalScrollbarGutter
>  → Element.getComputedStyle → …再回到 Flex.buildParticipants…
> ```
>
> 元凶是身份档案的 `.bio-grid`（8 个含文字的项）。也就是说：**`flex-wrap` 会把
> "文字测量 ↔ 父级内容尺寸 ↔ 滚动条槽宽 ↔ 计算样式"这条链按行数反复重跑**，槽位容器和文字容器是同一类放大器。
> 另外：安全模式救不了这种卡死——它在布局阶段，还没轮到元素绘制。

### 7.7 即使没有 `flex-wrap`，"测量 ↔ 字号 ↔ 样式"这条循环也会卡死

第三份报告（仅去掉所有 `flex-wrap` 之后）的栈底揭示了**另一类更隐蔽的循环**：

```
Flex.computeBaselineMetrics → Flex.baselineFromCrossStart
 → Text.of → Text.resolveFontSize → Text.getDeclaredFontSize
 → Element.getInlineStylePropertyValue → Style.get → ...又回到布局
```

页面里没有 wrap 容器了，但有 `.bio-item{display:flex; align-items:baseline}`（基线对齐）+
`.bio-k{text-indent:.18em}`（相对单位文本缩进）+ `.bio-desc{flex:1}`（等价 `flex:1 1 0%`，`0%` 触发父尺寸解析）。
**这三种特性单独哪一个都会让"布局 → 文本/字号 → 内联样式 → 父尺寸"循环**，被 60 次 `Position.of` 放大就死。

**AUI 1.2.5 的特征**：layout ↔ text ↔ style 这条链**总是存在**（每个文本节点都要字号才能算行高），
问题只是"被放大"。任何让放大倍数变大的东西都是危险源。

**容器页面上的硬性回避清单**（已实测会卡死）：

| 特性 | 为什么触发循环 | 替代 |
|---|---|---|
| `align-items: baseline` / `align-self: baseline` | Flex 要算每项的文本基线 → `Text.of` → 字号 → 内联样式 | 不要 baseline 对齐；要左右排版用 `inline-block` |
| `text-indent: .em` / `%` / 其他相对单位 | 文字测量要解算缩进 → 字号 → 父尺寸 | 不用 text-indent；要缩进用绝对 `px` |
| `letter-spacing: .em` | 同上，字间距要字号 | 用绝对 `px` |
| `flex: 1` / `flex-basis: 0%` / 任何带 `0%` 的 flex 简写 | `0%` 要解析父主轴尺寸 → 父尺寸 → 父的文本测量 | 改用 `flex: 1 1 auto` 配 `min-height:0`，或干脆不写 `flex` |
| 任何带 `em`/`%`/`calc()` 的尺寸属性（字号、缩进、字间距、padding、margin） | `em`/`%` 都要父/字号上下文 | 容器页面上只用整数 `px` |

> 一句话：**AUI 1.2.5 在容器页面（被 `Position.of` 放大 60×）上，"任何要回看父/字号/样式的特性"都不安全**。
> 其它页面（`identify.html`、`start.html`）没被放大，宽松一点；但仍建议照同样的清单写，把未来改成容器屏时的隐患提前排掉。

### 7.8 根因：容器屏 init 阶段布局缓存根本没开（AUI 1.2.5，已打补丁）

§7.6/§7.7 里每一轮"修好一个又换一个入口卡死"，真正的原因在 AUI 侧：

```java
// common/.../layout/LayoutMeasureCache.java —— 所有读写的闸门
public static Size getSize(...)   { if (state == null || state.depth <= 0 || element == null) return null; }  // 永远 miss
public static void putSize(...)   { if (state == null || state.depth <= 0 || ...) return; }                    // 写了也白写
public static Object getObject(...)/putObject(...)  // 同样两道闸门
```

`depth > 0` 只在 `LayoutMeasureCache#begin/#end` 之间成立，而这对调用只发生在**文档绘制**里。
`SlotDataBinder#syncAllSlotPositions` 却是在 **`Screen.init()`** 阶段执行的——文档一帧没画过，
缓存上下文不存在 → **60 个槽位逐个 `Position.of`，每一次都是"无缓存全树测量"，且结果直接丢弃**。
哪条路径最贵，栈就停在哪：先后出现过 flex-wrap 换行、基线对齐、`inline-block` 原子内联、
滚动条槽宽（`getVerticalScrollbarGutter → isScrollbarHidden → normalizeScrollbarWidth`）。
页面元素越多（189 个）、嵌套越深，60 次裸算越慢——这就是"别人的容器页没事、这页必卡"的原因。

**修复（kingdom 侧 Mixin，AUI 源码零改动）**：
- `AuiSlotSyncMeasureCacheMixin`：给 `syncAllSlotPositions` 包一层 `begin()/end()`（init 路径）；
- `AuiMeasureCacheGateMixin`：**布局缓存闸门常开**——第一次 `begin()` 后不再允许 `end()` 把 depth 减回 0。
  这一条覆盖了所有绘制之外的求值路径：除了 init 的逐槽位求位，还有 **鼠标悬停/拖拽命中**
  （`AbstractContainerScreenMixin#apricityui$injectSlotHovering` 注入 `isHovering` → `isBoundElementHovered`
  → 每个绑定槽位一次 `Position.of`——2026-10-01 00:42 的报告证实：开了 16/17 号 NPC 都正常，
  重进世界后开 13 号卡死，栈底是 `mouseDragged → findSlot → Position.of`；"有的 NPC 卡有的不卡"
  取决于开完后鼠标是否在界面上移动，与 NPC 本身无关）。
  闸门常开的安全性由缓存键保证（`Key` 里带 `element.getRenderer().layoutDependency()` 版本号，脏了自动失效；
  `BoundedCache` 上限 4096 条；`State` 是 `ThreadLocal`，无并发复用）。

两者都已注册进 `mixins.xy2407_kingdom.json`。只需重建 kingdom；**AUI 源码与 jar 一律不动**。

**页面侧配合项**：根节点不要写 `html,body{width:100%;height:100%}`——百分比尺寸会参与
"父尺寸↔文本测量"循环（§7.7）；**也不要给根节点写任何 `overflow`**（会让整页不渲染，§4.4）；禁滚动什么都不用写。

### 7.9 交互能力优先级（**以源码为准，与官方文档相反**）

`Slot#resolveInteractionCapabilities`（`element/Slot.java`）的真实顺序是：

| 优先级 | 来源 | 说明 |
|---|---|---|
| 1（最高） | recipe 生成 | `isRecipeSlot()` 直接返回空集 → 永不可交互 |
| 2 | HTML `interactive="..."` | 命中即返回 |
| 3 | HTML `pointer="..."` | 命中即返回 |
| 4 | CSS `--aui-slot-interactive` | 命中即返回（UA 在 body 上默认 `1` = `TOOLTIP+SLOT`） |
| 5 | 绑定默认 | 已绑定 → `{TOOLTIP, SLOT}`；未绑定 → `{TOOLTIP}` |

> ⚠ 官方 `docs/guide/container.md` 把 CSS 变量排在 HTML 属性**之前**，与本源码不一致。
> **以源码为准**：`interactive`/`pointer` 属性可以覆盖 CSS 变量；反过来不行。
> `recipe` 的非交互是 UA 样式 `recipe > .aui-recipe-slot{--aui-slot-interactive:0}` + `isRecipeSlot()` 双保险。

判定链：`canOperateBoundMenuSlot() = bound && !isDisabled() && caps.contains(SLOT)`；
其中 `isDisabled() = boundDisabled || isExplicitlyDisabled()`。**"槽位看得见点不动"七成先查这里**（§11 坑 32 是另一类）。

### 7.10 槽位"渲染开关"优先级（与交互能力不同，CSS 在前）

`Slot#shouldRenderBackground` / `#shouldRenderItem` 的顺序是 **CSS 变量 → HTML 属性**：

| 想控什么 | CSS | HTML | `render="…"` 的影响 |
|---|---|---|---|
| 背景/阴影/边框 | `--aui-slot-render-bg` | `render-bg` | `render="item"`/`"none"` → 背景关 |
| 物品 | `--aui-slot-render-item` | `render-item` | `render="bg"`/`"none"` → 物品关 |

其它槽位级开关：`--aui-slot-z` / `zIndex`；`--aui-slot-icon-scale`（§7.12）；`--aui-slot-cycle` /
`--aui-slot-cycle-interval`（展示槽位轮播，默认 1000ms、最小 200ms，悬停暂停）。
UA 在 `body` 上给出的全套默认值（`global.css`）：`--aui-slot-size:18`、`--aui-slot-render-bg:1`、
`--aui-slot-render-item:1`、`--aui-slot-icon-scale:1`、`--aui-slot-z:0`、`--aui-slot-interactive:1`、
`--aui-slot-cycle:1`、`--aui-slot-cycle-interval:1000`。

### 7.11 槽位尺寸：`--aui-slot-size` 是第一优先级

`Slot#resolveSlotSizeHint(fallback)` 的顺序：

1. **CSS `--aui-slot-size`**（无单位整数）；
2. `max(computed width, computed height)`（注意是**两者取大**）；
3. HTML `size` / `slot-size`；
4. fallback（命中判定传 16，渲染传 16）。

UA 给所有槽位默认 **18**（原版槽位尺寸），所以**不显式覆盖时，热区 = 18px**——
本项目 74px 的格子会出现"点格子中心没反应"（§11 坑 28）。覆盖写法：`.slot{--aui-slot-size:74}`。

### 7.12 图标尺寸与浮动物品（两个必须配套的坑）

- **槽位内图标**：UA 把 `item` 定死 `16×16`、`slot > item{position:absolute;left:1px;top:1px}`；
  AUI 的居中拿的是 **`<item>` 元素自己的矩形**（`RenderNode.ItemNode` 的 `centeredBodyX/centeredBodyY`
  → `Rect.of(item)`，再按固定 16px 居中），**不是槽位矩形**。所以要么把 `<item>` 撑成槽位大小 + 用
  `--aui-slot-icon-scale` 放大，否则图标永远缩在格子左上角（§11 坑 31）。
- **跟随光标的浮动物品**：`ApricityContainerScreen#captureFloatingItem` 里写死
  `RenderNode.ItemNode.positioned(..., 1.0D, 232, ...)`，与槽位图标不同倍，需要 kingdom 侧 Mixin 补偿（§11 坑 33）。

---

## 8. 自定义元素（AUI 没有的能力就写它）

### 8.1 两种基类

| 基类 | 适合 | 机制 |
|---|---|---|
| `Element` | 要用 MC 渲染管线（实体模型、方块预览、物品） | 重写 `drawPhase(PoseStack, Base.RenderPhase)`，三阶段 `SHADOW / BODY / BORDER` |
| `Canvas` | 程序化像素图（印章、盾徽、头像） | `renderOperation(g -> ...)` 用 Java2D 画到表面，再 `ImageDrawer.draw(...)` 贴出 |

两者都靠注解注册：`@ElementRegister("KINGDOM-XXX")`，并确保该包在 `scanPackage(...)` 扫描范围内。
标签名在 HTML 里小写写即可（`<kingdom-seal>` 对应 `KINGDOM-SEAL`）。

### 8.2 本项目现有元素（`client/aui/`）

| 标签 | 基类 | 作用 | 文件 |
|---|---|---|---|
| `kingdom-seal` | Canvas | 火漆印章 | `KingdomSealElement` |
| `kingdom-shield` | Canvas | 阵营盾徽（`c1`/`c2` 双色） | `KingdomShieldElement` |
| `kingdom-npc-model` | Element | GUI 内渲染 3D 实体模型 | `KingdomNpcModelElement` |
| `kingdom-entity-avatar` | Canvas | 皮肤上的 2D 脸（头像） | `KingdomEntityAvatarElement` |
| `npc-bar` | Element | 像素格子进度条（`value`/`max`/`type=hp\|hunger`） | `NpcBarElement` |
| `npc-triangle` | Element | 属性三角 | `NpcTriangleElement` |
| `structure-preview` | Element | 结构预览 | `StructurePreviewElement` |

辅助/宿主：`AuiDocPlacement`（居中偏移唯一来源）、`KingdomAuiIntegration`、`KingdomAuiStencil`、`SpanSheet`、`NpcIdentifyWindow`。

### 8.3 在 GUI 里渲染 3D 实体（完整 recipe）

```java
poseStack.pushPose();
Base.commitDraws();                                  // 先冲掉 AUI 共享缓冲，否则整页 UI 会一起消失
Base.pushDepthTest(true);                            // 用 AUI 的状态栈，退出时自动还原
RenderSystem.enableDepthTest();
try {
    poseStack.translate(centerX, centerY, 50.0F);    // 原版 GUI 内实体渲染的固定套路
    poseStack.scale(scale, scale, -scale);
    poseStack.mulPose(new Quaternionf().rotateZ((float) Math.PI));
    Lighting.setupForEntityInInventory();            // com.mojang.blaze3d.platform.Lighting（不是 client.renderer！）
    MultiBufferSource.BufferSource buffers =
            MultiBufferSource.immediate(new ByteBufferBuilder(1536));   // 关键：独立即时缓冲
    minecraft.getEntityRenderDispatcher().render(entity, 0, 0, 0, yaw, 1.0F, poseStack, buffers,
            LightTexture.FULL_BRIGHT);
    buffers.endBatch();
} finally {
    Lighting.setupForFlatItems();
    Base.popDepthTest();
    poseStack.popPose();
}
```

要点与坑：

- **用独立即时缓冲**（`MultiBufferSource.immediate(...)`），而不是关卡共享的
  `minecraft.renderBuffers().bufferSource()`：后者会把几何混进关卡缓冲的提交时机，属于高风险写法
  （`StructurePreviewElement` 目前仍在用它，且建筑渲染正常）。
  **注意**：日志里成片刷的 `GL_INVALID_OPERATION: Depth formats do not match` 是 **AUI 自身的既有问题**，
  已确认与本模组的实体渲染、建筑渲染无关——不要拿它当线索去改自己的渲染代码。
- **缩放要留余量**：`scale = 框高 × 0.74 ÷ 1.8`，脚底位置 `y + 框高 × 0.94`。
  早先用 `×1.0` + `×0.88` 的效果是**头顶被裁在框外**（截图里"没有头的模型"）。
- **只想画头**：把模型其它部件 `visible=false`（`HumanoidModel.head/hat` 是 public；`PlayerModel.cloak/ear` 是 **private**）。
- **模型坐标系**：`ModelPart.translateAndRotate` 用 `/16` 把 px 换算成方块；`LivingEntityRenderer` 内部是
  `scale(-1,-1,1)` → `scale(entity)` → `translate(0,-1.501,0)`。**别背符号**，要精确定位就用一个同链路的探针 `PoseStack`
  算出目标部件中心落在哪，再补偏移。

### 8.4 画皮肤上的 2D 脸

原版皮肤布局：**脸在 `(8,8)` 的 8×8**，第二层（头发/帽子）在 `(40,8)` 的 8×8。

```java
faceGraphics.drawImage(skin, 0,0,8,8,  8, 8, 16, 16, null);   // 脸
faceGraphics.drawImage(skin, 0,0,8,8, 40, 8, 48, 16, null);   // 叠第二层
graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);  // 最近邻 → 硬像素
graphics.drawImage(face, 0, 0, width, height, null);
```

取皮肤：`renderer.getTextureLocation(entity)`（这样"每个市民一张皮"自动生效）；
读图片：`Minecraft.getInstance().getResourceManager().getResourceOrThrow(loc).open()` + `ImageIO.read`。

---

## 9. 数据、网络与宿主

### 9.1 文案一律由 Java 填（页面不写死数据）

```java
Element el = document.getElementById("bioName");
if (el != null) el.setTextContent(name);
```

页面上只留**结构标签**（"生命""姓名"这类）与空值；数据、格数、数值全部运行时填。

### 9.2 三条打开路径

| 场景 | 写法 | 本项目 |
|---|---|---|
| 普通界面（`Screen`） | `new ApricityScreen(path)` 子类 | `FactionSelectScreen extends ApricityScreen` |
| 世界内窗口（`WorldWindow`） | `new WorldWindow(page, anchor, range)` | `NpcIdentifyWindow`（含 `EMPTY_DISPLAY_RANGE` 变体） |
| **真实容器菜单** | `new PendingMenu(player, page).bind(...)`（服务端） | `NpcBackpackMenu` |

`ApricityUI.screen(path)` / `new ApricityScreen(path)` 都是 **UI-only**，即使页面里写了 `bind="player"` 也没有真实槽位；
要从服务端走 `menu(...).bind(...)`。旧入口 `openScreen(...)` 还在，新代码别用。

### 9.3 消除"刚打开瞬间被放大"

只靠 tick 认领会晚一帧：第一帧画 HTML 默认尺寸，下一帧才被 Java 改成目标尺寸 → 视觉上"闪一下被放大再跳回"。
做法：在 **`ScreenEvent.Init.Post`** 里就判一次并立刻套用（容器屏用 `getLinkedDocument()` 取 Document）。

### 9.4 只存在于服务端的属性：用实体同步数据

例：饥饿值在服务端 `NpcData` 里，客户端界面读不到（身份卡一度永远是"无"）。
正解：`SynchedEntityData`（服务端逐 tick 镜像，只在值变化时发包）：

```java
builder.define(DATA_HUNGER, 0.0F);
if (!level().isClientSide()) entityData.set(DATA_HUNGER, (float) data.getHunger());
public double getSyncedHunger() { return entityData.get(DATA_HUNGER); }   // 客户端可读
```

同类需求（皮肤名、称号……）都走这条路，不要为此新开网络包。

现存同类同步字段（照抄即可）：`DATA_HUNGER`（饥饿）、`DATA_SKIN`（皮肤）、`DATA_STATUS`（排班+行为的中文标签，服务端由 `NpcDetail#label()` 生成）。

**界面数据一律逐 tick 从实体重读，不要只在开屏的快照里读一次**——行装页的生命/饥饿就是踩过这条：`NpcBackpackOpenPacket` 只在开屏应用一次，界面开着数值就不再变。正确做法见 §11.A 第 40 条。

### 9.5 市民皮肤：按性别稳定分配

`NpcSkins#assign(gender, id)`：女性 → `female1..female61`，男性 → `male1`，按 UUID 取模保证"同一个人每次进世界同一张脸"；
`NpcManager#applySkin` 在 `registerNpc` 与 `load` 时补分皮（老存档读档自动补），渲染端
`NpcEntityRenderer#getTextureLocation` 只读同步下来的 `skin`，读不到回落 `NpcSkins.DEFAULT_SKIN`。

### 9.6 建筑面板的专属通道：一个页面配三个包

通用宿主 `BuildingPanelScreen` 只够放"名字 + 等级 + 位置 + 几个死按钮"；有业务的面板（农业 `farm.html`、养殖 `ranch.html`）必须自带一套包，否则点下去只剩"该功能尚未接入"：

| 包 | 方向 | 作用 |
|---|---|---|
| `XxxPanelRequestPacket` | C→S | 空载荷，建筑由服务端按玩家位置判定（`BuildingRegistry#locate`） |
| `XxxPanelActionPacket` | C→S | `action` + 目标 id，一次操作一个包 |
| `XxxPanelStatePacket` | S→C | **整份**状态，每次操作后全量回推，不做增量 |

- 路由：`BuildingPanelService#pageOf` 给出页面路径，客户端 `BuildingPanelClient#accept` 按路径**特判**开专属 `Screen`（`FarmPanelScreen.PAGE` / `RanchPanelScreen.PAGE`），不要再喂给通用宿主；
- 客户端回包落地：`XxxPanelHooks.stateSink = XxxPanelScreen::accept`，挂点在 `BuildingClientSetup#onClientSetup`；
- 列表行由 Java 拼 HTML 再 `setInnerHTML`，**新节点必须重新 `addEventListener`**（整块 innerHTML 换掉，旧监听全丢）；
- 面板要展示的清单一律现读蓝图 json（农业 `crops`、养殖 `animals`），并缓存一层，别每次操作都读盘。

**餐厅页是这套通道的特例（2026-10-26 改造）**：`restaurant.html` 底栏主按钮 `rs-pick` 文案改为"开业 / 布置"，
点它发 `RestaurantLayoutRequestPacket`（空载荷）→ 服务端 `RestaurantPanelService#reopen` 现场扫描建筑范围内的
椅子/桌子（`RestaurantFurniture` 的 `CHAIR_BLOCK`/`TABLE_BLOCK`，id 暂留空）、把"座位 + 紧邻桌子"配对写进
`restaurant_seat` 表并刷菜单；而打开页面时发的 `RestaurantMenuRequestPacket` **只读内存、不扫描**，
避免每次开界面都跑一遍包围盒遍历。按钮仍用 `.bp-btn` 既有样式，没有新增 CSS 节点。

**商铺页与货架容器页（2026-10-26 新增）**：

- `shop.html` 沿用通用宿主（`BuildingPanelScreen`，前缀 `sh`）：新增一行 `sh-state`（营业 / 已打烊 · N 个货架），
  底栏主按钮 `sh-shelve` 文案由 Java 按状态在"开业 / 打烊"间切换，点击发 `ShopPanelActionPacket(open)`，
  服务端 `ShopPanelService#apply` 切状态、开业时重扫货架方块并落库，再整份回推 `ShopPanelStatePacket`；
- **货架容器页 `buildings/shelf.html` 是 `storage.html` 的复制品，只改了 `<container>` 的两处**：
  `id="saved_data"` → `id="block_entity"`、`bind="saved_data"` → `bind="block_entity"`。原因是 AUI 的
  `PendingMenu` 用**声明 id** 匹配页面上的 `bind`，绑方块实体就必须走 `ContainerBindType.BLOCK_ENTITY`
  （`BindingBuilder#blockEntity(BlockPos,int)`，见 `aui1.2.7/.../network/handler/BindingBuilder.java:75-88`）；
- 复制时**必须沿用** `storageRoot` / `storageTitle` / `storageCapacity` / `bslot0..53` 这套 id，
  这样 `BuildingStorageScreenDriver` 能零改动复用（它按 `storageRoot` 判页面、按 `bslot{i}` 切 `.is-off`），
  标题文案改由服务端发 `BuildingStorageOpenPacket("商铺货架", 54, 54)` 填入；
- 方块实体侧只要实现原版 `Container`（54 格），`PendingMenu.bind(b -> b.blockEntity(pos, 54).player())`
  即可工作，不需要再写 menu/menuType。

**市政厅资金存取的页内弹窗（2026-10-28 新增）**：

- 弹窗整段写在 `city_hall.html` 里（`#chTreasuryModal`，结构照抄 `#chBuyModal`：`modal-box--buy` + `modal-head` + `modal-body` + `modal-actions`），
  **不要在 Java 里 `new Element` 造节点**——运行时新建节点不参与测量/重绘（§11 坑 43），Java 只切 `.is-open`；
- 数字输入框复用现成的 `modal-search field` 包一层 `<input id="chTreasuryInput">`，页面已有
  `aui-mouse-events=intercept`，输入框能直接收到键盘；
- **点遮罩关闭**：给 `.modal` 本身挂 `click`，用 `event.target == modal` 判断点的是遮罩还是内容盒
  （`com.sighs.apricityui.event.Event#target` 是 `Object` 公共字段，`stopPropagation()` 也可用），
  不要用坐标比对——AUI 的 `MouseEvent.offsetX/offsetY` 是元素局部坐标，换缩放就会错；
- 开合与提交都由 Java 驱动：`click(document, "chGoldVital", this::showTreasuryModal)`，
  按钮回调里读 `Input#getValue()`（`Long.parseLong` 包 try/catch），发包后**立即 `toggleClass(..., false)` 关闭**，
  服务端 `CityHallTreasuryService` 校验领主后回推整份 `CityHallDataPacket`，资金条与流水随之刷新；
- 金库无上限时不要把 `goldMax` 填成与 `gold` 相等——`chGoldMax` 与 `npc-bar max` 都会变成同值，
  视觉上像"满了"。本项目的做法：`goldMax > gold` 才显示上限，否则留空文本、`bar` 传 `Math.max(1, gold)`。

---

## 10. 调试手册

| 症状 | 先查什么 |
|---|---|
| 改了页面没反应 | `src/main/resources/...` 与 `build/resources/main/...` 是否一致（NeoForge 读后者） |
| 页面整块空白/豆腐块 | 该字符在 Zpix 里有没有字形（§6.1） |
| 元素显示不出来 | 是不是 `.xxx{display:none}` 之后**没有规则把它打开**（§11 坑 2） |
| 尺寸/比例奇怪 | 逻辑视口多大（窗口 ÷ min(GUI Scale,5)）、页面有没有被记成非 1 倍缩放 |
| 槽位空白/无边框 | 槽位上有没有 `--aui-slot-render-bg:0`（§7.2）或 `render="item"/"none"`（§7.10） |
| 槽位点不动 | ① 页面有没有 `aui-mouse-events=intercept`（§11 坑 32）；② `canOperateBoundMenuSlot`（§7.9）；③ `--aui-slot-size` 是不是还是 18（§11 坑 28） |
| 打开菜单卡顿 / 日志刷 GL 错 | 是不是在 AUI 文档里用了关卡共享 bufferSource（§8.3） |
| 像素发虚 | 有没有非整数缩放（`scale=fit`、存量 zoom） |
| tooltip/光标偏移 | 居中平移有没有泄漏到同帧后续绘制（§11 坑 30） |

日志关键字：`[AUI Document]`、`[AUI Layout]`、`[AUI Viewport]`、以及本项目自打的诊断行。

**验签名不要猜**：NeoForge/MC 的类名与签名用本地 jar 直接查，例如

```powershell
javap -p -classpath <merged-mc.jar> net.minecraft.client.renderer.entity.EntityRenderDispatcher
```

本项目真实踩过的三个例子：`Lighting` 在 `com.mojang.blaze3d.platform`（不是 `client.renderer`）；
`Model` 在 1.21 不是泛型类（要用 `EntityModel<?>`）；`PlayerModel.cloak/ear` 是 private。

**编辑器的缓冲区回滚**：本仓库编辑时出现过"同一批 `replace` 一半生效一半没生效"（旧缓冲区写回）。
改完关键文件后**立刻回读/校验**（数一下替换后的字符串是否还存在），必要时一次性原子改写 + 校验。

### 客户端看门狗：卡死之后还能取证

渲染线程一旦卡死，日志也跟着停（日志是那条线程同步写的）。因此模组内置了 `client/watchdog/ClientWatchdog.java`：

- **两套通道**：心跳由客户端线程打（`RenderFrameEvent.Pre/Post`、`ClientTickEvent.Post`），
  巡检与写盘全在独立守护线程 `kingdom-watchdog` 上，渲染线程再死也拦不住它。
- **落盘位置**：`logs/kingdom-watchdog/`，每次卡死一份带时间戳的完整报告，另有一行摘要追加到 `summary.log`。
- **三条判据**：① 心跳增量（`0` = 客户端线程完全停住；`>0` = 没死只是极慢）；
  ② 当前所在阶段（自定义元素的 `beginPhase/endPhase` 记录最后进入却没退出的那段代码）；
  ③ 渲染线程栈顶 + CPU 增量（猛涨 = 自旋；几乎不涨 = 卡在锁或驱动）。
- **附带内容**：当前屏幕与 AUI 文档路径、窗口/GUI Scale、面包屑、全线程栈、JVM 堆、`latest.log` 末尾 120 行。
- **自动动作**：第 2 份报告开安全模式（重绘型自定义元素此后只画底框）；第 3 份排队关掉当前界面。
- **手动触发**：`ClientWatchdog.dumpNow("原因")`、`ClientWatchdog.setSafeMode(true)`。

> 排查卡死先看 `logs/kingdom-watchdog/summary.log`，再打开那一份报告看线程栈。

---

## 11. 踩坑速查表（分两段：我们的坑 / AUI 的坑）

> **编号只增不改**（`开发规则.md` 规矩 A 要求"编号顺延"，跨文档按 `坑 N` 引用）。
> **11.A = 我们自己的坑**（页面/Java 侧可直接修）；**11.B = AUI 侧行为造成的坑**（只能绕过，逐条证据见 §16）。
> 11.B 之后按主题分组的明细表是同一批条目的原始记录（保留备查，含证据与细节）。

### 11.A 我们自己的坑（自查优先看这里）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 图标/纹章整块空白 | 选的符号在 Zpix 里没有字形（16 个里 14 个 False） | 换成实测存在的符号（§6.2） |
| 2 | "立国建邦"弹窗只剩两个按钮 | `.modal{display:none}` 之后**没有规则把它打开** | 补 `.overlay.open .modal{display:block}` |
| 3 | 弹窗/信封不居中、错位 | `position:fixed` + `left:50%` + 负 margin + 百分比定位 | 父级 flex 居中，弹窗回文档流 |
| 4 | 质感与边缘全丢 | 参考稿的颗粒是 JS 程序化贴图，AUI 跑不了 | `tex/` 贴图 + `border` + 四层 `box-shadow`（§5.1） |
| 5 | 像素感消失、整体发虚 | ① 普通页误用 `scale=fit` ② 页面被 AUI 记住了历史 zoom | 普通页钉 1:1；`Ctrl+0` 复位（§16 D19） |
| 7 | 整页巨大、只剩一角、多出滚动条 | 卡片写死尺寸，而逻辑视口只有 543×284 | 尺寸由 Java 按 `getViewportSize()` 算（§4.3） |
| 9 | 卡片不居中 | 依赖 flex 居中的默认行为 | Java 写显式居中（或 §13.6 的居中 mixin） |
| 11 | 3D 模型头顶被裁在框外 | 缩放按 100% 框高、脚底按 88% 放 | 缩放 0.74、脚底 0.94（§8.3） |
| 12 | 刚打开瞬间被放大、随即跳回 | 版式在打开后的第一个 tick 才套用 | `ScreenEvent.Init.Post` 里立刻套用（§9.3） |
| 13 | 身份卡饥饿永远"无" | 卡片是纯客户端拼的，饥饿只存在于服务端 `NpcData` | `SynchedEntityData`（§9.4） |
| 14 | 滚轮会滚动/缩放页面 | 我们没关滚动（AUI 的开关是 `overflow-y`，`Interaction#allowsUserScroll`） | 只在有确定尺寸的块上开 `overflow-y:auto` |
| 15 | 边框外多出一圈背景 | 元素背景/贴图溢出 | 卡片与面板加 `overflow:hidden` |
| 16 | 改了页面游戏里没变 | 读的是 `build/resources/main` 下的副本 | 同步或走一次 `processResources` |
| 24 | 文档区域整块发黑 | body 的深色底（原稿给浏览器用的）涂满整个文档矩形 | body 背景改 `transparent`（§13.7） |
| 25 | 3D 模型变成侧面"柱子" | 自定义元素里鼠标用 GUI 坐标、元素位置用文档坐标 | 统一过 `screenToDocumentPosition`（§13.9） |
| 29 | 行囊面板比卡片还宽、槽位挤在左侧 | 面板宽跟着卡片写死 `1080px` | 面板宽按槽位数反推（§13.8） |
| **36** | **identify 卡"任何角度都能渲染"，但拾取矩阵是错的** | 我们把 `P × event.getModelViewMatrix()` 当投影传进 `WorldWindow#render`；而 `poseStack` 已含相机旋转 ⇒ `P·MV·pose` 把 V 乘了两遍：① `isQuadVisible` 形同关闭（所以"完全渲染"）② `captureInteractionTransform` 写出错的 `interactionClipMatrix`。同时我们的窗口是 `new WorldWindow(...)` **未登记** ⇒ AUI 的渲染器与鼠标分发都不覆盖它，我们的 `draw()` 是唯一渲染路径 | **路线①（推荐）**：改传 `event.getProjectionMatrix()` + 补 §16 D24 的"半个面板"mixin（渲染照样完整、拾取回正、省掉每帧 document/stencil）；**路线②**：需要卡片 DOM 交互就改走登记路径（`ApricityUI.createWorldWindow(...)`）+ 删掉自己的 `draw()`（否则双渲染）。细节见 §16.6、§17 |
| 37 | 建筑操作面板做出来"不像同一套界面"：面板居中、字体是默认字体、按钮点不动 | 写页面前没读已有的 `buildings/residential.html`，凭记忆另起了一套骨架，连带漏了 `@font-face` Zpix、`aui-mouse-events=intercept`、`mode=gui` viewport、`pane_gen.png` 底纹 | **建筑类操作面板一律以 `residential.html` 的侧边栏骨架为准**（`.bp-bar` 固定左侧 `width:25%` + 头/体/底三段 + `.bp-hint`），只换内容；类名沿用 `.bp-*` 体系。新增面板前先读该文件，别再另起。**色板、尺寸、组件同样是统一的一部分**：不许自创色（如另加一个红色 `--danger`）、不许自创尺寸（行内方图标一律 24×24 对齐 `.bp-face`，大槽位 48）、按钮一律 `.bp-btn` / `.bp-btn-quiet` / `.bp-btn-main` 三选一，别为"危险操作"另造一套 |
| 38 | 脱离 `<slot>` 单独用的 `<item>`，图标缩在盒子左上角 | 与坑 31 同源：UA 把 `item` 定死 16×16、`slot > item{position:absolute;left:1px;top:1px}`，而居中拿的是 `<item>` **自身矩形**（`Rect.of(item)` 按 16px 居中），不是父盒子 | 给 `<item>` 写 class 设 `position:absolute;left:0;top:0` + 宽高等于父盒子。**纯 DOM 页里的作物/物品图标就照这个写**，不要用 `<slot>`（那要真实容器菜单） |
| 39 | 同一元素同时带"面板类"与"覆盖层类"时，`display` 谁赢说不清 | 两个单类选择器优先级相同，只能靠源码先后顺序定胜负；改一处 CSS 顺序就翻车 | 覆盖层写成两个类叠加（如 `.bp-bar.bp-overlay{display:none}`），优先级直接压过单类。二级页同理用 `.bp-head/body/foot-off` 三个类分别关三段，不要指望一个类通吃 |
| 40 | 身份卡"状态"永远是"无"；行装页生命/饥饿停在开屏那一刻不动 | ① 卡片是客户端拼的（`NpcIdentifyWindow#profileOf`），而排班/行为 detail 只存在于服务端 `NpcData`，传 `null` 就渲染成"无"；② `NpcBackpackScreenDriver#onClientTick` 里"快照没变就直接 return"，生命/饥饿只在开屏应用了一次 | ① 行为标签走同步数据：`NpcEntity.DATA_STATUS` 逐 tick 镜像 `NpcDetail#label()`，卡片读 `getSyncedStatus()`（§9.4）；② 生命/饥饿逐 tick 从实体重读（生命＝原版同步字段，饥饿＝`getSyncedHunger()`），只在数值变化时写 DOM，开屏与换页时清缓存 |
| 41 | 八个建筑面板各写各的：`residential.html` 的顶部/中部/底部**没有 id**、建筑信息是写死的演示值；各页底部只有"打开仓库/关闭"、没有主操作 | 建页时只统一了"侧边栏形状"，没规定四段的内容与位次；坑 37 只说了骨架像 `residential.html`，没说主操作放哪 | 以 `farm.html` 为基准的四段规则：**顶部** `.bp-head`（`.bp-type` 分类 + `.bp-title` + `.bp-lv` 配额）＋ `.bp-body` 里的 `.bp-info`（位置/蓝图/业务字段，值一律留 `—` 与 id，由 Java 填）；**中部** `.bp-block > .bp-sec(+.bp-search) + .bp-list + .bp-empty`（本轮 farm/ranch/industry/restaurant 有条目区，residential/shop/military 中部留空）；**底部** `.bp-foot` ＝ 主操作 `.bp-btn-main`（创建农田 / 开始养殖 / 开始生产 / 选择菜品 / 自动上架 / 创建军队 / 安排入住）＋ 关闭；**底部第二行** `.bp-foot.bp-foot-off`（页内 id `bp-warehouse-row`，farm 是 `fd-foot`）＝ 打开仓库 `.bp-btn-main` **独占整行**，由 Java 按 `WAREHOUSE_PAGES` 开显隐（坑 44）——打开仓库**不许**塞进第一行跟主操作并排；**最下** `.bp-hint`。`storage.html` 是真实容器页形态（54 格 `<container>`），暂不并入此骨架 |
| 42 | 仓库没有建筑类型；军事/住宅按 Tab 打不开；各页信息框永远显示"—" | ① `BuildingType` 只有 6 类、`structures/` 下也没有 storage 目录，蓝图元数据缺一个类型；② `pageOf` 的 `default -> ""` 把军事/住宅挡在面板之外；③ `BuildingPanelStatePacket` 不带坐标/蓝图，通用宿主只能填名称与等级 | ① 新增 `BuildingType.STORAGE`（别名 warehouse/depot/logistics）＋ `structures/storage/_example.json`（`storage_slots: 54`，与容器页格数一致）；② `pageOf` 补 `MILITARY/RESIDENTIAL/STORAGE` 三条分支；③ 状态包加 `pos` 字段，通用宿主按“页面前缀表”（im/rm/rs/sh/mt/rd/wh）填 `<prefix>-pos` 与 `<prefix>-blueprint`；④ 新增 `buildings/warehouse.html`（以 `ranch.html` 为模板）：顶部信息框 → 中部“运输关系”卡片区 → 底部 `创建链接`(`.bp-btn-main`) + `打开核心仓库`（沿用 `bp-warehouse`，走既有的 `BuildingStorageRequestPacket` → `BuildingStorageMenu`）；⑤ `storage.html` 定位改为“各建筑仓库的容器绘制页”，不再算操作面板 |
| 43 | 建筑面板底栏的「打开仓库」按钮**怎么都显示不出来**（ranch 底栏只有页面 HTML 里那两颗）：Java 里 `new Element(document,"div")`+`foot.insertBefore(...)`、`init()` 里 `foot.setInnerHTML(...)`、首帧 `tick()` 里 `foot.setInnerHTML(...)` 三种写法全试过，全都不出来 | ① 运行时**新建**的节点（`new Element`）只进 `children` 列表，不参与 AUI 的测量/重绘；② 底栏这类"固定几颗按钮"即使整段 `setInnerHTML` 重建，也不出现在画面里（与列表行不同——列表宿主是 `overflow` 滚动容器，底栏不是） | **照抄农业页的机制**：按钮**本来就写在页面 HTML 里**，带 `style="display:none"`（`farm.html` 的 `fd-save`/`fd-cancel` 同款），由 Java 用 `setVisible` 打开——`element.setAttribute("style", visible ? "display:flex" : "display:none")`（`FarmPanelScreen:795`）。即"该按钮由 Java 添加"的真实含义是**Java 控制显隐**，而不是 Java 造节点。六个建筑页的 `bp-warehouse` 都按这条：按钮写在页面 HTML 里，`BuildingPanelScreen` 首帧 `tick()` 里 `setVisible` 控制它**所在底栏行**的显隐（住宅页不显示；成行样式见坑 44） |
| 44 | 建筑面板的「打开仓库」被做成**贴着主操作的小按钮**，只有农业页是底栏独占一整行 | 坑 41/42/43 只规定了"底栏要有打开仓库"，没说它**占几行**；六个页都写成 `.bp-foot` 里并排三颗，而基准页 `farm.html` 是**两行**：主操作行 + `fd-foot`（`bp-foot bp-foot-off`）单独放打开仓库 | 六个页（industry/military/ranch/restaurant/shop/warehouse）底栏拆成两行：第一行 `.bp-foot` 只留主操作 + `bp-close`；第二行 `<div class="bp-foot bp-foot-off" id="bp-warehouse-row">` 里放 `<div class="bp-btn bp-btn-main" id="bp-warehouse">打开仓库</div>`（照抄 farm 的 `fd-foot`/`fd-warehouse`，按钮**不带** inline `style`）。Java 侧 `BuildingPanelScreen.sync()` 改为对**行**切类：`setOff(document.getElementById("bp-warehouse-row"), "bp-foot-off", !WAREHOUSE_PAGES.contains(prefix))`（照 `FarmPanelScreen.setOff` → `element.getClassList().toggle(token, off)`，容器用切类、不用 inline style），点击监听仍挂 `bp-warehouse`；按钮 id 与 `openWarehouse()` 路径不变。养殖页后来独立成 `RanchPanelScreen`，那一行由它自己切（见坑 45） |
| 45 | 养殖面板**整页只有死数据**："可养动物 0 / 0 头"、列表永远是空的、"这座建筑没有配置可养动物" 常显、"饲养员 未指派" 改不动，底栏「取出动物」点了只弹"该功能尚未接入" | `ranch.html` 里 `rm-total`/`rm-animals`/`rm-empty`/`rm-keeper`/`rm-search`/`rm-search-clear` 六个 id **没有任何 Java 读写**（通用宿主 `BuildingPanelScreen.sync()` 只填 `bp-name`/`bp-level`/`rm-pos`/`rm-blueprint`），而养殖的服务端循环（`RanchWorkService`）早就写好了——缺的是"面板 ↔ 栏"的连接层 | 照"农业那套"补齐 §9.6 的三件套：`common/network/ranch/`（Request/Action/State + Hooks + Registration）、`server/ranch/RanchPanelService`（快照现读蓝图 `animals` 与市民名册，动作＝开始/暂停这一栏、任免饲养员）、`client/ranch/RanchPanelScreen`（拼行 + 重挂监听 + 搜索），并在 `BuildingPanelClient.accept` 里按 `RanchPanelScreen.PAGE` 特判开屏、`BuildingClientSetup` 挂 `RanchPanelHooks.stateSink`。页面侧加了两处：信息框一行 `rm-recipe`（饲料→产出·方式·上限），以及**二级页 `rc-*`**（单条配方详情 + 饲养员名册，骨架照抄 `farm.html` 的 `fd-*`：`bp-head-off/bp-body-off/bp-foot-off` 三段切显隐）；一级底栏主按钮 `rm-start`＝开始/暂停养殖（开始时同建筑其它栏自动停下），饲养员由玩家在二级页点行任免，**没有自动指派**。二级页的市民行照 `farm.html` 用 `<kingdom-entity-avatar class="bp-worker-face" skin="...">` 渲染**头像**（`skin` 走状态包里的 `NpcData#getSkin`），状态文案用 `.bp-sub` 写"空闲/饲养中/饥饿/下班" |
| 47 | 世界地图**整块由服务端采样并推送**：客户端 `<world-map>` 每 250ms 发一次 `WorldMapRequestPacket` 要数据，服务端采样后切片下发 `WorldMapDataPacket`，并把结果按玩家存进 `ExploredMapStore`（SavedData） | 地图渲染本来就是**纯客户端**的事（Xaero 那类 mod 全在客户端做）。搬到服务端等于：为每个玩家存一份地形像素（SavedData 随探索无限增长）、每次开界面走一趟"请求→采样→分片下发"的往返，还把"只有已加载区块才有数据"这个客户端天然限制变成"服务端替每个玩家算一遍" | 拆掉整条服务端 + 网络链路（`server/map/` 两个类、`common/network/map/` 四个类、`ServerLifecycle` 的两处驱动、`PlayerProfile` 的 `map_center_*` 列、`CityHallDataPacket` 的两个字段），改为**客户端自采自用**：`client/map/ClientMapScanner`（`ChunkEvent.Load` 入队 + 客户端 tick 每 tick 采 2 块 + 玩家周围 13×13 兜底 + 视口优先）用 `WorldSurfaceSampler.sampleChunk`（参数类型由 `ServerLevel` 放宽到 `Level`）写进 `client/map/ClientMapStore`（按维度缓存 + 落盘 `gameDir/kingdom-client-map/<世界>/<维度>.bin`，30 秒一刷、登出即存、带视口中心），`WorldMapElement` 直接读它、删掉请求/节流/`revision` 那套。**注意**：NeoForge 客户端不允许后台线程碰 `Level`，所以"后台渲染"落地就是**主线程分帧渐进采样**，只有落盘 I/O 在后台线程；视口**每次打开市政厅都归位到市政厅方块**（`CityHallScreen#pointMap` 直接写 `center-x/center-z` 为市政厅坐标，并调 `WorldMapElement#resetView()` 复位缩放与拖动偏移，不再沿用上次视野）；滚轮在地图元素与全屏地图层内被 `event.preventDefault()` 吃掉（AUI 的 wheel 在 `Operation#scroll` 里创建时 `cancelable=true`，`MouseEvent#triggerResolvedEvent` 只在 `!defaultPrevented` 时才走 `applyScrollDefault`），所以缩放地图不会顺带滚动页面 |

| 48 | 打开世界地图后**帧率掉到 1~20** | ① 地图元素**每帧重建整张光栅**：`sheet()` 的签名里含 `ClientMapStore.revision()`，而扫描器每 tick 采 2 块 ⇒ 版本每帧都变 ⇒ 每帧重铺；全屏 1920×1080 时单次重建约 **400 万次** `SpanSheet.over()`（逐像素 alpha 混合调用，含整块 `fill` 背景）。② 提交走 `Graph.drawFillRect` 逐矩形，这个量级撑不住 | `WorldMapElement` 由 `extends Element` 改为 **`extends Canvas`**（与 `KingdomEntityAvatarElement` / `KingdomShieldElement` / `KingdomSealElement` 同路）：`renderOperation(g -> …)` 把地图画进位图表面 → `syncTexture()` 上传 → `ImageDrawer.draw` **一次**画完（1 个绘制调用）。**光栅尺寸 = 表面尺寸（1:1，`drawImage` 不拉伸）**，按 `step` 逐块填充；几何只由 `blocksX=(2·radius+1)/zoom` 决定，`step` 只控采样密度（`1/pixelsPerBlock` 起算、`MAX_SAMPLES` 封顶、拖拽 ×3），逐行按**区块**批量取 `byte[]`（每 16 列才查一次 map），取色走 256 项 `PALETTE`，`int[]` 与 `BufferedImage` 跨帧复用；重建**节流**：几何变化立即重建，仅内容变化最多 300ms 一次；纹理只在重建后 sync（绘制里仅 `textureLocation == null` 时兜底）。网格线与标记画在表面层。**注意**：更激进的"脏区块增量 + 直接写 `getSurface()` 的 `DataBufferInt` + 只上传脏矩形"实测**整张地图完全不渲染**（异常打断 `drawPhase`），已回滚到本版本；要再试必须逐项验证——重点怀疑 `Box.of(this).innerSize()` 与 `getWidth()/getHeight()` 的口径不一致、以及绘制期 `markSurfaceDirty` 的重入 |

| 49 | 地图交互**全坏**：小窗一按下画面就放大 3 倍、拖完被判成"点击放大"进了全屏、全屏里滚轮缩放毫无反应、大地图被压扁 | ① 把原 `step`（跳像素采样，**几何不变**）误改成"把覆盖方块数 ÷3" ⇒ 一按下视野缩小 3 倍（＝放大）。② 光栅按 `ceil((2·radius+1)/zoom)` 取**正方形**再拉伸到矩形表面 ⇒ 宽高比失真（变扁）。③ `grid` 被 `MAX_GRID=1024` 卡死：全屏 `radius=320` 时 `zoom∈[0.25,0.63]` 全算出 1024 ⇒ 缩放看不出变化。④ 元素内 `mousedown+mouseup` 同样是一次 `click`，冒泡到 `chMapBox` 的"点击放大"监听 ⇒ 拖完误开全屏 | 把"几何"与"采样密度"彻底分开：几何只由 `blocksX=(2·radius+1)/zoom` 与表面尺寸决定，`step` 只控采样密度（拖拽时 ×3 可放心用）；光栅改 1:1 表面尺寸、`drawImage` 不拉伸；删掉 `MAX_GRID`（改用 `MAX_SAMPLES` 预算控采样量）；加 `WorldMapElement.draggedRecently()`（累计拖动像素 >4 才算拖动，松手后 250ms 内抑制），`CityHallScreen` 的 `chMapBox` 点击回调据此提前 return |

| 50 | **定稿页面把 `scale` 写成固定数字后，窗口一缩就"页面不跟着缩小 + 严重偏离中心"**（大窗口下完全正常，所以极易漏测）；`city_hall`（`0.45`）、`backpack`（`0.4864`）、`storage`（`0.4864`）三页同病 | 固定数字走的是 `ApricityViewport#fixed` 的最后一个 `else` 分支（`parseDouble(scaleOption, 1.0d)`），**该分支完全不读窗口** ⇒ `renderScale` 是**常量** ⇒ 文档在 GUI 坐标系里的尺寸恒定（如 `1124 × 0.45 = 506×384`），窗口怎么变它都不变——"不缩放"由此而来。而居中偏移是每帧/每次 `applyViewport` 用**当前视口**现算的（`AuiDocPlacement#centeredOffset` 里的 `(guiWidth − docWidth)/2`）⇒ 视口一变，这个式子的输入就失去意义：视口 `960×540` 时偏移是 `(227, 54)`，视口 `480×270` 时文档（`506×384`）**比视口还大**，横向余量成负数被 `max(0,…)` 夹成 0、纵向被 `MIN_TOP=8` 硬推下移 ⇒ 右侧裁 26、底部裁 122。**"不缩放"和"偏移"是同一个根因的两个症状，不是两个 bug**——排查时现象一直指向居中补丁，真凶却在配置里 | 定稿页面的 `scale` 一律用 **`fit`**：`renderScale = min(GUI宽 ÷ W, GUI高 ÷ H)`，每次 `applyViewport` 重算 ⇒ 整稿等比放进视口，**且必然有一个方向恰好等于视口**，于是"居中偏移"的语义恒定（把余量对半），永远不会出现"文档比视口大"的病态输入。三页已统一 `scale=fit` + `zoom` 钉死 1（`zoom` 在 `fit` 下只改逻辑坐标精度、不改实际显示大小，留着只会让人以为 Ctrl+滚轮坏了）。配套加固：`AuiDocPlacement#centeredOffset` 现在对**已铺满的方向**（`gapY ≤ 0`）返回 0 偏移，免得旧病复发时把文档推出视口。**判据**：页面出缩放问题时，先问"文档尺寸是否会随窗口变"，再怀疑补丁 |

| 51 | 市政厅「政务处理」列表的行**点不动 / 只在第一次能点** | 政务行由 Java 用 `setInnerHTML` 动态生成。`setInnerHTML` 会**重建整棵子树**，先前 `addEventListener` 绑在旧节点上的监听随旧节点一起丢掉；而重建发生在**每次收到 `CityHallDataPacket` 时**（`CityHallScreen#bind`）⇒ 只在 `bindInteractions` 里绑一次必然失效 | `setInnerHTML` **之后**再遍历数据、按 `id` 逐个 `getElementById` 取新节点重新 `addEventListener("click", …)`（`CityHallScreen#fillAffairs`）。规律：**动态生成的节点每次重建都要重新绑事件**，只有写在页面里的静态节点才可以在 `bindInteractions` 里绑一次。样式上只给现有 `.rline` 加一个变体类 `.rline--affair`（`cursor:pointer` + `:hover` 背景色，`:hover` 与页面既有 `.cal-icon:hover` 同款写法），不引入任何新布局机制。`cursor` 属性在 AUI 中是否真的改光标**待实测**（不影响功能） |

| 52 | 往 `<world-map>`（`Canvas` 元素）里**新加的一层画完不显示**，或要等地图自身数据刷新才一起冒出来（本次：右键选区的高亮层） | `WorldMapElement` 的光栅只在 `repaintIfNeeded` 判定为"几何变了 **或** 区块脏了"时才重建（`paintAll` / `repaintChunk`），而**我们自己新加的绘制层不在它的判定输入里** ⇒ 只改我们自己的状态（选区、外来源的领地/建筑数据）不会触发重绘，表现是"数据对了但画面不动" | 给元素加一个自持的脏标志（本次是 `selectionDirty`），把它并进 `repaintIfNeeded` 的 `geometry` 条件、在 `paintAll` 之后清除；另开一个 `requestRepaint()` 给宿主，在"外部数据变了"时调用（`CityHallScreen` 把它挂在 `MapOverlayStore.setChangeListener` 上，购买领地后地图立刻更新）。**规律：往 `Canvas` 元素加新绘制内容，必须同时接进它的重绘触发链** |
| 53 | 给地图加右键操作，按 DOM 惯例写 `event.button == 2` 拿不到、分支不生效 | AUI 的 `MouseEvent#button` 走的是 **GLFW 约定（0 = 左键、1 = 右键）**，不是 DOM 的 0=左 / 1=中 / 2=右。源码自证：`event/MouseEvent.java` 的合成分发里 `originalEvent.button == 0` → 派发 `click`、`originalEvent.button == 1` → 派发 `contextmenu`（右键菜单事件） | 右键判断一律写 `button == 1`，左键 `== 0`。左键拖动平移与右键框选要在 `mousedown/mousemove/mouseup` 各自**先分流**，否则右键会被当成平移把地图拖走 |

| 54 | 在 `Canvas` 元素上画**任意形状的区块集合**（领地轮廓 / 地图选区）时，用包围盒遍历会把不该填的格子也填上（表现为"边缘买一格、中间整片被吞"） | 领地和选区早期都是矩形（只存 min/max 四个界），一旦改成区块集合却仍按 `for (x = min..max) for (z = min..max)` 遍历，画出来还是矩形 | 一律 `for (long key : chunks)` **逐块**遍历；每一格的边框**只在"四邻不属于同一集合"时才画**（`!set.contains(邻格key)`），相邻块之间就不会出现多余格线、外轮廓自然连成一片。判定容器必须是**哈希集合**：用数组线性查找会让"每块 4 次邻居判定"退化成 O(n²)（几千块的领地在全屏地图上会卡） |

| 55 | 世界地图**拖动时帧率暴降**（静止时完全不卡，只有按住拖才掉帧） | `<world-map>` 是 `Canvas`：拖动让视图中心每帧变化 → `repaintIfNeeded` 的 `geometry` 判定为真 → **每帧走 `paintAll` 全量重建**：① `fillRaster` 先整屏 `Arrays.fill` 填未探索色、再按 step 采样覆盖（全屏约 200 万像素写两遍）；② `renderOperation` 走 Java2D 软件光栅化画叠加层（领地逐块 `fillRect`，几千块就是上万次调用）；③ `markSurfaceDirty()` **无参 = 整张脏** ⇒ `Canvas#syncTexture` 全量走 `copyRegionToStaging` 逐像素 `argbToAbgr` + `writeImagePixels` + `uploadTextureRegion`。而静止时走的是 `CONTENT_REPAINT_NANOS`(300ms) 节流 + `repaintChunk` 只重画脏区块，所以反差极大。请注意 AUI 的 `markSurfaceDirty(Rectangle2D)` **本身是支持脏区上传的**，是调用方（地图）自己把整张标脏了 | **拖动快照（只挪 uv 窗口，永不重建）**：① 光栅四周各留 `SURFACE_MARGIN = 256` 预存量——`resizeSurface(内容宽 + 512, 内容高 + 512)`，"元素矩形内"的像素密度保持不变（`pixelsPerBlock = 内容宽 ÷ 世界方块数`）；② 覆写 `Canvas#drawCanvas`，改用 **`ImageDrawer.drawWithUvWindow(...)`** 指定源区域：静止 `uTexel = MARGIN`、拖动 `uTexel = MARGIN − dragPixelX`（同理 v），`widthTexels/heightTexels` = 元素内容尺寸；③ 拖动期间 `repaintIfNeeded` **直接 return**（`if (dragging && !resized && !dragRebuild) return;`）⇒ 零重绘、零纹理上传，每帧只剩一次带 uv 的 quad；④ 松手 / 鼠标移出 / uv 超出余量时才 `commitPan()` + `dragRebuild = true` 重建一次（并把拖动锚点重置到当前鼠标点）；⑤ 拖动期间**必须** `document.markDirty(this, Drawer.REPAINT)`，否则 AUI 可能不重绘该元素、画面纹丝不动；⑥ 容器要有 `overflow:hidden`（`.map-full-box` 原先**没有**，本次补上），保证预存量绝不画到框外；⑦ **命中判定必须同步补偿**——光栅带 margin 后，"元素内坐标"与"光栅坐标"相差 `SURFACE_MARGIN − dragPixel`，建筑 / 领地 / 中心标记 / 区块的命中都要先过一层转换（`toSurfaceX/toSurfaceY`），漏改的表现是 **tooltip 与点击整体失效**（第一版就漏了，因为原先 `pixelX()` 的返回值和元素坐标恰好重合，加了 margin 才分家）；⑧ 松手/归位时要让"强制重建"标志真正参与 `geometry` 判定（`forceRebuild`），否则位移不足 1 个方块时 `commitPan` 不改中心、画面不重建，会出现"松开瞬间跳回"。**通用结论**：`Canvas` 元素想做到"拖动零成本"，就让光栅比显示区大、只挪 uv 窗口，永远不要每帧重建 |

| 56 | `structures/viewer.html` 要从"常驻底栏"改成**贴边隐藏式面板**：平时屏幕底只留一条边、鼠标贴到屏幕下边缘才向上展开；这次同时新增左侧边栏，并要求"底栏层级高于左栏、左栏往上抬让位"。两个直接坑：① 用负 `bottom` 把面板推进视口外"藏起来"，鼠标贴到屏幕最底 1px 时**展不开**；② 想让两个面板"重叠时底栏压左栏"，只调 DOM 顺序无效 | ① 贴边面板必须真的贴边（`bottom:0` / `left:0`）。用负偏移"藏"时，**收起态的命中区就是被推出视口的那部分**——`hitTest` 在视口外命不中元素，`hover` 自然不触发；而且大部分位于视口外的 `fixed` 元素与 AUI 的可见性剔除（§16 D24）边界不明。② 文档级 z 由 AUI 统一给（§16 D24 / 坑 36），**只有元素级 `translateZ` 才真能分先后** | 收起/展开一律用**改自身尺寸**：`.dock{height:12px}` → `.dock:hover{height:150px}`、`.side{width:12px}` → `.side:hover{width:240px}`，`position:fixed` 元素不参与兄弟布局，改动只影响自身。**不写 `transition`**——AUI 只对白名单属性做过渡，尺寸是直接跳变，写了等于没写（§2）。"露出多少" = 收起尺寸 − 边框：本次 `12px = 2px 边框 ×2 + 8px 把手`，把手用绝对定位 `top:0; height:8px`（相对 padding box，正好吃掉 12 − 4），里面一根 `.grip-pip` 金条提示可拉出。层级：底栏 `translateZ(405px)` > 左栏 `translateZ(400px)`。**避让要按展开态算**：左栏 `bottom:156px`（= 底栏展开高 150 + 6 间隙），收起态两者不重叠只是巧合。贴屏幕边的面板**不能**用主题 `.frame` 的硬投影（`2px 2px 0 0`，会溢出视口被切）与向外扩的 `.frame--thick`（8px 四圈环，同理），改用 **`.frame--flat` 配方**（1px 外环 + 四角 inset 浮雕、无硬投影）。⚠ **待实测**：`:hover` 驱动**布局属性**（尺寸）本页是首次（此前页面 `:hover` 只改颜色/阴影）。万一引出测量循环卡死，改为 Java 每 tick 读 `Element#isHover` 切 class（`StructureDebugKeybind#updateCardTooltip` 同款路子）。左侧栏分类行的 `setInnerHTML` 重建后必须重新 `addEventListener`（坑 51） |

| 57 | 要把底栏切成 **8 等分**（住宅/农业/养殖/商业/工业/军事/仓库/其它）、左栏切成 **4 等分**（人口/建筑/队伍/道路），试遍 CSS 的等分写法都靠不住 | AUI 里"等分"的常规写法全部不成立：`flex:1`（0% 解析，§0 铁律 2）、`grid` 的 `fr`（塌成单列）、`width:12.5%`（`%` 不可靠，§2）、`flex:1 1 auto`（**内容不等长就不等分**——8 个分类的结构数天差地别，格子必然宽窄不一）。而且这页本身就是"尺寸随窗口变"的贴边 HUD，等分基准必须是**当前逻辑视口** | 等分尺寸由 **Java 按 `document.getViewportSize()` 算好、写进元素的 inline `style`**：`element.setAttribute("style", "width:" + bandWidth + "px")`（`StructureDebugKeybind#layoutViewer`），CSS 侧只保留 `flex:0 0 auto` 锁死 + 一个默认值顶着算出来之前用。等分算式要**扣掉固定装饰**：底栏 `(视口宽 − 4 边框 − 16 内边距) ÷ 8`、左栏 `(视口高 − 156 让位 − 4 边框 − 16 内边距) ÷ 4`，两头取 `Math.max(48, …)` 兜底。**只在视口尺寸真的变了才重算**（缓存上次 `width/height` 比对，别每 tick 无条件写 style）。⚠ **`setInnerHTML` 重建会连带丢掉子孙的 inline style** ⇒ ① 整体重建后把尺寸缓存置 `-1` 强制下一帧重算；② 只更新内容的场景（左栏计数刷新）**重建"内层容器"**（`sect-rows-*`）而不是带尺寸的格子本身。格子尺寸含自己的 `border`（`box-sizing:border-box`）⇒ `8 × (可用宽 ÷ 8)` 正好铺满，不用再补间距 |

| 58 | 给底栏（**HUD 覆盖层文档**，`ApricityUI.createDocument` 创建、没有 Screen）做了个搜索框，AUI 的 `<input>` 摆上去后**显示正常、鼠标能点进去，但一个字符也打不进去**；`input.getValue()` 永远是空串 | AUI 的**字符**事件只挂在一个地方：`Client#onCharTyped(ScreenEvent.CharacterTyped.Pre)`（`targets/neoforge-1.21.1/.../client/Client.java`），而 `ScreenEvent` **只在有 Screen 时派发**。游戏内的覆盖层文档走的是另一条：`Client#onKeyPressed(InputEvent.Key)` → `Operation#handleKeyInput`，那条路**只带键码、不带字符**（`InputEvent.Key` 本身就没有 codePoint）。两份 AUI 源码（`AUI-snow` / `aui1.2.7`）都没有别处注册 `glfwSetCharCallback`。⇒ **覆盖层文档里的 `<input>` 天生收不到 `charTyped`**，与页面写法无关 | 自己补一层字符通路：打开页面前 `install()`，先读回当前回调再 `glfwSetCharCallback(handle, lambda)` 换上我们的 —— 激活态把 codePoint 吃掉喂给搜索框，非激活态原样 `previousCallback.invoke(...)` 转发；关页时 `glfwSetCharCallback(handle, previousCallback)` 还原（`client/structure/StructureSearchInput`）。三条配套纪律：① **键盘接管必须显式激活**（点中搜索框才 `active=true`），否则 RTS 的移动/操作键会被整片吃掉；② 退格、回车、ESC 走 `InputEvent.Key`（无屏幕时照样触发），字符才走 char 回调；③ 文本真源放 Java（`StringBuilder`），`input.setValue()` 只当显示，过滤仍旧每 tick 轮询 `input.getValue()` 与缓存比对（与 `BuildingPanelScreen` 同一条路子，§7.9 的能力位），加载投影后立刻取消激活交还键盘。⚠ **待实测**：回调包装与其他 mod / 全屏切换导致窗口重建时的还原是否干净 |

| 59 | 左栏要"点人口 / 建筑后**整栏**换成该分类的内容页（搜索框 + 长列表）"，而底栏**也有**一个搜索框，两者要共存且各打各的字；同时列表行点击在 RTS 下**不能同时**触发世界点击 | 三件事各有出处：① 同文档里两个 `<input>` 抢一份键盘 —— 输入代理（坑 58）只有一个 `active` 开关，不区分焦点就会串；② 列表行是 `setInnerHTML` 动态生成，点一次要能立刻改选中态（坑 51）；③ `RtsClientEvents#insideViewer` 按"元素**或祖先**的 id 前缀"判是否算点在 AUI 上（`viewer-` / `card-` / `load-` / `big-`），新行 id 若不匹配就会穿透到世界 | ① 两态切换与底栏同款：`.side.is-page` 切 `.sect-list` ↔ `.side-page`（`display:none/flex`）；② **输入焦点由 Java 侧显式记账**：`activeField ∈ {NONE, DOCK, SIDE}`，`acceptSearchChar/backspaceSearch` 按它分发到各自的 `StringBuilder`，再 `input.setValue()` 回写显示，过滤各自由每 tick 轮询 `getValue()` 驱动；点中才激活（`.search-on` 金边），回车 / ESC / `[← 返回]` / 进出分类都置 `NONE`（`client/structure/StructureDebugKeybind`、`StructureSearchInput`）；③ 行 id 用 `side-npc-{index}` / `side-bld-{index}`，点击后**整表重渲染**（选中态由 Java 的 `selectedId` 决定，不在 DOM 上手工切 class）；④ **新行不必改名去凑前缀** —— 它们都挂在 `#viewer-page` 之下，`insideViewer` 向上遍历祖先时会撞上 `viewer-` 前缀而返回 true，世界点击自然不会同时触发。⚠ **待实测**：两个搜索框来回切换焦点后，字符是否只进当前那一个 |

| 60 | 想把主题原样搬进 AUI 时，容易照 §2 禁用清单把 `transform` / `transition` / `@keyframes` / `@media` / `calc()` **一起砍掉** | ⚠ **先量一眼官方主题**：AUI 自带的 `ore.css`（3067 行）自己就在用 `calc(` ×9、`transform` ×41、`transition` ×17、`@keyframes` ×5、`@media` ×3、`::after` ×49、`flex-wrap` ×5。⇒ §2 那批清单针对的是**"会让客户端卡在测量循环里"**的那几条（`flex-wrap`、`align-items:baseline`、`em`/`%` 字距、`flex:1` 的 0% 解析），**不是"禁止出现"**：纯 DOM 页里写了顶多**退化**（动画变瞬时、变换不生效、伪元素不画），**不会崩** | 判据是"**这页会不会进测量循环**"，不是"字符串里有没有 transform"。主题类页面真有**必须**改的只有三处：① `@font-face` 的 CDN 换 `/font/zpix.ttf`（AUI 内无网络语义）；② `theme.js` 在 AUI 里跑不了 ⇒ `var(--tex-*)` 得指向预生成的 PNG（`/tex/*.png`，与 §5 对照表一致）；③ `body` 的深色底与 `min-height:100vh` 要清掉（§0 铁律 5）。另注意主题里靠 `aspect-ratio`（`.slot`）与百分比宽度（`.bar`）表达的尺寸，落进 AUI 页面时得改成显式 px 或由 Java 现算 |
| 61 | 世界道路页（`ApricityScreen` + 全屏 `<world-map>`）打开时按 **C**，Screen 里的撤回正常执行，但**领地道路的撤销栈也被误弹一次**；按 **Enter** 同样误弹领地道路的确认窗——一次按键，两处反应 | `InputEvent.Key` **没有** `screen != null` 前置：项目全局监听 `RtsClientEvents#onKey`（`InputEvent.Key`）在 Screen 打开时**照样触发**，而它早前的道路分支写的是 `isRoadMode()`（`ROAD_WORLD` 也算）⇒ 全局侧与 `Screen#keyPressed` 各跑一遍。同类的 `onMouseButton` / `onMouseScroll` **有** `minecraft.screen != null` 前置所以没事——三条事件的前置条件不一致，是最容易漏的点。这与坑 58 是一对：**HUD 覆盖层收不到字符事件，而 Screen 页的按键会被外部全局监听重复处理** | 全局监听里凡是"某 AUI 页专属"的分支，判定收窄到**具体子模式/页面**（`getSubMode() == ROAD_TERRITORY` 而不是 `isRoadMode()`）；Screen 页内的按键处理完 **`return true` 消费**（`WorldRoadScreen#keyPressed`），但注意 `return true` 只挡 vanilla 后续，**挡不住同 tick 里早已跑过的 `InputEvent.Key` 监听**——所以收窄必须做在全局侧。另：`<world-map>` 这类 `Canvas` 子元素被两个页复用（市政厅 + 世界道路）时，右键语义按"模式开关"分流（`roadMode` static，进页开、`onClose` 关），交互状态（`selecting` / `roadDrawing`）保持 static 与既有先例一致，但**每次只可能有一个页在用**，不要在两个页同时开模式 |
| 62 | 需要"按住头像拖到卡片上指派"这类拖拽交互时，容易以为 AUI 做不了、打算去 Java `Screen#mouseDragged` 里拦鼠标再手工换算两套坐标系 | **AUI 原生三件套齐全**，不用 Screen 层：① `Element#getBoundingClientRect()` 返回 `DOMRect`（public 字段 x/y/width/height/left/top/right/bottom，`Element.java:2174-2185`）；② `document.addEventListener("mousemove"/"mouseup", …)`（`Document.java:1049` 委托 body；mousemove 由 `Client` 的 `RenderFrameEvent.Pre` 全局派发，**拖出元素范围也持续收到**）；③ AUI 自己的可拖窗 `DialogWindow`（mousedown 记起点 → mousemove 改 x/y）与 `ColorPicker#bindDrag` 就是现成范本 | 照抄 `ColorPicker` 结构：拖起元素 `addEventListener("mousedown")` 记源数据 → `document` 级 `mousemove` 更新 ghost（`position:absolute` + `setAttribute("style","display:block;left:Xpx;top:Ypx")`）→ `mouseup` 里用各目标卡 `getBoundingClientRect()` 与**文档坐标**比对投放。**坐标必须过 `document.screenToDocumentPosition(new Position(e.clientX, e.clientY))`**（fixed+fit+居中下 GUI 坐标≠文档坐标，§13.9），漏了这步 ghost 与热区会错位。拖动源节点加半透明类反馈；`setInnerHTML` 重建列表后拖拽监听也要重绑（坑 51）。落地：`client/builder/BuilderBoardScreen`（建筑工坊头像拖拽指派）+ `ui/builder.html` 的 `#bb-ghost` |
| 63 | 纯 DOM 页（无真实容器菜单）要显示物品图标时，以为要 `<slot>` 或 `<img src="item:...">` | AUI 没有 `item:` URI；物品图标是 `<item>` 自定义元素（`targets/neoforge-1.21.1/.../element/Item.java`，TAG_NAME=`ITEM`），**文本内容**会被 `ItemStackExpressionCompiler` 解析（同目录 `ItemStackExpressionCompiler.java:23-53`）：直接写 `minecraft:dirt`，或 SNBT `minecraft:oak_planks{Count:3b}` 表达数量；Java 侧也可 `getElementById(...) instanceof Item item → item.setIngredientStack(stack)` | 直接 `<item class="bb-mat-icon">minecraft:dirt</item>`，并按坑 38 给 class 写 `position:absolute;left:0;top:0` + 宽高铺满父盒（父盒固定 px + `position:relative`）。数量建议用旁边的文本行表达，别依赖 SNBT Count 的角标渲染 |
| 64 | 新页面要渲染 NPC 头像时另起炉灶写画布/截图逻辑 | 项目里已有现成元素：`<kingdom-entity-avatar skin="…">`（`client/aui/KingdomEntityAvatarElement`，`@ElementRegister`），`skin` 来自服务端 `NpcData#getSkin`，按内容尺寸最近邻放大画 8×8 脸（`identify.html` L76-97 是"头像+边框"的标准先例） | 照抄 identify.html 配方：外层固定尺寸 div（`border:2px solid #4a3218` + 双层 inset `box-shadow` + `/tex/parchment_avatar.png` 底）做边框，内层 `<kingdom-entity-avatar class="…" skin="…">` 显式 `width/height`；动态列表里由 Java 拼 HTML（`CityHallScreen#citizensHtml` 同款），skin 走状态包字段 |
| 65 | `<world-map>`（Canvas 元素）**缩放/拖动时像素糊成一坨**：放大是颜色互相渗透的渐变糊，截图放大也没有锐利像素 | `WorldMapElement#drawCanvas` 调 `ImageDrawer.drawWithUvWindow(..., blur=true, ...)`。`blur` 直接决定 `TextureStateShard` 的采样模式（`ImageDrawer.getRenderHandle` → `smoothRenderType(location, blur, depthTest)`）：`true`=线性采样，GPU 在相邻格颜色间插值——把 1px/格 的地图光栅缩放时"融合"了。§5.2 早就定过"三条路径都 `blur=false`"，但自定义 Canvas 元素自己调 `drawWithUvWindow` 时第 6 参传了 `true`，绕过了那条检查表 | 自定义元素用 `ImageDrawer` 画任何"逐像素数据"（地图/画布/像素图）一律 `blur=false`（最近邻）。规则本体的完整表述是：**AUI 里所有像素级内容默认最近邻，`blur=true` 只属于照片类贴图的显式选择**（§5.2、§15.4） |

### 11.B AUI 侧行为造成的坑（只列现象与绕过，证据见 §16）

| 坑 # | 现象 | §16 对应 | 绕过 |
|---|---|---|---|
| 6 | 槽位完全没渲染、边框也没有 | D13 | 别写 `--aui-slot-render-bg`，用 `render` 系开关（§7.10） |
| 8 / 20 | 分栏比例反了 / 塌成单列通栏 | D5 | 分栏用 flex + 显式 px |
| 10 | 日志成片刷 `Depth formats do not match` | D22 | 已由 `AuiFabulousStencilMixin` 掐掉那次对账 |
| 17 / 18 | 一右键 NPC 就卡死（布局期，安全模式救不回） | D1 + D2 | 页面一处 `flex-wrap` 都不留 + 两个测量缓存 mixin（§7.8） |
| 19 | 槽位格子粘死/被拉宽 | D4 | 横排间距用 `margin`（或固定列 `grid` 的 `gap`） |
| 21 | 卡片高度失控、内容被拉爆 | D6 | 写成设计宽度下的精确 `height` |
| 22 / 23 | 文档靠左不居中 / 只改偏移还不够 | D8 | 输入映射 + 画面平移两侧都改（§13.6） |
| 26 | 居中后 `overflow:hidden` 区域被切割 | D9 | `AuiScissorOffsetMixin` |
| 27 | 槽位渲染与点击热区错位、点不到 | D10 | `AuiSlotMenuOffsetMixin` |
| 28 | 点击热区只有渲染格子的 1/2.7 | D14 | 槽位 class 写 `--aui-slot-size`（§7.11） |
| 30 | tooltip / 伪光标整体偏移 | D12 | 平移在 `@At("RETURN")` 成对收回（§13.6） |
| 31 | 图标 16px 缩在格子左上角 | D15 | `<item>` 铺满 + `--aui-slot-icon-scale`（§7.12） |
| 32 | 整个容器界面都点不动 | D17 | 容器页不写 `aui-mouse-events=intercept` |
| 33 | 浮动物品变小/上偏、数量飞走 | D16 | `AuiFloatingItemScaleMixin` |
| 34 | 空流页面根元素写 `overflow` ⇒ 整页静默不画 | D23 | 空流文档根元素不写 `overflow`（§4.4） |
| 35 | 世界空间"按角度被剔除" / 只在固定角度可见 | D24 + D25 | 世界窗口 `setFacing(true)`；剔除补偿见 §16.6 |
| 36 | 覆盖层 HUD 被原版聊天栏压住，抬 `Base.pushDocumentZOffset` **无效** | `drawFlatDocumentAtZInContext` 用 `resolveFlatDocumentBaseZ(document)` 覆盖传入值（`Base:159-175`）⇒ 扁平文档面恒为 `GLOBAL_DOCUMENT_Z_OFFSET(1.0)+层序号`；只有元素级 z 才真的抬高 | 给页面里有确定尺寸的容器加 `transform:translateZ(400px)`（§4.4；`structures/viewer.html` 已用） |

### 字体与元素

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | 图标/纹章整块空白 | 选的符号在 Zpix 里没有字形（16 个里 14 个 False） | 换成实测存在的符号（§6.2） |
| 2 | "立国建邦"弹窗只剩两个按钮 | `.modal{display:none}` 之后**没有任何规则把它打开** | 补 `.overlay.open .modal{display:block}`（后代与子选择器各写一条） |
| 3 | 弹窗/信封不居中、错位 | `position:fixed` + `left:50%` + 负 margin + 百分比定位 | 父级 `display:flex;justify-content:center`，弹窗回到文档流 |
| 4 | 质感与边缘全丢 | 参考稿的颗粒/边缘是 JS 程序化贴图，AUI 跑不了 | 用 `tex/` 贴图 + `border` + 四层 `box-shadow`（§5） |

### 布局与尺寸

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 5 | 像素感消失、整体发虚 | ① `scale=fit` 算出 0.65 倍；② AUI 按页面路径记住了 0.9 倍缩放（`ApricityViewport#readStoredZoom`） | 钉死 `zoom=1,min-zoom=1,max-zoom=1`；普通页不用 `fit`；`Ctrl+0` 重置历史缩放（§3.3） |
| 7 | 整页巨大、只剩一角、多出滚动条 | 卡片写死 720×540，而逻辑视口只有 543×284（GUI Scale 2） | 普通页尺寸由 Java 按 `Document.getViewportSize()` 算（§4.3） |
| 8 | 左右两列比例反了 | `grid-template-columns:1fr 2fr` 在 AUI 下分配与预期不符 | 改 flex + 显式 px 宽 |
| 9 | 卡片不居中 | 依赖 flex 居中的默认行为 | 由 Java 写显式 `margin` 居中 |
| 15 | 边框外多出一圈背景 | 元素背景/贴图溢出 | 卡片与面板加 `overflow:hidden` |
| 16 | 改了页面游戏里没变 | 读的是 `build/resources/main` 下的副本 | 同步或走一次 `processResources` |
| 19 | 槽位格子粘在一起、被拉宽 | AUI flex 的 `gap` 横排不生效（竖排生效，行为不一致），格子被拉伸填满整行 | 间距改 `margin-right/bottom`，`flex:0 0 auto` 锁死尺寸（§13.5） |
| 20 | 左右分栏塌成单列通栏、另一栏被挤走 | AUI 的 grid `fr` 分栏不可靠 | flex + 显式 px：数值取原 grid 比例的精确值（§13.4） |
| 21 | 卡片高度失控、内容被拉爆 | AUI 不实现 `aspect-ratio` | 写成设计宽度下的精确 `height`（4:3 @1080 → 810px，§13.4） |
| 29 | 行囊（玩家背包）面板比卡片还宽，槽位挤在左侧、右半截全是空白 | 面板宽跟着卡片写死 `1080px`，而 AUI 的 block/flex 不会按内容收缩：`.slot-row` 是块级、槽位一行靠左堆叠，多出来的宽度就空在那里 | 面板宽按槽位数反推：`9×48 + 8×8 + 2×16 = 528`，`#player` 定宽 `496`；间距交给 `grid` 的 `gap`；居中交给 body 的 `align-items:center`（§13.8） |

### 槽位与交互

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 6 | 槽位完全没渲染、边框也没有 | 槽位上写了 `--aui-slot-render-bg:0`，AUI 把背景 + 阴影 + 边框三个阶段全跳过（`Slot#shouldRenderBackground` → `drawPhase`） | 删掉该属性，槽位用自身 CSS 画（§7.2、§7.10） |
| 28 | 点击热区只有渲染格子的 1/2.7（点格子中心没反应） | UA 样式表给所有 slot 默认 `--aui-slot-size:18`，而它是 `Slot#resolveSlotSizeHint` 的**第一优先级**，盖过 computed width/height 和 `slot-size` 属性 | 在槽位 class 里显式覆盖：`.slot{--aui-slot-size:74}`（无单位），行囊 48 同理（§7.11） |
| 31 | 物品图标严重小于渲染的格子（格子 74/48px，图标只有 16px，且缩在格子左上角） | ① UA 把 `item` 定死 `16×16`、`slot > item{left:1px;top:1px}`；② AUI 的物品居中是拿 **`<item>` 元素自己的矩形**算的（`RenderNode.ItemNode` 的 `centeredBodyX/centeredBodyY` → `Rect.of(item)`，再按固定 16px 居中）；③ 放大只有 `--aui-slot-icon-scale`（`Item#resolveIconScale` → `Slot#resolveIconScale`）一条路 | 两步成对做：① 给每个 `<item>` 加 class（`.slot-icon`），写 `position:absolute;left:0;top:0;width/height = 槽位px` 铺满槽位（**别用 `slot > item` 标签选择器**，靠 class 才稳）；② 槽位 class 里给 `--aui-slot-icon-scale`。取值看槽位贴图内框：`slot_gen.png` 32×32、四边各 3px 边框 → `内框 ≈ 槽位px − 2×(3/32×槽位px)`，目标宽 `≈ 内框 − 2px`，`iconScale ≈ 目标宽 ÷ 16`（74px → 3.5 = 56px；48px → 2.25 = 36px）。只铺满不放大 = 图标居中但很小；只放大不铺满 = 以格子左上角为中心放大、撑出格子 |
| 32 | **整个容器界面都点不动**：槽位看得见、悬停高亮和 tooltip 都正常，就是拿不起/放不下物品（两个容器都一样） | 页面写了 `<meta name="aui-mouse-events" content="intercept">`。官方文档明说 intercept「让命中区域的鼠标事件不再传给下方的 Minecraft 输入」，源码侧印证：`Document#interceptsMouseEventsAt` = `interceptMouseEvents && hitTest(screenToDocumentPosition(pos)) != null`，`Client#mouseButton` 里 `if (nativeConsumed) event.setCanceled(true)` 取消掉 NeoForge 的 `InputEvent.MouseButton.Pre`。而容器槽位取放走的是原版菜单 `AbstractContainerScreen#mouseClicked`，原生点击被吃掉后**永远收不到**；悬停高亮/tooltip 是 render 阶段独立算的（`SlotDataBinder#syncBoundSlotHoverStates`、`ApricityContainerScreen#drawSlotHoverTooltipByElement`），所以照常显示 | 容器页（有真实槽位）**坚决不写 intercept**：槽位本身 `pointer-events:none`、交互全走 MC 菜单，没有需要 DOM 吃点击的元素。只有纯 DOM 交互页才写（本项目 `start.html`）。另确认 Ctrl+滚轮缩放不依赖它：`Client#findViewportZoomTargetAtMouse` 只要求 `hitTest` 命中 |

### 渲染与居中

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 10 | 日志成片刷 `Depth formats do not match` | AUI 每 tick 无条件调用 `RenderService#reconcileFabulousChainStencil`：先用原版 `enableStencil()` 给主目标开 stencil（24S），再手动把 Fabulous 五个链目标重分配成 32F，两侧格式不一致 → MC 每帧深度拷贝 `GL_INVALID_OPERATION`（AUI 自身问题，与本模组渲染无关） | 已由 `AuiFabulousStencilMixin` 掐掉那次对账（链保持原版格式，与主目标一致）；实体绘制仍建议用独立即时缓冲（§8.3） |
| 11 | 模型头顶被裁在框外 | 缩放按 100% 框高、脚底按 88% 放 | 缩放 0.74、脚底 0.94（§8.3） |
| 12 | 刚打开瞬间被放大、随即跳回 | 版式在打开后的第一个 tick 才套用 | `ScreenEvent.Init.Post` 里立刻套用（§9.3） |
| 13 | 身份卡饥饿永远"无" | 卡片是纯客户端拼的，饥饿只存在于服务端 `NpcData` | `SynchedEntityData` 同步（§9.4） |
| 14 | 滚轮会滚动/缩放页面 | AUI 允许滚动的开关是 `overflow-y`（`Interaction#allowsUserScroll`） | 只在有确定尺寸的块上写 `overflow-y:auto`；**根节点写了会整页不渲染**（§4.4） |
| 22 | 文档完全靠左不居中 | `Document#applyViewport` 把屏幕文档变换偏移写死 `(0,0)`，`fixed+scale` 缩小后的文档钉在左上角 | 居中 mixin：`AuiDocumentCenterMixin` + `AuiContainerDocCenterMixin`，两份偏移必须一致（§10、§13.6） |
| 23 | 只改偏移、画面还是不居中 | 渲染路径不消费 `viewportOffset`（只喂给坐标换算） | 输入与渲染两侧都要改：一个 `setViewportTransform`、一个 `pose.translate`（§13.6） |
| 24 | 文档区域整块发黑 | body 的深色底（原稿给浏览器用的）涂满整个文档矩形 | body 背景改 `transparent`，让容器屏自带的压暗与世界透出来（§13.7） |
| 25 | 3D 模型变成侧面"柱子" | 自定义元素里鼠标用 GUI 坐标、元素位置用文档坐标——`mode=gui` 下两者重合掩盖了 bug | 鼠标先过 `document.screenToDocumentPosition(...)` 再参与计算（§13.9） |
| 26 | 居中后带 `overflow:hidden` 的区域被切割/内容被遮 | 裁剪框换算在 `Mask#applyScissor` → `Mask#enableScissor(x,y,w,h)`，只乘 `getScissorScale(window)`，**不消费** `pushScissorScale` 快照里的基础平移 `b30/b31`；而 surface 路径（`Mask.SurfaceScissorTransform#apply`）用的是 surface 自己的 `offsetX/offsetY`（也**不是** b30/b31）——两条路机制不同，普通路径完全没有位移概念 | `AuiScissorOffsetMixin`：`@ModifyVariable` 改 `Mask#enableScissor` 的 x/y 入参，补 `documentToGuiPosition(0,0) ÷ renderScale`（GUI 偏移换算成 CSS 单位），用 `Document.getContextDocument()` 取当前文档；gui 模式/世界窗口偏移为 0，无影响（§13.10） |
| 27 | 容器屏槽位渲染与点击热区严重错位、完全点不到 | `SlotDataBinder#syncAllSlotPositions` 写菜单槽位坐标用 `round(doc.x × viewportScaleX) − leftPos`，不消费绘制偏移——DOM 画面居中了，物品渲染与原版式点击判定的坐标没跟 | `AuiSlotMenuOffsetMixin`：`syncAllSlotPositions` RETURN 处给菜单槽位 `slot.x/y += 偏移`（§13.10） |
| 30 | **对准某个物品，tooltip / 伪光标整体偏出去一大截**（偏移量正好等于文档居中偏移） | `AuiContainerDocCenterMixin` 原先把居中平移注在 `drawLinkedDocument` 的 `@At("HEAD")`——那在 AUI 自己 `pushPose()` **之外**，它内部的 `popPose()` 只还原到"平移之后"，于是平移**泄漏**给同一帧后续所有绘制：`drawSlotHoverTooltipByElement`（tooltip）、`Client#drawPersistentScreenDocuments`、`Cursor#drawPseudoCursor`、帧计时 HUD | 平移仍在 HEAD 施加（矩阵净效果 `T(偏移)∘S(缩放)`、scissor 基底 `b30/b31` 都不变），但**在 `@At("RETURN")` 原样抵消**，成对收走。没采用"注到 `pushPose()` 之后"的写法：`pushPose()` 的返回类型在版本间变过（`PoseStack` / `void`），描述符写错会在加载期直接报错。**改这条必须编译** |
| 33 | **点起物品后跟随光标的图标突然变小**（槽位里是放大过的，一拿到手上就缩回 16px）；放大后又出现**图标整体往上偏移、数量数字飞到更上面** | `ApricityContainerScreen#captureFloatingItem` 写死 `RenderNode.ItemNode.positioned(..., 1.0D, 232, ...)`。放大时还有两处几何要配套：① `ItemNode#render` 的缩放绕局部 `(8,8)`（图标中心——`ItemRenderService#render` 就是 `translate(8,8); scale(16,-16,16)` 画的），不动绘制点的话图标以中心为轴上下各长 `8×(scale−1)`；② 装饰位移 `decorationOffsetY` 是在**放大后的 pose** 里 `translate` 的（`ItemRenderService#drawDecorations`），原样传会被再乘一遍 `scale`（3.5 × −16.45 ≈ −57.6 文档像素）→ 数量数字飞到图标上方很远 | kingdom 侧 `AuiFloatingItemScaleMixin`：capture 的 `@At("TAIL")` 丢掉 AUI 刚加的 1 倍节点，换成补偿过的节点——**绘制点右下移 `8×(scale−1)`**（保持 1 倍时的左上角锚点、只往右下长）、**`decorationOffsetY` 除以 `scale`**。倍数只在"拿起的那一帧"按光标下槽位定标（`ApricityContainerScreen#findSlotIndexAt` → `SlotDataBinder#getBoundElement` → `Slot#resolveIconScale`），整个拖拽不变；`render` 开头 `carried` 为空时清零；取不到绑定槽位兜底 3.5。`scale==1` 时两个补偿都归零，节点与 AUI 原样逐参数一致 |

### 世界窗口 / 遮罩（AUI 侧）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 34 | **空流页面（HUD / overlay / 建筑预览栏）根元素写 `overflow` → 整页一像素不画，且零日志零报错** | `Drawer` 对 `Interaction#clipsOverflow` 为真的元素生成 `MaskPushNode` → `Mask#pushMask` 把 `currentClip` 与该元素矩形求交；根元素盒子高度为 0（内容全是 `position:fixed`）⇒ 交集不满足 `AABB#isValid()` ⇒ `RenderNode` 的 5 处 `!currentClip.isValid()` 早退把整页跳过。**注意它不是被修好了**：条件不成立时（根盒子非零，如 `backpack.html` 根上就有 `overflow-x/y:hidden`）完全不触发 | 根元素不写 `overflow`（禁滚动什么都不用写）；需要裁剪写到有确定尺寸的容器上（§4.4） |
| 35 | **世界空间里"按角度被剔除"（世界窗口/世界变换下的内容整块消失）** | AUI 自己的注释自证：世界窗口的文档裁剪 "**used to be only a logical culling rectangle**"——旧版只用一条逻辑剔除矩形，世界变换（旋转/朝向）会让它与真实可见区域不一致，于是内容被逻辑剔除；现在改成 `Mask.pushForceStencil()` + `pushMask(…, forceStencil=true)` 做**真像素 stencil 裁剪**。另外：建筑预览期我们抑制了 stencil（`MaskStencilSuppressMixin` + `KingdomAuiStencil`）⇒ 这条 forced 遮罩走 `MaskMode.NONE`（`clipStack`/`currentClip` 交集照做，但不 `beginStencilIfNeeded`、不 `setStencilFunc(GL_EQUAL,…)`）⇒ 不再有 stencil 剔除，同时**失去真像素裁剪** | 不需要动；若见到"窗口内容溢出到四边形之外"，那就是强制遮罩退成 NONE 的代价（§16 的 D24） |

### 性能

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 17 | 一右键 NPC 就卡死、连日志都没有 | 页面里有 `flex-wrap`，两处都实测卡死：① 槽位容器的 wrap 被"60 次逐槽位求位置"放大；② 文字容器（`.bio-grid`）的 wrap 把"文字测量 ↔ 父尺寸 ↔ 滚动条槽宽 ↔ 计算样式"按行数放大 | 页面上一处 wrap 都不留：槽位用显式行 / 固定列 grid，档案项纵向排列（§7.6）。报告里能看到 `Flex.computeWrappedRowLayout` 与 `Text.of` |
| 18 | 看门狗 + 安全模式都救不回来 | 卡死在 `ApricityContainerScreen.init()` 的布局阶段，还没轮到元素绘制 | 安全模式只治绘制期卡死；布局期要靠 §7.6 那种结构性修法 + §7.8 的缓存 mixin |
| 65 | 建筑操作面板（农田/牧场/工厂/建筑工/住宅）一打开就掉帧甚至卡死，市民越多越明显，切分类或搜索一次更卡 | 各面板把**整份市民名册**一次拼成 HTML 塞进 `setInnerHTML`，每行还带一个 `<kingdom-entity-avatar>`（`Canvas` 子类 ⇒ 各自一张 `BufferedImage` + 一张纹理）。200 人就 200 个 Canvas 同帧建面上传；搜索/切分类/每次状态包都会整表重建一遍 | 分批渲染：`client/aui/AuiLazyList`——总数 ≤200 时**一次全部建成**（不走滚动加载）；>200 时首批 200、滚到底再用 `document.createHTML` + `appendChild` 追加 200，`rendered == total` 就彻底停（继续往下滚也不会再加）（**不重设 `innerHTML`**，否则整表重建、越滚越卡）。AUI 原生够用、**不需要 mixin**：`Element#getScrollTop()`（`init/Element.java:684`）、`getClientHeight()`（`:2291`）、`scrollHeight`（`:145`）；`scroll` 事件由 `Element#dispatchScrollEventIfChanged`（`:2453`）派发，触发点在 `ScrollModel:197/694` 与 `MouseEvent:304`。`.bp-list` 本来就是 `flex:1 1 auto; min-height:0; overflow-y:auto`，直接可滚（1.2.8 另有 `Window#IntersectionObserver`，但构造器 private、回调是 JS 侧 `Consumer<Object>`，Java 侧不如 scroll 事件直接）。**别只拿 `scrollHeight` 判到底**：AUI 下它可能滞后或恒为 0（追加的行要等下一次布局提交才计入），只按 `scrollTop + clientHeight >= scrollHeight - 64` 会卡死在首批；`AuiLazyList` 因此叠了三重判断——到底 64px 内 / 滚过 3/4 屏 / `scrollHeight` 不可用时按 30px 行高估着补满 1.25 屏 |
| 66 | 建筑面板详情页里"可雇佣 NPC 名册"只露一行，不缩小 HTML 根本看不到列表 | 详情页 `.bp-body` 是 column flex，而 `.bp-block{flex:1 1 0}` 让"分配格数 / 种植作物 / 打理人"三块**平分**剩余高度；每块再扣掉 `.bp-sec`(~20px) + `.bp-search`(~26px) + gap(8) ⇒ 列表只剩 ~30px，正好一行。AUI 对 `flex:1 1 0` 一律按 0 基准平分，不会按内容让列表块多拿 | ① 内容固定的块（分配格数、床位）加 `.bp-block-fixed{flex:0 0 auto}`，退出平分；② NPC 名册块加 `.bp-block-people{flex:2 1 0;min-height:118px}` + `.bp-block-people .bp-list{min-height:74px}`（双份 + 保底 3 行）。只改 farm/ranch/industry/residential 四页，`.bp-block` 本体不动，其它页版式不变 |
| 67 | 分批列表"越滚越多"：同一批 NPC 反复出现，滚到底又叠一遍（只有 10 个 NPC 也能堆出几十行） | "`setInnerHTML("")` 清空 + `appendChild` 追加"这套混合模型不可靠：只要清空与追加之间对不上一次（子节点移动/样式重算都会插进来），账面 `rendered` 就与真实 DOM 行数脱钩，之后每次追加都叠在旧行上；行 id（`fd-worker-0..n`）重复后 `getElementById` 还会绑到第一批旧行上，于是"重复行也能点击提交"。参考 `Element#setInnerHTML`（`init/Element.java:1755`）：它先逐个 `removeChild` 再把 wrapper 的孩子 `appendChild` 过来，两步之间的任何异常都会让账面与 DOM 分岔 | ① 清空改成逐个 `removeChild`（`clearHost`），不依赖 `setInnerHTML("")`；② 每次追加前校验 `host.childNodes.size() == rendered`，不等就按 `rendered` 全量重建（自愈）；③ 名册先按 UUID 去重（`LinkedHashMap.putIfAbsent`）再进列表；④ 行数上限 200 兜底（`AuiLazyList.limit()`） |
| 68 | 行装页加到 30 槽后，新加的 6 个装备格点不动、图标也不刷新 | ① AUI 按 `<container size>` 建绑定表，`size` 与 Java 侧 `NpcInventory.SLOT_COUNT` 不一致时，多出来的 `<slot>` 没有绑定目标（热区空、图标不更新）；② 旧存档 SNBT 里写着 `Size:24`，`ItemStackHandler#deserializeNBT` 会**按存档 Size 重设容量**，把 30 槽压回 24，装备格直接消失 | ① `size` 与 `SLOT_COUNT` 同源（都读 `NpcInventory.SLOT_COUNT`），不要写字面量；② 覆写 `deserializeNBT(provider, tag)`，先 `tag.copy().putInt("Size", SLOT_COUNT)` 再交给父类；③ 装备格沿用 `.slot` 的 `--aui-slot-size`，只用 `.slot--equip` 改底色，不引入第二种槽位尺寸（§7 强制） |
| 69 | **1.2.9 起玩家背包只渲染第一列**，后面 8 列全丢（36 格变成 1 列往下堆） | `grid-template-columns:9` 这种裸整数写法在 1.2.9 被判为非法轨道列表（`ShorthandParser.isValidGridTrackList`），整条声明被丢弃 → `display:grid` 退化成单列。1.2.5 时代它被当作"列数"扩展解析，所以老页面看起来正常 | 改成真实轨道列表：`grid-template-columns:repeat(9,48px)`（1.2.9 的 `Grid.java` 支持 `repeat()`）。**同理，页面里任何 `grid-template-columns:<整数>` 都要换掉**；`ContainerExpander.injectDefaultColumnsIfNeeded` 注入的也是整数形式，所以容器别依赖自动列数，自己写 `repeat()`。**已修**：`ui/backpack.html`（`repeat(9,48px)`）、`buildings/shelf.html`（`.slot-grid` 同样改 `repeat(9,48px)`，货架本体 `#block_entity` 用 `repeat(3,48px)` 排成 3×3） |

---

## 12. 新页面 checklist

**viewport / 坐标系**
- [ ] `viewport` meta：普通页 `mode=gui`；世界窗口 `mode=fixed,width,height,scale=1`；**定稿整稿页**（`ApricityScreen` / 容器屏）`mode=fixed,width,height,scale=fit`（§3.2 的警告、§11.A 坑 50）。**不要写固定数字倍率**
- [ ] 定稿页把 `zoom` 钉死（`zoom=1,min-zoom=1,max-zoom=1,user-scalable=false`）：`fit` 下 `zoom` 只改逻辑坐标精度、不改实际显示大小
- [ ] **有真实槽位的页面不写 `aui-mouse-events=intercept`**（§11 坑 32）；纯 DOM 交互页要写

**布局**
- [ ] 普通页尺寸由 Java 按 `getViewportSize()` 算，整数 px；整稿页写死在设计坐标系里但必须装得进视口
- [ ] 只用 flex + 显式 px；不用 `fr`、`%`/`calc`/`vw`/`aspect-ratio`/`position:fixed`
- [ ] 槽位阵列用**显式行**或**固定列 `grid`**（`display:grid; grid-template-columns:<整数列数>; gap`，AUI `preset:player` 与 `ContainerExpander#injectDefaultColumnsIfNeeded` 的同一套机制）
- [ ] **容器页任何地方都不用 `flex-wrap`**（§7.6）
- [ ] 容器页回避：`align-items:baseline`、`text-indent`/`letter-spacing` 用相对单位、`flex:1`/`flex-basis:0%`、任何带 `em/%` 的尺寸（§7.7）
- [ ] flex 子项间距用 margin 不用横排 `gap`；子项写 `flex:0 0 auto` 防拉伸（§13.5）
- [ ] 需要滚动就只在目标块开 `overflow-y:auto`，容器页整页 `hidden`

**槽位**
- [ ] `<slot>` 是 `<container>` 的直接子元素，`slot-index` 正确；玩家容器按 `0..8` 快捷栏在**最后一行**排
- [ ] 槽位 class 里写 `--aui-slot-size`（否则热区是 18px，§11 坑 28）
- [ ] **不写** `--aui-slot-render-bg`；要单独关背景/物品用 `render`/`render-bg`/`render-item`（§7.2、§7.10）
- [ ] 槽位里的 `<item>` 加 class 并铺满槽位盒子，槽位 class 里给 `--aui-slot-icon-scale`（§11 坑 31）
- [ ] 图标放大后，跟随光标的浮动物品也要同比（`AuiFloatingItemScaleMixin`，§11 坑 33）
- [ ] 服务端：`new PendingMenu(player, page).bind(...)`，绑定顺序定 primary；实体物品能力已注册（§7.3、§7.4）

**交互 / 拖拽**
- [ ] 拖拽走 AUI 原生：`mousedown`（源）+ `document` 级 `mousemove/mouseup` + ghost `position:absolute` 跟手，坐标过 `screenToDocumentPosition`，投放命中用 `getBoundingClientRect()`（§11.A 坑 62）
- [ ] 动态重建的行/芯片每次 `setInnerHTML` 后重绑全部监听（坑 51）；物品图标用文本内容 `<item>` 并铺满父盒（坑 63）；NPC 头像用 `<kingdom-entity-avatar skin>` + 外框 div（坑 64）

**质感 / 字体**
- [ ] 贴图按目标显示尺寸 1:1（或整数倍）；背景靠默认最近邻（`blur=false`），不写 `blur="true"`、不用 `transform`/`filter`
- [ ] 硬边用 `border` + 多层 `box-shadow`，容器加 `overflow:hidden`
- [ ] 所有符号/装饰字符先用 GlyphTypeface 验一遍字形（§6.1）

**宿主 / 数据**
- [ ] 文案/数值全部由 Java 填，页面不留假数据；只存在于服务端的属性走 `SynchedEntityData`
- [ ] 容器屏在 `ScreenEvent.Init.Post` 里立即套用版式（§9.3）
- [ ] 重绘型自定义元素用 `ClientWatchdog.beginPhase/endPhase` 圈起来，并尊重 `isSafeMode()`
- [ ] 每行带重元素（`kingdom-entity-avatar` / `<canvas>` / `<item>`）的长列表用 `AuiLazyList`（≤200 一次全建，>200 每批 200 滚动追加），不要整表 `setInnerHTML`（§11 坑 65）
- [ ] 一个 column flex 面板里有多块抢高度时，**列表块要给 `flex` 权重 + `min-height` 保底**，内容固定的块用 `flex:0 0 auto` 退出平分（§11 坑 66）
- [ ] 分批列表的账面行数要与 DOM 子节点数对得上，并按业务主键去重（同一个 NPC 只进一行）（§11 坑 67）
- [ ] 自定义元素：`@ElementRegister` + 包在扫描范围内；GUI 内画 3D 实体用独立即时缓冲（§8.3）

**居中改造（整稿页才有）**
- [ ] 四件套齐活：`AuiDocumentCenterMixin`（输入映射）、`AuiContainerDocCenterMixin`（画面平移，注在 `pushPose()` **之内**/成对收回）、`AuiScissorOffsetMixin`（裁剪框）、`AuiSlotMenuOffsetMixin`（菜单槽位坐标）（§10、§13.10）
- [ ] 居中平移**不许**注在 `drawLinkedDocument` 的 `@At("HEAD")` 而不收回——会泄漏给同帧后续绘制（§11 坑 30）

**世界窗口 / HUD**
- [ ] 世界窗口：`setFacing(true)`（否则只在正面角度可见，§16 D25）；要 DOM 交互就走**登记**路径（`ApricityUI.createWorldWindow(...)`），不要"不登记还指望 AUI 分发鼠标"（§16.6）
- [ ] 世界窗口渲染**只传 `event.getProjectionMatrix()`**（`P × pose` 才是正确 clip；不要再乘 `event.getModelViewMatrix()`，§11 坑 36）
- [ ] HUD / overlay 这类**空流**页面：根元素不写 `overflow`（§4.4、坑 34）；需要裁剪写到有确定尺寸的容器上
- [ ] 出现"按角度整块消失"：先查 `isFacingEnabled()`，再查 §16.6 的剔除补偿

**收尾**
- [ ] 同步到 `build/resources/main`；启动后看日志里自打的诊断行
- [ ] 改完关键文件立刻回读校验（§10）

---

## 13. 原稿 1:1 还原流程（backpack.html 案例全记录）

> 结论先行：**还原原稿的正确姿势是"原封复制 → 只改 AUI 跑不了的部分"**，不是凭记忆重写。
> 重写必丢细节（本案第一版丢的就是边框体系）。

### 13.1 流程

1. `Copy-Item 原始稿 → ui/页面.html`，**哈希校验逐字节一致**；
2. 逐段读原稿，列出它用到的 AUI 跑不了/会卡死的特性；
3. 只对清单内的特性做等价替换（每处留注释说明原因），CSS 数值能抄一律照抄；
4. 数据节点加 id，由 Java 驱动填值；Java 驱动**不管版式**。

### 13.2 JS 程序化贴图 → 同算法移植

原稿用 JS（fbm 值噪声）生成三张贴图。AUI 跑不了 JS，但算法是纯数学——
**逐行移植成生成脚本**（PowerShell + System.Drawing，`Math.Sin` 与 JS 同为 IEEE double），
产出与浏览器逐像素同源的 PNG：

| 文件 | 尺寸 | 内容 |
|---|---|---|
| `tex/card_gen.png` | 180×135 | 大卡贴图，**含 7px 连续烘焙边框**（7 层色带一次画成） |
| `tex/pane_gen.png` | 80×60 | 面板贴图，无边框（边框来自 box-shadow 环） |
| `tex/slot_gen.png` | 32×32 | 槽位贴图，深色双环 + 凹陷高光（**四边各 3px 边框**，图标缩放的换算依据见 §11 坑 31） |

面板"边框完整连起来"的关键 = 贴图自带烘焙边框 + 原稿同款 `0 0 0 2px→8px` 四色环阴影（四块面板共用一组，逐字照抄）。

### 13.3 viewport：`mode=fixed,width=1200,height=1182,scale=fit`

> **修订（2026-10-09）**：下面记的是当初的定标过程（`scale=0.4864`），**保留作为推导记录**；
> 实际配置已改为 **`scale=fit`**，理由与实测见 §11.A 坑 50——固定数字是绝对倍率，
> 窗口一缩就"不缩放 + 偏移失义"，实测 1920×1080 / GUI Scale 2 下文档已缩放成 583×575、
> 比 540 高的视口还大、底部必被裁；`fit` 后同一视口下文档为 548×540、完整居中。
> 下面那条"整稿定尺页面更推荐固定数字"的取舍**已作废**（当时只算了像素清晰度，没算窗口自适应的账）。

- 设计稿 1080×810 + body 留边（20/32），行囊面板补回后整稿高度 900 → **1182**；
- **`scale=0.4864` = 2×18/74**：74px 的槽位在游戏内 = **36 GUI px = 原版 2 倍**；整页随之 = 原设计 ÷2.06；
- 另一档 `scale=0.2432`（18/74）：槽位恰好等于原版 18px，代价是文字基本不可读；**当前取前者**；
- **`scale=fit` 也能用**，基准是**逻辑视口**（`min(逻辑视口宽 ÷ 1200, 逻辑视口高 ÷ 1182)`，见 §3.2）——
  窗口越大越清晰，但倍率随窗口变（像素密度不固定），整稿定尺的页面更推荐固定数字；
- **用户手动缩放**：Ctrl+滚轮 / Ctrl+= / Ctrl+- / Ctrl+0 由 `ApricityContainerScreen#mouseScrolled`（判 Ctrl）
  → `#handleViewportZoom` → `Document#handleViewportZoom`，成功后会调
  `SlotDataBinder#syncAllSlotPositions(force=true)` 重同步热区；由 meta 的 `user-scalable` 闸着，
  `min-zoom/max-zoom` 钳死时功能失灵。现配 `zoom=1,min-zoom=0.5,max-zoom=2,zoom-step=0.1,user-scalable=true`；
- **垂直偏置**：居中偏移由 `client/aui/AuiDocPlacement.centeredOffset` 统一计算——水平居中、
  垂直居中后整体上移 `UP_BIAS=24`（顶边距保底 `MIN_TOP=8`，不贴顶）。两个居中 mixin 都消费它，改上移量只动这一个文件。

### 13.4 尺寸等价换算表（grid/aspect-ratio 的精确值）

原稿的 grid/aspect-ratio 在 AUI 下不可靠，换成它们**本来就会算出的值**：

| 原稿写法 | AUI 下的等价写法 |
|---|---|
| `grid-template-columns:1fr 2fr`（1036 内宽、缝 16） | flex：左 `width:340px` + 缝 16 + 右 `width:680px` |
| `repeat(8,minmax(0,1fr))` + `aspect-ratio:1/1`（右栏内容宽 648） | 3 个 `.slot-row`（flex），槽位 `width:74px;height:74px`（=(648−7×8)÷8） |
| `repeat(2,minmax(0,1fr))` + `gap:0 22px` | 4 个 `.bio-row`（flex，`gap:22px` 竖排安全），项 `width:313px`（=(648−22)÷2） |
| `aspect-ratio:4/3` @1080 宽 | `height:810px` |
| `9 列玩家背包`（行囊） | `#player{display:grid;grid-template-columns:9;gap:8px;width:496px}`（9×48 + 8×8） |

### 13.5 flex 间距：margin 而非 gap

AUI flex 的 `gap` **行为不一致**（竖排生效、横排实测不生效）。
确定性的写法：子项 `flex:0 0 auto`（不许伸不许缩）+ `margin-right/bottom` 承担间距。
**例外**：`display:grid` 的 `gap` 可用（AUI 自己的 `preset:player` 就靠它）。

### 13.6 居中：AUI 的缺陷 + 我们的 mixin

**缺陷（源码证据）**：
- `Document#applyViewport` 把屏幕文档变换偏移写死 `(0,0)`（`setViewportTransform(renderScale, renderScale, 0, 0)`）——
  缩小的文档被钉在左上角；
- 渲染路径不消费 `viewportOffset`（`ApricityContainerScreen#drawLinkedDocument` 只 `scale` 不 `translate`），
  偏移只喂给点击/悬停的坐标换算。

**修法（两个配套 mixin，缺一会出现"画在中间、点在左上"）**：
- `AuiDocumentCenterMixin` → `Document#applyViewport` RETURN 处 `setViewportTransform(rs, rs, 居中偏移)`：点击/悬停/tooltip 跟着居中；
- `AuiContainerDocCenterMixin` → `drawLinkedDocument` HEAD 处 `pose.translate(居中偏移)`，**并在 RETURN 原样抵消**：
  画面居中，且不泄漏给同帧后续绘制（§11 坑 30）。

安全阀：文档已占满视口（gui/浏览器模式）时不动、世界窗口不参与、全程 try-catch。

### 13.7 背景透明

原稿 body 的深色底（`#0a0604` + 径向渐变）是给浏览器看的；游戏里容器屏自带压暗，
body 必须 `transparent`——否则文档矩形整块发黑，看起来像"部分黑屏"。

### 13.8 行囊（玩家背包）面板：现状与设计依据

- 原稿没有这块，为物品存取按原稿视觉语言（pane 贴图 + 同款边框环）新增 `.pane-player`；
  viewport 高度随之 900 → 1182。
- **面板宽不跟卡片走**：`9×48 + 8×8 + 2×16 = 528`（`#player` 定宽 `496`），正好包住槽位并左右对称，
  居中由 body 的 `align-items:center` 完成（§11 坑 29）。
- 容器侧按官方 `docs/guide/container.md` 对齐两条：
  ① **槽位是 `#player` 的直接子元素**（文档明写"手写 slot 也必须是直接子元素"），不再包 `.slot-row`；
  36 个 slot 靠 `#player{display:grid; grid-template-columns:9; gap:8px}` 排成 4×9——这正是 AUI 自带
  `preset:player` 用的同一套机制（`ContainerExpander#injectDefaultColumnsIfNeeded` 自己也会给容器注入
  `grid-template-columns:<整数>`）。行内 margin 必须清零（`.pane-player .slot{margin-right:0;margin-bottom:0}`），
  否则会被算进列宽/行高。
  ② **DOM 顺序按原版习惯**：背包 `9..35` 三行在上、快捷栏 `0..8` 一行在底——`PlayerInventorySlotOrder`
  就是把 `0..8` 当玩家池的最后 9 个，官方文档也点名"背包位置乱：索引 0-35，别按自然顺序排网格"。
  `slot-index` 始终是该槽在玩家背包里的逻辑索引，与排版顺序无关。
- `#entity`（NPC 行装 24 格）仍是"`.bag-grid` 块级 + 3 个 `.slot-row` 显式行"的写法，槽位嵌在行 div 里。
  实测绑定正常（`findAncestor` 收集，不走直接子元素判定）；若日后要统一成直接子元素，可照 `#player` 那样换 grid。
- **图标尺寸**：槽位内 `<item>` 全部带 `class="slot-icon"` 并铺满槽位盒子（74px / 48px）；
  缩放写在槽位 class 上：`.slot{--aui-slot-icon-scale:3.5}`、`.pane-player .slot{--aui-slot-icon-scale:2.25}`（§11 坑 31）。
- **浮动物品**同比放大（`AuiFloatingItemScaleMixin`，§11 坑 33）。

### 13.9 自定义元素里的坐标空间（混用即出鬼）

元素内部拿到的一切几何（`Rect.of(this)`、`Position.of(this)`）都是**文档坐标**；
而 `mouseHandler.xpos()`、`GuiGraphics` 鼠标是 **GUI 坐标**。
`mode=gui` 且无缩放偏移时两者恰好相等，bug 被掩盖；一旦"fixed + 缩放 + 居中"，两空间差一个缩放 + 偏移，
所有混用的数学全错。**统一过 `document.screenToDocumentPosition(...)` / `documentToGuiPosition(...)`。**
案例：模型转头角混用坐标 → NPC 转成侧面（§11 坑 25）。

### 13.10 裁剪框与菜单槽位坐标不跟随文档偏移（AUI 缺陷 + mixin）

**裁剪框**：`Mask#applyScissor` 是唯一出口，两条分支——
- 普通路径：`enableScissor(rect.x(), rect.y(), rect.width(), rect.height())`，内部只乘 `getScissorScale(window)`
  （即 §3.2 的 `scissorScale`），**完全不消费** `pushScissorScale` 快照里的基础平移 `b30/b31`；
- surface 路径：`Mask.SurfaceScissorTransform#apply`（进入 surface 时构建），用 surface 自己的
  `offsetX/offsetY/scaleX/scaleY` 做平移，**也没有用 b30/b31**（这两个字段只被存进快照，apply 里没读）。

也就是说：**普通路径根本没有"文档被平移了"这个概念**。文档画在 (0,0) 时无感；居中平移后几何移走、裁剪框不动，
所有 `overflow:hidden` 区域（模型盒、槽位面板）按旧位置裁切。
修法：`AuiScissorOffsetMixin` → `@ModifyVariable` 改 `Mask#enableScissor` 的 x/y 入参，
补 `documentToGuiPosition(0,0) ÷ renderScale`（GUI 偏移 → CSS 单位），文档用 `Document.getContextDocument()` 取。

**菜单槽位坐标**：`SlotDataBinder#syncAllSlotPositions` 算的是 `round(doc × viewportScale) − leftPos`，
同样不知道绘制偏移。修法 `AuiSlotMenuOffsetMixin`：在 RETURN 处 `slot.x/y += documentToGuiPosition(0,0)`。

> 居中改造共**四件套**，缺一不可：`AuiDocumentCenterMixin`（输入映射）、`AuiContainerDocCenterMixin`（画面平移）、
> `AuiScissorOffsetMixin`（裁剪框）、`AuiSlotMenuOffsetMixin`（菜单槽位坐标 = 物品渲染位置 + 原版式点击判定）。
> AUI 里所有"文档画在 (0,0)"的假设点都要逐一核对：`Document#applyViewport`（偏移清零）、
> `Base#drawOverlayDocument` / `Base#drawScreenDocument`（只 scale）、`ApricityContainerScreen#drawLinkedDocument`（只 scale）、
> `Mask#enableScissor`（不消费基础平移）、`SlotDataBinder#syncAllSlotPositions`（不消费偏移）。

---

## 14. 源码地图与取证方法

**AUI-snow 仓库的真实分层**（`common/` 放平台无关代码，`targets/<平台>/` 放平台相关代码，
kingdom 用的 jar 来自 `targets/neoforge-1.21.1`）：

| 要找什么 | 去哪 |
|---|---|
| viewport / 缩放 | `common/src/main/java/com/sighs/apricityui/viewport/ApricityViewport.java` |
| Document（坐标换算、applyViewport、hitTest、intercept） | `common/.../init/Document.java` |
| Element / 样式 / 文本测量 | `common/.../init/Element.java`、`common/.../style/`（`Style`、`Text`、`Interaction`） |
| 布局（flex/grid/wrap/基线）与测量缓存 | `common/.../layout/Flex.java`、`common/.../layout/LayoutMeasureCache.java` |
| 渲染节点、裁剪、图片采样 | `common/.../render/RenderNode.java`、`Mask.java`、`ImageDrawer.java`、`Base.java` |
| 槽位与容器元素 | `targets/neoforge-1.21.1/.../element/Slot.java`、`Container.java`、`Item.java` |
| 容器屏 / 菜单 / 绑定 | `targets/neoforge-1.21.1/.../screen/ApricityContainerScreen.java`、`ApricityContainerMenu.java`、`SlotDataBinder.java` |
| 自动补槽（expander） | `targets/neoforge-1.21.1/.../dom/expander/ContainerExpander.java` |
| AUI 自己的 mixin（原版行为改写） | `targets/neoforge-1.21.1/.../mixin/AbstractContainerScreenMixin.java`（该目录只有这一个） |
| 客户端输入 / 缩放 / 持久文档 / 计时 HUD | `targets/neoforge-1.21.1/.../client/Client.java` |
| 平台渲染与物品渲染 | `targets/neoforge-1.21.1/.../neoforge/RenderService.java`、`ItemRenderService.java` |
| 玩家背包索引映射 | `common/.../container/PlayerInventorySlotOrder.java` |
| UA 样式表（槽位默认值、`pointer-events`、`item` 尺寸） | `common/src/main/resources/assets/apricityui/apricity/global.css` |
| 官方文档 | `AUI-snow/docs/guide/*.md`（`container.md`、`apricity-screen.md`、`ai-skill.md`）；在线站 `https://doc.sighs.cc/ApricityUI/` |

**取证方法**：先用 `Select-String` 找方法所在文件与行号，再定点读方法体；
MC/NeoForge 的签名用 `javap -p -classpath <jar> <类>` 现查（§10）。
**引用写法**：写 `文件#方法`，行号只在当次核验时有效，不要写进结论。

---

## 15. 像素风中世纪主题 HTML 编写与 AUI 适配指南

> 本节是"从零写一个新页面 / 新 HUD"的总纲：**先定宿主形态 → 套设计令牌 → 用版式配方 → 过交付自检**。
> 细节一律指向前面各节，这里只给能直接抄的结论。

### 15.1 第一步：定宿主形态（它决定其余全部约束）

| 形态 | 怎么创建 | viewport meta | 交互能力 | 必须遵守 |
|---|---|---|---|---|
| **HUD / overlay** | AUI 的 overlay 文档路径（`RenderGuiEvent` 那侧，`Base.drawOverlayDocument`） | `mode=gui` | 全在 DOM（可写 `aui-mouse-events=intercept`） | **空流 ⇒ 根元素绝不写 `overflow`**（§4.4、坑 34） |
| **普通界面 Screen** | `class X extends ApricityScreen`（构造传 `ui/x.html`） | `mode=gui` | DOM | 尺寸由 Java 按 `getViewportSize()` 现算（§4.3） |
| **世界窗口** | `ApricityUI.createWorldWindow(...)` | `mode=fixed,width,height,scale=1` | DOM + 世界射线 | **`setFacing(true)`**，否则只在正面角度可见（§16 D25）；要 DOM 交互就必须走登记路径（§16.6） |
| **真实容器 GUI** | `new PendingMenu(player, page).bind(...)`（服务端） | `mode=fixed` + 整稿固定 `scale` | 槽位走原版菜单 | 不写 `intercept`、一处 `flex-wrap` 都不留、整稿塞进视口（§1.0、坑 32） |

### 15.2 设计令牌（照抄；改配色只改这里）

```css
:root{
  --parchment:#c8ac7c; --parchment-hi:#d8bc8c; --parchment-lo:#8a6e3c;
  --ink:#3a2410; --ink-dim:#5a4020;
  --border-dark:#4a3218; --border-mid:#6a4a20; --border-hi:#8a6e3c;
  --gold:#e8c96a; --blood:#8c2f22;
}
*{ box-sizing:border-box; margin:0; padding:0; }   /* 边框计入尺寸 */
html,body{ background:transparent; }                /* 深色底只属于浏览器稿，§13.7 */
body{ font-family:'Zpix',monospace; font-size:13px; color:var(--ink); }
```

- **圆角一律 0**（像素风），不要 `border-radius`（AUI 会走圆角遮罩 → stencil 路径，与 §16 D22 那串报错同源）。
- 描边永远是 `border` + 四层 `box-shadow`（外描边 / 内描边 / 左上高光 / 右下暗边），配方见 §5.1。
- 尺寸全整数 px；间距取 2/4/8 的整数倍。

### 15.3 版式配方

| 需求 | 写法 | 依据 |
|---|---|---|
| 纵向排（面板、档案项） | `display:flex; flex-direction:column` | 竖排 `gap` 可用 |
| 横向两栏 | `.left{width:229px;flex:0 0 auto}` + `.right{width:459px}` | §4.2（别用 `fr`） |
| 横向一排 | 子项 `flex:0 0 auto` + `margin-right`（**不要横排 `gap`、不要 `flex-wrap`**） | §13.5、坑 19、坑 17 |
| 槽位阵列 | `display:grid; grid-template-columns:<整数列数>; gap:<px>`（AUI `preset:player` 同机制） | §13.8 |
| 内容自适应高度 | Java 算好写 inline style | §4.3 |

**容器页额外回避清单**（会进"布局 ↔ 文本 ↔ 样式"循环，实测卡死）：`align-items:baseline`、
`text-indent` / `letter-spacing` 用 `.em/%`、`flex:1` / `flex-basis:0%`、任何带 `em/%` 的尺寸（§7.7）。

### 15.4 质感与像素化（四条铁律）

1. **背景贴图**：`background-image:url('/tex/x.png'); background-size:100% 100%; background-repeat:no-repeat;`，
   元素补 `overflow:hidden`（否则边框外多出一圈）。贴图按**目标显示尺寸**设计，或整数倍拉伸。
2. **采样**：AUI 的背景图 / `<img>` / 九宫格三条路径都是**最近邻**（`blur=false`）；**不要写 `blur="true"`**，
   不要用 `transform` / `filter`（会引入重采样，§5.2）。
3. **缩放整数**：普通页 `zoom=1,min-zoom=1,max-zoom=1`；整稿页固定 `scale`（§13.3）。非整数缩放会让上面两条全废。
4. **程序化图形走 Java**：印章 / 盾徽 / 进度条 / 头像 / 3D 模型一律写 `Element` / `Canvas` 元素 +
   `VALUE_INTERPOLATION_NEAREST_NEIGHBOR`（§8.1、§8.4），**不要赌 `<script>` / `<canvas>`**（§2）。

### 15.5 组件配方

- **面板 / 卡片**：`--parchment` 底 + `parchment_panel/card.png` 或 §13.2 的 `pane_gen/card_gen.png` + §5.1 描边。
- **槽位**（容器 GUI）：`<slot class="slot" slot-index="n">`；class 上写 `--aui-slot-size:<px>`（§7.11）；
  **不写** `--aui-slot-render-bg`（§7.2）；`<item class="slot-icon">` 铺满槽位 + `--aui-slot-icon-scale`（§7.12）。
- **进度条 / 属性三角 / 印章 / 盾徽 / 头像 / 实体模型**：用现有元素（§8.2 的表）。
- **悬停与 tooltip**：容器槽位的悬停高亮与 tooltip 由 AUI 自绘（§7.9 的能力位）；DOM 元素的 hover/click
  只在 DOM 页与**已登记**的世界窗口里才有（§16.6）。

### 15.6 字体与符号

- `@font-face src:url('/font/zpix.ttf')`；Zpix **只覆盖拉丁 / GBK 与部分几何图形区**（§6.2）。
- **任何新符号先用 §6.1 的 GlyphTypeface 脚本验一遍**，`ContainsKey == false` 的一律不用（否则整块空白）。
- 需要纹章母题（十字 / 鸢尾 / 狮子 / 锚）：字体这条路走不通，写 Java 元素画（§8.4）。

### 15.7 数据落位与交互

- 页面只留**结构标签 + 空值**，文案 / 数值全由 Java 填（§9.1）。
- **面板数据是异步到的**：屏幕 `init()` 会先画一次"还没有数据"的状态，服务端状态随后才到。用
  "是否渲染过"当一次性开关（`xxxBuilt`）会把这一轮空表**锁死**——重建依据必须按**内容是否变化**判定。
  作物 / 清单这类列表最容易踩：`空 → 有` 之后再也不会重建，页面看着像"根本没适配"。
- 只在服务端的属性（饥饿、称号…）走 `SynchedEntityData`，不要为它新开网络包（§9.4）。
- 容器页：`PendingMenu(...).bind(...)`，绑定顺序定 primary；实体物品能力要注册（§7.3、§7.4）。
- **容器页不写 `aui-mouse-events=intercept`**：它会吃掉原生点击（§11 坑 32）。

### 15.8 交付自检（每次改完都过一遍）

1. 过 §12 checklist；
2. 同步 `build/resources/main`（坑 16），关键文件改完**回读校验**（§10 的编辑器注意事项）；
3. 起日志看自打诊断行；卡死先看 `logs/kingdom-watchdog/summary.log`（§10）；
4. 涉及 AUI 的改动，按 `开发规则.md` 规矩 A **当场**回写本文件（新坑进 §11.A / §11.B，AUI 侧问题进 §16）。

---

### 15.9 官方明确的支持范围（照做即可；细节与出处见 §17.2）

- **放心用**：flex（含 `wrap` / `baseline` / `gap`）、grid（`template-columns/rows` 的 px/auto/`fr`/`minmax`/`repeat` + `gap`）、
  `transform` / `filter` / `clip-path` / 动画、`box-shadow`（多重 + inset）、`border-radius`、`border-image` 九宫格、
  `min()` / `max()` / `clamp()`、`aspect-ratio`。
- **别用**：`float`、`sticky`、表格布局、grid 的 `areas` / `auto-flow` / 命名线 / `subgrid` / 隐式轨道、`calc()` 乘除嵌套、
  `fit-content` / `min-content`、`border` 虚线（**会画成实线**）、`radial/conic` 渐变、`skew` / `matrix`、
  `::first-line` / `::selection` 一类伪元素、`:has()` / `:link` 一类伪类。
- **必须知道**：**没有 UA 默认样式**（`h1` 与 `div` 一样，样式全自己写）；**负 margin 被钳成 0**；
  `transition` 有白名单（名单外的属性直接跳变）；命名色只有 26 个；`vertical-align` 只有 `baseline` 有效；
  `text-decoration` 只有下划线 / 删除线。
- **两个"官方标支持但别滥用"**：`transform` / `filter` 会引入重采样 ⇒ 像素图禁用（§5.2）；
  `wrap` / `baseline` 在**容器屏**会触发测量循环 ⇒ 卡死（§7.6 / §7.7）。官方说的"支持"与我们的"别用"不冲突：
  一个是能力，一个是性能。

---

## 16. AUI 侧问题清单（疑似 / 已确认，D1–D25）

> ⚠ **D4 / D5 / D6**（横排 `gap` / grid 的 `fr` / `aspect-ratio`）与官方文档冲突，已降级为**待重测**（§17.1）；
> 页面现有写法不动。其余条目不受影响。

> 本节收**由 AUI 自身行为造成**的问题，也就是 §11.B 的逐条证据；我们自己的坑在 §11.A。
> 每条格式统一：**证据**（`文件#方法`，✅ = 源码核过 / 🕐 = 历史实测）→ **后果** → **我们的绕过**；
> 末尾 §16.7 给"这 25 条怎么用"的分组建议。

> **收录判据**：不是"我们页面写错了"，也不是 MC 自己的限制，而是 **AUI 的设计/实现问题**才收进来；
> 我们自己的坑（如忘记同步 `build/`、写错 meta）不进本表。
> **证据标记**：✅ = 2026-10-02 对着源码逐行核验过；🕐 = 历史实测（看门狗报告/现象）结论。
> 级别：**致命**（卡死/完全不能用）→ **严重**（错位/失灵）→ **一般**（观感/易错）→ **不一致**（文档·实现·隐性状态）。

### 16.1 布局引擎（最要命的一组）

| # | 级别 | 缺陷 | 证据 | 后果 | 我们的绕过 |
|---|---|---|---|---|---|
| D1 | 致命 | **测量缓存只在绘制期有效**：`LayoutMeasureCache` 的四个出入口都要求 `state.depth > 0`，而 `depth` 只由绘制路径的 `begin()/end()` 配平。凡"绘制之外的测量"（`Screen.init()` 逐槽位求位、悬停/拖拽命中）都是**无缓存的全树测量，算完还丢**。 | ✅ `common/.../layout/LayoutMeasureCache.java#getSize/#putSize/#getObject/#putObject`、`#begin/#end` | 容器屏 init 对 60 个槽位各做一次全树测量；页面元素越多越慢 → 表现为"必卡" | `AuiSlotSyncMeasureCacheMixin` + `AuiMeasureCacheGateMixin`（§7.8） |
| D2 | 致命 | **换行布局与文本/样式测量互相递归**，既无递归保护也无中间结果缓存：`computeWrappedRowLayout → buildWrappedRowLines → closeWrappedRowLine → computeWrappedRowContentSize → computeContentSize`，再与 `Text.of / Size.getTextSize / ScrollModel.getHorizontalScrollbarGutter / Element.getComputedStyle` 打环。 | ✅ `common/.../layout/Flex.java#computeWrappedRowLayout` 等一串；🕐 看门狗栈底（§7.6） | 只要页面有 `flex-wrap`，被 D1 放大 60× 后渲染线程纯自旋，连日志都停 | 页面一处 `flex-wrap` 都不留 |
| D3 | 致命 | **同一循环还有三个"非 wrap"入口**：`align-items/align-self: baseline`（要算文本基线）、相对单位 `text-indent/letter-spacing: .em`（要解字号）、`flex: 1` / `flex-basis: 0%`（要解父主轴尺寸）——每一个都会走"布局 → 文本/字号 → 内联样式 → 父尺寸"。 | ✅ `Flex#computeBaselineMetrics/#baselineFromCrossStart` + `common/.../style/Text.java`；🕐 第三份看门狗报告（§7.7） | 去掉 wrap 后**照样卡死**，把排查方向带偏 | 容器页回避清单（§7.7） |
| D4 | 严重 | **flex 的 `gap` 横排不生效、竖排生效**（同一属性两种行为）。 | 🕐 实测（§11 坑 19） | 槽位粘死/被拉伸；"看着对"很容易误判 | 横排用 `margin`；或改用固定列 `grid` 的 `gap` |
| D5 | 严重 | **`grid` 的 `fr` 分配算错**（`1fr 2fr` 实测约 1.26:1，左右还会反）。 | 🕐 实测（§11 坑 8/20） | 分栏塌成单列通栏 | 分栏用 flex + 显式 px；`grid` 只用于固定列数阵列 |
| D6 | 一般 | **`aspect-ratio` 未实现**。 | 🕐 实测（§11 坑 21） | 高度失控、内容被拉爆 | 写死等价 `height`（§13.4） |
| D7 | 严重 | **自定义标签的"标签选择器"匹配不可靠**：`#entity slot{...}` 不生效，同页 `.class` 选择器正常。 | ✅ 现象 + `mixin/AbstractContainerScreenMixin#apricityui$cancelVanillaRenderSlot`（槽位皮肤确由 AUI 自绘） | 颜色写不进去，看起来像"原版灰皮肤没去掉" | 一律用 class 选择器（`.slot`，§7.6） |

### 16.2 坐标与绘制状态（居中的一切麻烦都源于此）

| # | 级别 | 缺陷 | 证据 | 后果 | 我们的绕过 |
|---|---|---|---|---|---|
| D8 | 严重 | **"文档偏移"在 AUI 里是个不完整概念**：`Document#applyViewport` 把屏幕文档偏移写死 `(0,0)`，`viewportOffset` 只被坐标换算消费，渲染路径不消费（`drawLinkedDocument` 只 `scale`）。 | ✅ `Document#applyViewport`（`setViewportTransform(rs, rs, 0, 0)`）、`#setViewportTransform`；✅ `ApricityContainerScreen#drawLinkedDocument` | 文档只能钉在左上角，想居中必须外挂 mixin | `AuiDocumentCenterMixin` + `AuiContainerDocCenterMixin`（§13.6） |
| D9 | 严重 | **裁剪框不消费文档偏移**：`Mask#applyScissor → #enableScissor` 只乘 `getScissorScale(window)`；快照 `ScissorScaleState` 存了 `b00/b11/b30/b31` 却**没人读**，surface 路径另造 `SurfaceScissorTransform`（用自身 offset，同样不读 b30/b31）——**一套快照两套消费，且都与"文档平移"无关**。 | ✅ `Mask#applyScissor/#enableScissor/#pushScissorScale`、`Mask.SurfaceScissorTransform#apply` | 居中后所有 `overflow:hidden` 区域按旧位置裁切 | `AuiScissorOffsetMixin`（§13.10） |
| D10 | 严重 | **菜单槽位坐标同样不消费偏移**：`SlotDataBinder#syncAllSlotPositions` 用 `round(doc×viewportScale) − leftPos` 直接写 `menuSlot.x/y`。 | ✅ `SlotDataBinder#syncAllSlotPositions` | 画面居中了，物品渲染与原版式点击判定留在旧位置 | `AuiSlotMenuOffsetMixin`（§13.10） |
| D11 | 严重 | **判定基准分裂**：命中走 DOM 几何（`isHovering → isBoundElementHovered`、`Slot#containsSlotPoint` 用 `Position.of`），渲染/原版点击走菜单 `slot.x/y`——不居中时两者恰好重合，掩盖了分裂。 | ✅ `AbstractContainerScreenMixin#apricityui$injectSlotHovering`、`SlotDataBinder#isBoundElementHovered/#findSlotIndexAt` | 任何"文档被平移"的改造都要同时补两处，漏一处就是"看得见点不到" | 四件套必须成对（§10、§13.10） |
| D12 | 严重 | **绘制状态不成对**：`drawLinkedDocument` 内部 `pushPose()/popPose()` 成对，但外部注入的平移会让它"只还原到平移之后"，从而泄漏给同帧后续绘制（tooltip / 伪光标 / 持久文档 / 计时 HUD）。 | ✅ `ApricityContainerScreen#drawLinkedDocument` + `#drawSlotHoverTooltipByElement`、`Client#drawPersistentScreenDocuments/#drawPseudoCursor` | 对准物品时 tooltip 整体偏掉 | 平移在 `@At("RETURN")` 原样抵消（§11 坑 30） |

### 16.3 槽位语义（"关一个开关 = 关一整套"）

| # | 级别 | 缺陷 | 证据 | 后果 | 我们的绕过 |
|---|---|---|---|---|---|
| D13 | 严重 | **`shouldRenderBackground()==false` 会把背景 + 阴影 + 边框三个阶段一起跳过**——"关背景"的语义被扩大到"关一切"。 | ✅ `element/Slot.java#shouldRenderBackground` + `#drawPhase` | 只想去掉底色 → 槽位彻底空白、边框也没了 | 别写 `--aui-slot-render-bg`，要用 `render` 系开关（§7.2、§7.10） |
| D14 | 严重 | **热区尺寸解析顺序把"UA 默认值"排第一**：`resolveSlotSizeHint` = CSS `--aui-slot-size` → `max(computed width,height)` → `size/slot-size`；UA 给所有 slot 默认 `18`。且 `containsSlotPoint` 用"方形 + sizeHint"而不是渲染出的真实矩形。 | ✅ `Slot#resolveSlotSizeHint/#containsSlotPoint`；✅ UA `common/src/main/resources/assets/apricityui/apricity/global.css`（`--aui-slot-size:18`） | 自绘大槽位默认"点格子中心没反应"（热区只有 18px） | 槽位 class 里显式 `--aui-slot-size`（§11 坑 28） |
| D15 | 严重 | **物品图标放大这条链有三处各自为政的缩放约定**：① UA 把 `item` 定死 `16×16`、`slot > item{left:1px;top:1px}`；② 居中基准取的是 `<item>` **自己**的矩形（`RenderNode.ItemNode#centeredBodyX/Y` → `Rect.of(item)`）而不是槽位矩形；③ 放大只有 `--aui-slot-icon-scale` 一条路（`Slot#resolveIconScale`）。 | ✅ `common/.../render/RenderNode.java#centeredBodyX/#centeredBodyY`；✅ `Slot#resolveIconScale`；✅ UA `global.css` | 大槽位里图标必然"16px 缩在左上角" | 铺满 + `--aui-slot-icon-scale` **成对做**（§11 坑 31） |
| D16 | 严重 | **浮动物品与槽位图标不同规章**：`captureFloatingItem` 写死 `1.0D` / z=232；`ItemNode#render` 的缩放绕局部 `(8,8)`、装饰位移 `decorationOffsetY` 是在**放大后的 pose** 里 `translate`（`ItemRenderService#drawDecorations`）——既不同步倍率，也没有"锚点/装饰随缩放补偿"的接口。 | ✅ `ApricityContainerScreen#captureFloatingItem`、`neoforge/ItemRenderService.java#render/#drawDecorations` | 拿起物品瞬间图标变小、上偏，数量数字飞出去 | `AuiFloatingItemScaleMixin`（§11 坑 33） |
| D17 | 严重 | **`intercept` 是整页级的，没有局部开关**：`Document#interceptsMouseEventsAt` = `interceptMouseEvents && hitTest(命中)`，一旦开启，命中区域的原生点击被 `Client#mouseButton` 取消——**"页面 DOM 要点击"与"槽位要原生点击"无法共存**。 | ✅ `Document#interceptsMouseEventsAt`、`client/Client.java#mouseButton` | 容器页写了它就"整个界面点不动"，而悬停/tooltip 照常 → 极易误判成槽位坏了 | 容器页不写 intercept（§11 坑 32） |

### 16.4 文档不一致 / 隐性状态 / 其他

| # | 级别 | 缺陷 | 证据 | 后果 | 我们的绕过 |
|---|---|---|---|---|---|
| D18 | 不一致 | **官方文档与实现相反**：`docs/guide/container.md` 把 CSS `--aui-slot-interactive` 排在 HTML `interactive`/`pointer` **之前**，源码是**之后**。 | ✅ `Slot#resolveInteractionCapabilities`（recipe → `interactive` → `pointer` → CSS → 绑定默认） | 照文档改 CSS 变量会被属性覆盖，"怎么改都没反应" | 以源码为准（§7.9）；两边都写更保险 |
| D19 | 不一致 | **缩放按页面路径隐式持久化**（`readStoredZoom/writeStoredZoom/zoomStorePath`，落在 config 目录）。 | ✅ `ApricityViewport` 同名方法 | "同一页面别人正常我发虚""改了没反应"，且没有任何 UI 提示 | `min-zoom=max-zoom` 钉死，或 `Ctrl+0`（§3.3） |
| D20 | 不一致 | **`pushScissorScale` 快照的 `b00/b11/b30/b31` 是死字段**（存了没人用）。 | ✅ `Mask#pushScissorScale`、`Mask.SurfaceScissorTransform` | 读代码时以为"平移已经被处理"，实际没有 → 误判 | 不依赖它（§13.10） |
| D21 | 一致性问题 | **原版 `renderSlot` / `renderSlotHighlight` / `renderFloatingItem` 全被 cancel**，由 AUI 自绘。 | ✅ `mixin/AbstractContainerScreenMixin`（三个注入点） | 任何"槽位长得不对"都不是原版的锅；与资源包/原版行为的兼容性被 AUI 接管 | 知悉即可（修正排查方向） |
| D22 | 一般 | **AUI 自身的 GL 报错**：`GL_INVALID_OPERATION: Depth formats do not match` 成片刷日志。 | 🕐 实测（建筑渲染正常时同样出现，§11 坑 10） | 会把排查引到自己代码上 | 忽略，别当线索（§8.3） |

### 16.5 遮罩与"整层不画"的语义（本轮补充）

| # | 级别 | 缺陷 | 证据 | 后果 | 我们的绕过 |
|---|---|---|---|---|---|
| D23 | 严重 | **"零尺寸遮罩 = 整层不画"**：`Mask#pushMask` 把 `currentClip` 与遮罩矩形求交，交出来的矩形若不满足 `AABB#isValid()`（`width>0 && height>0`）就被当成"全不可见"，`RenderNode` 五个节点入口直接 `return`。也就是说"裁剪到一个空区域"与"整层跳过"被合并成同一件事，**且不区分"作者就是要裁到空"还是"盒子尺寸算错了"**。更要命的是根元素盒子高度取决于流内内容（全 `position:fixed` 的空流文档实测 474×0），于是"根元素带 overflow"这条无害惯例会在 HUD 类页面上静默炸掉整页。 | ✅ `AABB#isValid`、`Mask#pushMask`（两个分支都做交集）、`RenderNode#ElementPhaseNode/ElementBackgroundNode` 等 5 处早退、`render/Drawer` 的 `clipsOverflow` 门控 | 整页不渲染、零日志零报错，极难定位（工程根目录 `aui-hud-ok/broken.html` 就是最小复现） | 空流页面根元素不写 `overflow`（§4.4、§11 坑 34） |
| D24 | 严重 | **世界窗口的文档裁剪曾是"纯逻辑剔除矩形"**：AUI 注释自证 "used to be only a logical culling rectangle"，世界变换（旋转/朝向）会让这条逻辑矩形与真实可见区域不一致 → 内容按角度被剔除；现改为 `pushForceStencil()` + `forceStencil=true` 视口遮罩（真像素裁剪）。但 forces 遮罩的可选实现只剩两档：**stencil 可用 = 建 stencil；stencil 被抑制 = `MaskMode.NONE`（等于不裁）**，没有"退化成 scissor"这一档（scissor 只给非 forced 的矩形遮罩用）。 | ✅ `Mask#pushMask`（`mode = !stencilUsable() ? (forced ? NONE : SCISSOR) : …`）、`Mask#pushForceStencil`、`WorldWindow#render` 里的注释与调用 | 掐掉 stencil（我们在建筑预览期做的事）会**连带取消这条真像素裁剪**；反过来若保持 stencil，"按角度误剔"就取决于 stencil 遮罩 quad 在世界变换下能否正常栅格化 | 知悉即可；出现"世界窗口内容溢出"时先想到这条（§11 坑 35） |

### 16.6 世界窗口专项（朝向 / 剔除 / 矩阵 / 登记）

> 这一节专门回答"世界空间里为什么按角度不可见"。四条已核实的事实，出问题按这个顺序排查。

| # | 级别 | 问题 | 证据 | 后果 | 绕过 |
|---|---|---|---|---|---|
| D25 | 一般（易误导） | **`facingEnabled` 默认 false**：`WorldWindow` 的 `private boolean facingEnabled;` 初值 false，不设时用构造时的固定朝向 `rotationY(180°)`。零厚度平面板于是**只在正面那一段角度可见**。AUI 自己命令生成的测试窗 `ApricityUIClientCommands#spawnTestWindow` 只做了 `new WorldWindow + addWindow + bindDebugger`，**没有 `setFacing`** ⇒ 表现正是"只在固定角度可见"；而 `WorldWindowTestSpawner:49` 与我们的 identify（`NpcIdentifyWindow:208/266`）都 `setFacing(true)` ⇒ 全角度可见 | ✅ `WorldWindow` L62/L107/L222-227、`WorldWindowTestSpawner:49`、`ApricityUIClientCommands:67-69/123/248`、`NpcIdentifyWindow:208/266` | 会误判成"窗口坏了 / 被剔除"，其实只是它背对着你 | `window.setFacing(true)`；**判别法**：勾上那个调试面板的 `facing-enabled` 复选框（`ApricityUIClientCommands` L123 处理、L248 回显），勾上后应变全角度可见 |

**四条事实（排查顺序）**：

1. **朝向 = D25**：默认固定朝向 ⇒ 固定角度可见。先看 `isFacingEnabled()`。
2. **剔除 = D24**：`render` 在 L492（**平移之前**）调 `isQuadVisible(poseStack.last().pose(), projectionMatrix, w, h)`，
   而它判定的是"以锚点为中心、±w/2、±h/2"的矩形，真实面板却在 L497 之后才 `translate(-w/2,-h/2)` ⇒
   **判定矩形比真实面板少挪半个面板**；再叠 L940（整块在相机背后）与 L944-948（六个"整块在同侧"）判定，
   面板接近 edge-on 时四块会同时出界 ⇒ `return`（所以是"完全不可见"而不是"变薄"）。⚠ 待实测确认。
3. **矩阵约定**：`P × pose` 本身就是正确 clip（`pose` 已含相机旋转、需要自己减相机平移 ⇒ 同函数里 `translate(renderPos − cameraPos)`）；
   AUI 自己的钩子只传 `P`（`WorldWindowRenderer:29`）。**我们 identify 传的是 `P × event.getModelViewMatrix()`，等于把 V 乘两遍**（§11 坑 36）。
4. **登记**：`WorldWindow.windows` 只在 `WorldWindow.addWindow` 里维护（**构造器不登记**）；
   渲染（`WorldWindowRenderer`）与鼠标分发（`Operation#onMouseMoveFrame → WorldWindow#dispatchMouseMove`）都只遍历这个列表
   ⇒ **不登记 = 只能自己渲染 + 拿不到 DOM 交互**；登记 = AUI 渲染 + AUI 分发鼠标（记得去掉自己的绘制，否则双渲染）。

### 16.7 这 25 条怎么用

- **只能绕过、改不了**（除非改 AUI 源码）：D1–D3、D8–D12、D23 —— 这正是本项目那 9 个 AUI 相关 mixin 存在的理由。
  D24 更偏"设计取舍"：`forced` 遮罩只有 stencil / NONE 两档，我们在建筑预览期主动选了 NONE（换来深度缓冲完整），代价是世界窗口失去真像素裁剪。
- **绕过低成本但新页面容易重踩**：D13–D17 —— 写新页面时对着 §12 checklist 逐条过一遍即可。
- **能力缺口**（我们干脆不用）：D4–D7 —— 若将来 AUI 修好，§2 禁用清单可以放宽。
- **值得反馈给 AUI 作者**（影响面最大、且与其自身宣称能力冲突）：D1/D2（非绘制期测量无缓存）、
  D8/D9/D10/D11（"文档偏移"只做了一半，居中必然出事）、D14/D15（UA 默认值压过作者样式、图标放大无统一约定）、
  D18（文档与实现相反）、D23（空流文档根元素 overflow 会静默整页失效）、D24（forced 遮罩只有 stencil / NONE 两档）、
  D25（`facingEnabled` 默认 false ⇒ 固定角度可见，连 AUI 自己的命令窗都没设——最容易让人误判成"窗口坏了"）。


---

## 17. 官方文档核验（对照 `doc.sighs.cc/ApricityUI/`，2026-10-02）

**核验方式**：在线站首页可达（`https://doc.sighs.cc/ApricityUI/`，站点"高塔书库"，AUI 分区 = 简介 / showcase(5) / guide(16) / 快速上手 / SKILL），
但**子页 URL 未在首页暴露**；该站 SKILL 指向仓库 `Tower-of-Sighs/AUI` 的 `snow` 分支 `docs/ai-skill.md` ——
与本仓库 `AUI-snow/docs/` 同源 ⇒ 本次用**本地 `AUI-snow/docs/guide/*.md`（18 篇）逐条对照**，等价于核验官方文档。

### 17.1 官方文档与源码/实测**冲突**的地方（以源码为准）

| # | 官方原话 | 源码 / 实测 | 处理 |
|---|---|---|---|
| 1 | `container.md` 交互控制优先级：**CSS `--aui-slot-interactive` → HTML `interactive` → HTML `pointer` → 绑定默认** | 源码 `Slot#resolveInteractionCapabilities`：**recipe → HTML `interactive` → HTML `pointer` → CSS → 绑定默认**（HTML 在前） | 以源码为准（§7.9、§16 D18）。**两边都写**最稳：要关就 `interactive="0" pointer="0"` |
| 2 | `html-css-coverage.md`：Flex **`gap` ✅**、**`wrap` ✅**、**`align-items/self: baseline` ✅**；盒模型 **`aspect-ratio` ✅**；**grid（MVP）`template-columns/rows` 含 `fr`/`minmax`/`repeat` + `gap` ✅** | 我们实测（🕐）：横排 `gap` 不生效、`1fr 2fr` 塌陷、`aspect-ratio` 不实现；`flex-wrap` 在容器屏**卡死**（看门狗报告）；`baseline` 触发测量循环 | ⚠ **`gap` / `fr` / `aspect-ratio` 三条降级为"待重测"**（不再当 AUI 缺陷，§16 D4/D5/D6 同步降级）；页面已用等价值（margin / 显式 px / 精确 height）——**保持不动**。`flex-wrap`/`baseline` 的问题**不是"不支持"而是性能与循环放大**，结论保留 |

### 17.2 官方文档补充的事实（原先缺，已据此补全）

**世界窗口**（`guide/world-window.md`）——**逐条印证 §16.6 的四条事实**：
- `ApricityUI.createWorldWindow(...)` **创建并注册**；手动 `new WorldWindow(...)` 只创建不注册，得自己 `WorldWindow.addWindow(window)`；
  只 `Document.createInWorld(path)` 则"资源存在但世界里没窗口" ✓（= §16.6 事实 4）
- **同一实例别重复 `addWindow`，会重复绘制、重复收事件** ✓
- `setFacing(true)` 每帧面向相机；**"要固定朝向的牌子就别开"** ⇒ "只在固定角度可见"是**预期行为**，不是 bug（= §16.6 事实 1 / D25）
- 每次渲染框架保存当帧投影矩阵，输入用**同一组变换**反投影再 hitTest ⇒ **命中精度依赖那个矩阵** ✓（= 我们传 `P·MV` 会破坏命中的原因，§11 坑 36）
- `setDepthTest(true)` 默认：方块能挡住窗口（命中时做可视性射线）；`maxDistance`（射线距离）≠ `maxDisplayDistance`（显示距离，全局默认 128）
- **"整个 Document 被裁剪在窗口矩形内，子元素的阴影、滤镜不会画出去"** ✓（= §16 D24 的 stencil 裁剪；**stencil 被抑制后这条保证失效**）
- 旧带 `width/height` 的构造器**已过时且会覆盖 viewport 配置**；`setScale(0.02f)` 表示 1 逻辑像素 = 0.02 世界单位并关闭自动缩放；`getScale()` 首次成功渲染前是回退值
- 旋转坑：`setRotation(Vec3)` 参数顺序是 `(pitch, yaw, roll)`；`setFacing` 开着时固定朝向不生效
- 输入：第一人称的交互点是**屏幕准心**；事件 `clientX/clientY` **已反投影成文档坐标，别再乘世界缩放 / renderScale / devicePixelRatio**
- 开着 Screen 时世界窗口的鼠标/滚轮被 Screen 优先级阻断；多窗口投影重叠时**每个命中的窗口都会收到事件**（无 topmost）
- 调试命令是 **`/aui worldwindow`**（不是 `/apricityui`）；验收用名叫 `auitest` 的盔甲架 ✓
- 坐标 API：`getDocumentPositionAtScreen` / `projectDocumentPosition` / `projectDocumentRect` / `getRealPos`（别自己造比例换算）
- 纯展示窗口**不用开 intercept**

**容器**（`guide/container.md`）：
- 玩家背包的**官方首选**是空容器 + `layout="preset:player"`（自动 36 槽、9-35 三行在上、0-8 快捷栏在底）；
  "手写 slot 也行（**必须是 container 直接子元素 + `slot-index` 正确**），但没必要自找麻烦" ✓（= §7.1、§13.8）
- 展示槽位建议显式 `interactive="0" pointer="0"` 让语义稳定
- **shift-click 方向由服务端绑定顺序决定，写 HTML 的 `primary` 属性没用** ⇒ 我们页面上那个 `primary="true"` 是**无效属性**（可顺手清理，§13.8）
- 渲染控制：`render="all|item|bg|none"` + `render-bg` / `render-item` + CSS `--aui-slot-render-*`（**CSS 版优先于 HTML 属性**）✓（= §7.10）
- 方块实体/实体的 8 格限制 ✓（= §7.3）

**CSS 覆盖面**（`guide/html-css-coverage.md`，写页面前必看）：
- **没有 UA 默认样式**（`h1` 与 `div` 视觉上一样，样式全自己写）
- ❌ 不支持/被忽略：`float`、`sticky`、表格布局、grid 的 `areas`/`auto-flow`/命名线/`subgrid`/隐式轨道、`calc()` 乘除与嵌套（**只支持加减**）、`fit-content`/`min-content`、`border` 虚线（**全画实线**）、`radial/conic` 渐变、`skew`/`matrix` transform、`::first-line`/`::selection` 一类伪元素、`:has()`/`:link` 一类伪类
- 🟡 需注意：**负 margin 被钳成 0**；`transition` 有白名单（`gap`/`grid-template`/`font-size` 等**直接跳变**）；`vertical-align` 只有 `baseline` 有效；`text-decoration` 只有下划线/删除线；命名色只有 26 个
- ✅ 放心用：flex（含 wrap / baseline / gap）、grid（MVP 子集）、`transform`/`filter`/`clip-path`/动画、`box-shadow`（多重 + inset）、`border-radius`、`border-image` 九宫格、`min()/max()/clamp()`、`aspect-ratio`
  ⇒ 两个提醒：**"支持"≠"在容器屏不出性能问题"**（§7.6/§7.7 的卡死是循环放大）；**`transform`/`filter` 可用 ≠ 能拿来做像素图**（会重采样，§5.2）

**Overlay / HUD**（`guide/overlay-document.md`）：
- 官方 FAQ 第一条就是"**Overlay 不显示**：… **根元素是不是零尺寸或被隐藏？**" ✓ ⇒ 我们的空流坑（§4.4、§16 D23）是**官方已知点**，不是我们看错
- 弹窗/下拉要跳出父容器 `overflow` 裁剪 ⇒ 用 Document 内 **top layer**（Java 侧标记）
- 截图/读鼠标：尺寸用 `document.getViewportSize()`、鼠标用 `document.getMouseDocumentPosition()`（**GUI scale ≥ 6 时 GUI 坐标 ≠ 文档坐标**）
- Ctrl+滚轮缩放目标按**最上层命中的 Document** 选；`[input] viewportZoomPassThrough` 会把没声明 intercept 的持久化 Overlay 跳过

**自定义元素**（`guide/secondary-development.md`）：
- 继承 `Element` + `@ElementRegister("MOD-TAG")` + `public (Document)` 构造器；注册用 `ApricityUIRegistry.scanPackage(...)` / `scanPackages(...)` ✓（= §8.1；我们的 `KingdomAuiIntegration#onClientSetup` 就是这个）
- 标签名**带模组前缀**、大小写不敏感；同标签重复注册后者覆盖前者（扫描顺序不是优先级）
- **必须在第一个 Document 创建前注册**；元素注册**不是热重载**，改注册逻辑要重启客户端

### 17.3 核验结论

- **保持**：§3、§4.4、§7、§8、§13、§16.6 的源码实证结论（本轮官方文档全部对上，尤其世界窗口那四条）。
- **降级**：`grid fr` / 横排 `gap` / `aspect-ratio` 从"AUI 缺陷"降为"⚠ 待重测"（官方标 ✅，源码也有实现）——页面写法不动，**别再当结论传播**。
- **补全**：世界窗口 13 条、容器 5 条、CSS 覆盖面 3 组、overlay 4 条、自定义元素 3 条（已并入本节，并在 §15 指南里同步）。
- **仍待实测**：官方文档没写、源码也未定的两条——`isQuadVisible` 半个面板偏移、登记后双渲染（§18）。

---

## 18. 遗留事项

> 世界窗口那几条（朝向 / 剔除 / 矩阵 / 登记）已并入 **§16.6** 与 **§11 坑 36**；下面的长条目是当时的原始记录，保留备查。

- **`NpcBackpackOpenPacket` 的 name 字段**曾观察到被塞入实体 id 字符串（截图里
  "姓名 entity.xy2407_kingdom.nec"），后续又出现过空串——服务端打包处待修
  （档案值空白但血条有值 = 服务端发了空数据，不是布局问题）。
- **`AuiSlotMenuOffsetMixin` 对"没有绑定槽位"的菜单槽位会逐帧累加偏移**：
  它给 `menu.slots` 全部 `+= documentToGuiPosition(0,0)`，而 `syncAllSlotPositions` 只重写
  `bindingsByGlobalIndex` 里的槽位；数量不一致时（DOM 槽位少于菜单槽位）那些槽位每帧漂移一次。
  当前页面 24 + 36 = 菜单 60 槽全部有绑定，所以无症状；若日后出现"某些槽位点不到"，先查这里。
- **`#entity` 仍是 `.slot-row` 嵌套写法**（非官方要求的"直接子元素"），实测绑定正常；
  新页面按直接子元素写（§13.8）。
- **`gender` 列默认空串**：库里若存在性别为空的老市民，会被分到 `male1`；
  需要的话可在 load 时随机补全并落库。
- **固定列 `grid` 是容器槽位阵列的唯一例外**（§2 的禁用清单里 `grid` 只禁 `fr`）：
  若将来 AUI 版本改动导致 grid 不可用，退回"显式 `.slot-row` 行 + 块级容器"，
  只是槽位就不再是直接子元素（绑定侧 `findAncestor` 仍能工作）。
- **待确认：`WorldWindow` 的视锥剔除判定矩形与真实面板错开半个面板**（"世界窗口按角度被剔除"的**候选真因**）。
  `WorldWindow#render` 在 L492 用此刻的 `poseStack.last().pose()`（= `T(renderPos−cameraPos)·R·S`，
  **尚未**施加 L497 的 `translate(-viewportWidth/2, -viewportHeight/2, 0)`）去调 `isQuadVisible`，
  而 `isQuadVisible`（L914-948）按"以锚点为中心、axes = clip 第 0/1 列 × w/2、h/2"做六面剔除 ⇒
  与真实绘制矩形（局部 `[0,w]×[0,h]`，中心在 `(w/2,h/2)`）**错开半个面板**；错开方向在 clip 空间里随
  `renderRotation`（`setFacing(true)` 时跟着相机转）与相机角旋转，于是"整块在相机背后"（L940）与
  六个"整块在同侧"（L944-948）判定可能把仍可见的面板判掉，L492-495 直接 `return`（不进 document/stencil、无日志）。
  **注意不是"漏传视图矩阵"**：`event.getPoseStack()` 就是 MC 的 level poseStack（含相机旋转、不含相机平移——
  所以同函数里要自己 `translate(renderPos − cameraPos)`），`P × pose` = `P·V_rot·T(−cam)·M` 正是 clip；
  再乘一次 `V`（如 `getModelViewMatrix()`）会把视图乘两遍，剔除会彻底错。
  验证：让卡片一半留在屏幕外（贴边 + 斜视 + 贴脸）看是否整块消失；或在 L492 前后各打一行日志比对。
  修法（AUI 零改动）：`@ModifyArg` 到 `WorldWindow#render` 里 `isQuadVisible` 的调用，把第一个参数换成
  "再乘 `translate(-w/2,-h/2,0)`"的矩阵（等于把判定矩形挪到与绘制一致）；或把 L497 的平移上移到 L492 之前。
- **已确认：我们自己的 identify 钩子给 `WorldWindow#render` 传的不是 `P`，而是 `P × MV`**（`NpcIdentifyWindow#draw`：
  `viewProjection = projection.mul(event.getModelViewMatrix())` 后当作 `projectionMatrix` 传进去；AUI 自己的
  `WorldWindowRenderer` 只传 `event.getProjectionMatrix()`）。因为 `poseStack` 里已经含 V_rot，这等于**把视图乘了两遍**，
  于是同一个参数在三处消费各不相同：
  ① `resolveRenderScale`（只读 `m00/m11`）——我们的窗口 `setScale(CARD_SCALE)` 命中 `if (scaleOverride != null) return scaleOverride`
  （L849-850）⇒ 提前返回，**尺寸不受影响**；
  ② `isQuadVisible`（`clip = P·MV·pose`）⇒ 六面与"相机背后"判定几乎不可能命中 ⇒ **剔除事实上被关掉**，这正是
  "identify 才真正能完全渲染"的机制（把剔除关掉，不是修好）；
  ③ `captureInteractionTransform`（`interactionClipMatrix = P·MV·pose`）⇒ 屏幕↔文档映射（L633 正投影、L725 `invert()`）整体错。
  **注意 ③ 目前没有消费者**：`interactionClipMatrix` 只被 `WorldWindow#dispatchMouseMove`（及点选）使用，而那条分发来自
  `Operation#onMouseMoveFrame` 的 `for (WorldWindow window : WorldWindow.windows)`；我们的窗口是 `new WorldWindow(...)`
  直接构造、**没有 `WorldWindow.addWindow(...)` / `ApricityUI.createWorldWindow(...)` ⇒ 不在 `windows` 里**
  （构造器不登记，已核 L102-111 与 L584-591）。连带后果：AUI 的 `WorldWindowRenderer`（同样遍历 `windows`）也不会渲染它
  ⇒ 我们自己的 `draw()` 是**唯一**渲染路径（不存在重复渲染），但世界窗口的鼠标分发同样不覆盖它。
  ⇒ 两条可选路线（**卡片能渲染是既定事实**：它靠我们自己的 `draw()`，AUI 那条路径不参与，所以"渲染"本身不需要动）：
  - **路线①（默认，最小改动，保留自渲染）**：只把 `viewProjection` 换成 `event.getProjectionMatrix()`（一行），
    再补那条"半个面板偏移"的 mixin（`@ModifyArg` 到 `WorldWindow#render` 的 `isQuadVisible` 调用，第一个参数传
    `pose·T(-w/2,-h/2)`）。这样渲染依旧完整（这次是靠**正确**的剔除，而不是把剔除关掉）、拾取矩阵也正确（`P×pose`），
    还省掉每帧进 document/stencil 的开销。**这是既完整又正确的组合。**
  - **路线②（卡片需要 DOM 交互时才走）**：改用登记路径（`ApricityUI.createWorldWindow(...)` 或构造后 `WorldWindow.addWindow(window)`）——
    AUI 的世界窗口鼠标分发 `Operation#onMouseMoveFrame → WorldWindow#dispatchMouseMove` **只遍历 `windows`**，
    不登记就永远收不到 hover/click。注意：登记后 AUI 的 `WorldWindowRenderer` 会**也**渲染它 ⇒ 必须同时删掉我们自己的
    `draw()`（否则双渲染），并同样需要"半个面板偏移"的补偿（登记后剔除生效）。
  - **现状（`P·MV` + 自渲染）**：唯一实际效果 = 剔除被关掉（= 卡片一定能渲染）；副作用（`interactionClipMatrix` 双 V）
    在当前不登记的窗口上**没有消费者**，所以现在也"可用"，代价是每帧都进 document/stencil 路径。

---

