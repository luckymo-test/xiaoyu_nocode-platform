# 计划书：可视化编辑之「手动样式面板」（低/中成本版）

> 日期：2026-09-06
> 范围：在已有点选元素交互之上，提供**属性面板手动微调样式**，改动持久化到源码并可撤销。
> 定位：与「AI 自然语言修改」互补——手动改快而精确（改色/字号/间距等），AI 改复杂/结构性需求。
> 复用的现状能力：[AppGeneratePage.vue](xiaoyuZeroCode-front/src/views/app/AppGeneratePage.vue) 已有点选（同源 iframe 注入 + `cssPathOf` 选择器 + `describeElement` 元素信息 + 编辑卡片 UI）；html / multi_file 的预览/下载/部署都读 `css/styles.css`。

---

## 一、目标与范围

**做（v1）**：html / multi_file 的**CSS 属性级手动微调**
- 点选元素 → 弹「样式面板」，改一组高频属性 → 生成一条带选择器的 CSS 规则 → 追加进源码 → 预览即时刷新。
- 撤销"最近一次手动修改"、清空全部手动修改。
- 改动真实写入源码，因此下载/部署得到的是改后效果。

**不做（v1）**：
- 改文字内容、增删/移动元素、结构级布局 → 走 AI 修改（文字要改 HTML 文本节点，结构级对通用源码做忠实回写成本高）。
- vue 模式（预览是 dist 构建产物，追加覆盖样式需重建，另议）。
- 拖拽、多选、Figma 式编辑器。

---

## 二、交互设计（沿用现有编辑卡片）

1. 「可视化编辑」开启 → hover 高亮 → 点选元素（现有逻辑）。
2. 选中后编辑卡片**上方多一个「样式」页签**（与「AI 修改」并列）：
   - 元素信息行（tag + 选择器，可复制）；
   - 属性控件（Ant Design 即可）：文字颜色(取色器)、背景色、字号(滑块/输入)、字重(选择)、对齐(单选)、margin/padding(数字)、圆角、宽度(选择 auto/100%/数字)。
   - 只显示选中元素**实际生效**的当前值（从 `getComputedStyle` 读），改动即时在面板预览。
3. 「应用到页面」→ 调后端追加一条覆盖规则 → 重新拉代码刷新预览；编辑卡片底部出现「手动修改记录」与「撤销最近一次 / 清空手动修改」。
4. 选择器默认取点选的精确路径；也提供简写选项（仅 class/id），供用户扩大或收窄影响范围。

---

## 三、持久化方案（关键决策：带标记追加进 styles.css）

**为什么直接写 `css/styles.css` 而不是新建 `user-edits.css`**：
- html / multi_file 的 `index.html` 只 `<link>` 了 `css/styles.css`；新建文件不会被引用，预览/下载/部署都失效，还得改 `readProjectCode` 拼接 + 处理下载/部署不含新 link 的问题 → 绕。
- 追加进 styles.css：`readProjectCode`（预览代码视图）、下载、部署**天然全部生效**，一行代码都不用为它改。

**规则格式（带标记，便于撤销）**：
```css
/* @user-edit:2f3c9a */
body > section.hero > h1.hero__title { color:#1677ff; font-size:40px; }
/* @end-user-edit */
```
- 追加总是落在文件末尾，不影响原 AI 生成内容；
- **撤销最近一次** = 去掉最后一个 `@user-edit…@end-user-edit` 块；
- **清空手动修改** = 去掉所有标记块；
- 客户端选择器来自 DOM，需做简单净化（去掉 `{};'"` 等，防注入破坏 CSS 文件结构）。

---

## 四、后端改动

| 位置 | 改动 |
|---|---|
| `CodeFileWriteService.java` | 加两个小工具：`appendToFile(projectDir, relPath, text)`；`stripMarkedBlocks(fileContent)`（按 `@user-edit`/`@end-user-edit` 正则删除标记块）。写盘用现有 `FileUtil.appendUtf8String` + `saveProjectFile` 覆盖 |
| 新增接口 `POST /ai/manual-edit/apply` | 入参 `appId`、`css`（一条或多条规则文本）。校验：登录、owner、`type != VUE`、`css` 长度上限(如 2000)。把 `css` 用标记包裹追加到 `css/styles.css`，返回 ok |
| 新增接口 `POST /ai/manual-edit/undo` | 入参 `appId`、`mode`(last / all)。同校验；`last`=去最后一个标记块，`all`=去所有标记块；写回 styles.css |
| `VueProjectAgent` / facade | 无需改动（不走 agent） |

> 更稳妥可把这些文件操作放一个 `ManualEditService`（依赖 CodeFileWriteService），避免 facade 变胖。控制层沿用 `AiChatController` 或 `AppController`。

---

## 五、前端改动（集中在 AppGeneratePage + 复用 api）

| 位置 | 改动 |
|---|---|
| 编辑卡片 | 加「样式 / AI」页签；样式页签放属性控件；沿用 `editTarget`/`cssPathOf` |
| 取当前值 | 读 iframe 内 `getComputedStyle(selectedEl)` 初始化控件默认值 |
| 生成规则 | `buildCssRule(selector, props)` 拼接成 `选择器 { prop:value; ... }` |
| apply | `manualEditApply(appId, css)` → 成功 → `getAppCode` 拉最新 → `buildPreviewDoc` 重建 srcdoc → iframe reload（现有 `@load` 自动重新武装点选） |
| undo | `manualEditUndo(appId, 'last'|'all')` → 同上刷新 |
| api | `aiChatController.ts`（或新建 `manualEdit.ts`）加 `manualEditApply` / `manualEditUndo` |

> 实时反馈技巧：apply 成功前可先在预览 iframe 内临时注入一条 `<style>`（仅本次会话可见），后端成功返回后再真正落盘刷新，避免每调一次都整页刷新；落地后刷新一次即可。

---

## 六、分阶段与工作量

| 阶段 | 内容 | 相对工作量 |
|---|---|---|
| 1 | 后端 `appendToFile`/`stripMarkedBlocks` + apply/undo 两接口 + curl 验证追加/撤销/越权 | ★ |
| 2 | 前端：编辑卡片加「样式」页签 + 属性控件 + buildCssRule + apply→刷新 | ★★ |
| 3 | 撤销按钮 + 选择器净化 + 取色器/控件打磨 + 边界（元素无 class、id 带特殊字符） | ★★ |
| 4 | 可选：把文字改并入（按选择器定位文本节点替换，仅 html 简单结构可靠） | ★★（另评） |

预计 1+2+3 合起来在**几天量级**，风险集中在 2/3 的控件与选择器净化，后端非常简单。

## 七、验证口径
- 多文件应用：点选标题改色/改字号 → 预览立变、代码视图 css 末尾出现标记块、下载 zip 的 css 含覆盖规则；撤销最近一次 → 恢复。
- 单文件 html（也会产出 css/styles.css）同样走通。
- 越权：他人 appId → 40101/40100。

## 八、开放问题（可后续定）
1. 属性子集初版你希望给到多少？（建议先 ~10 个高频项，够用再扩）
2. 「撤销」粒度：最近一次 + 清空全部 是否够？要不要「逐条历史列表」（需额外存 manifest）？
3. 单文件 html 若无独立 css（极端 case 内联全包在 html）时，手动样式要不要也做（需注入 `<style>` 到 html）？
