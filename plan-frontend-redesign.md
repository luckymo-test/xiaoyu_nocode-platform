# 计划书：前端视觉与交互改造（舒适 · 酷炫 · 按键易用）

> 日期：2026-10-10
> 范围：`xiaoyuZeroCode-front`（Vue 3 + Vite + TS + Ant Design Vue 4.2.6），纯前端改造
> 目标：把"后台管理系统"观感改造成"AI 创作工作台"观感——风格舒适且有科技感，操作按钮排版让人一眼知道该点哪个
> 不做的事：不改后端接口、不动业务逻辑、不引入重型 UI/动画依赖、不重写 `AppGeneratePage` 的 script 逻辑

---

## 一、结论先行

1. **"不好看"的根因不是配色丑，而是没有设计系统。** 全站只有 `reset.css` 一份 antd 默认样式，没有主题配置、没有 CSS 变量、没有全局样式文件；颜色硬编码散落在 7 个 SFC 里共 **98 处**（其中 `AppGeneratePage.vue` 一个文件 73 处）。同一套蓝色在 6 个文件里各写一遍，风格自然统一不起来。
2. **改造要"先底座、后页面"。** 第一批只做 Design Tokens + `ConfigProvider` + 全局样式 + 页面容器，全站外观立刻变样，且风险最低、不需要动任何业务代码。
3. **"按键排版"是独立于美化的一个问题，需要一套规范。** 现在多处是"5 个按钮平铺、主次不分、危险操作和安全操作长得一样"，这会直接带来误操作。本计划书第六章给出按钮层级、分组、尺寸、确认规则的完整规范。

---

## 二、现状诊断

### 2.1 客观证据

| 项 | 现状 | 证据 |
|---|---|---|
| 主题配置 | 完全没有 | 全项目搜不到 `ConfigProvider` / `theme=`；`main.ts` 只有 `import 'ant-design-vue/dist/reset.css'` |
| 全局样式 | 不存在 | `src/**/*.css` 搜索结果为空，样式全部写在 SFC 的 `<style scoped>` 里 |
| 颜色硬编码 | 98 处 16 进制色值，分布在 7 个文件 | `AppGeneratePage.vue` 73、`AdminChatPage.vue` 9、`MarkdownView.vue` 6、`HomeView.vue` 4 |
| 导航 | antd 默认 `theme="dark"` 深蓝菜单（`#001529`），2018 年后台默认脸 | `GlobalHeader.vue` |
| 页脚 | 固定深色条，内容为空 `<div class="footer-content"></div>`，白占 50px | `GlobalFooter.vue` |
| 主内容区 | `background: #f5f5f5` + `padding: 24px`，无最大容器、无页面头 | `BasicLayout.vue` |
| 布局常量 | `height: calc(100vh - 64px - 50px - 48px)` 魔法数字，与布局强耦合 | `AppGeneratePage.vue` |
| 折叠把手 | `.panel-toggle` 宽 **18px**，点击热区远小于可用下限 | `AppGeneratePage.vue` |
| 生成页顶部 | 5 个按钮平铺：停止生成 / 打开网站 / 下载代码 / 部署 / 下线，"部署"和"下线"是同一个按钮靠文案切换 | `AppGeneratePage.vue` 模板 |
| 表格行内操作 | 4 个 `type="link" size="small"` 平铺：进入对话 / 编辑 / 申请精选 / 删除 | `MyProjectsPage.vue` |
| 工具栏 | 搜索 + 搜索按钮 + 重置 + 新建 + 批量删除 平铺一行，主次不分 | `MyProjectsPage.vue`、`AdminUserPage.vue`、`AdminChatPage.vue` |
| 登录/注册 | 400px 白卡贴在灰底上，无品牌氛围；两页样式几乎重复 | `UserLoginPage.vue`、`UserRegisterPage.vue` |
| 菜单死链 | 菜单有"模板市场""文档"，但 `router/index.ts` 里没有 `/templates`、`/docs` 路由，点了是空白页 | `config/menu.ts` vs `router/index.ts` |
| 菜单高亮 | `selectedKeys` 初始化一次，路由变化（返回/重定向）后不同步；`/app/gen/:id`、`/app/edit/:id` 不在菜单里，永远无高亮 | `GlobalHeader.vue` |
| 反馈与空态 | 全站只有 `message.xxx` 顶层 toast；列表 loading 只有 `a-spin`，无骨架屏；空态是 antd 默认 `a-empty` | 各页面 |
| 移动端 | 仅零散 `@media (max-width:768px)`；header 菜单不折叠，表格横向滚动 | `BasicLayout.vue`、`AppGeneratePage.vue` |

### 2.2 归纳成五个真问题

1. **没有设计系统** → 想统一也统一不了，改一处要改 N 个文件。
2. **外壳是"管理后台"** → 深蓝菜单 + 空页脚 + 灰底 + 方卡片，和"用 AI 生成应用"这件事的气质不搭。
3. **操作区无层级** → 主操作、次操作、危险操作视觉权重接近，用户需要读文字才能判断，容易误点。
4. **核心页信息结构粗** → 生成页是使用频率最高的页面，但对话区/预览区比例固定不可调、AI 气泡和用户气泡样式朴素、思考过程是素 `<details>`、预览工具按钮和标题挤在一行。
5. **缺状态设计** → 无骨架屏、无统一空态、无页面级加载和错误态，加载过程显得"卡"。

---

## 三、改造目标与设计原则

把"舒适酷炫"翻译成可验收的标准：

| 关键词 | 落地标准 |
|---|---|
| **舒适** | 单一强调色 + 克制的浅灰底；正文对比度 ≥ 4.5:1；圆角/阴影/字号有统一阶梯；每屏留白充足；长对话 + 长代码盯久了不累 |
| **酷炫** | 品牌渐变色（蓝→紫）作为记忆点；顶部毛玻璃导航；卡片"悬浮在光上"；生成中有光带进度、流式光标；代码/预览区用局部深色形成对比 |
| **按键易用** | 每屏每区最多 1 个主按钮；按钮按"筛选区 / 操作区"分组；危险操作收进"更多"或二次确认；图标 + 文案；热区 ≥ 32px（移动端 ≥ 40px）；快捷键在 Tooltip 里提示 |

**三条设计原则（用来否决过度设计）**

- 酷炫靠"光、渐变、微动效、局部深色"实现，不靠花哨插画和大面积动效。
- 动效只用于表达状态（进入、悬停、加载、成功），不做纯装饰动画；尊重 `prefers-reduced-motion`。
- 所有视觉值走 token，页面里不写死颜色/圆角/阴影。

---

## 四、设计系统（Design Tokens）

### 4.1 主色方案

给出两套候选，**推荐 A**（首页 hero 已经在用 `#1677ff → #722ed1` 渐变，A 是对它的收敛升级，改动成本最低）。

| 方案 | 主色 | 渐变 | 气质 |
|---|---|---|---|
| **A 星夜蓝紫（推荐）** | `#4F6BFF` | `135deg, #5B7CFF → #A855F7` | AI/创作工具感，和现有 hero 渐变同源 |
| B 青蓝科技 | `#1677FF` | `135deg, #1677FF → #22D3EE` | 更"工程/云服务"，偏冷静 |

### 4.2 Token 清单（写入 `src/styles/tokens.css` 作为 CSS 变量）

```css
/* 品牌 */
--brand-50:#EEF1FF;  --brand-100:#E0E6FF;  --brand-300:#A5B4FF;
--brand-500:#4F6BFF; --brand-600:#3D57F0;  --brand-700:#2F45D6;
--accent-500:#A855F7; --accent-400:#22D3EE;
--gradient-brand: linear-gradient(135deg, #5B7CFF 0%, #A855F7 100%);

/* 背景层级 */
--bg-app:#F5F6FB;      /* 页面底 */
--bg-surface:#FFFFFF;  /* 卡片 */
--bg-sunken:#EEF0F7;   /* 凹槽/输入框底 */
--bg-hover:#F2F4FA;
--bg-glass: rgba(255,255,255,.72);   /* 毛玻璃导航 */

/* 文字与描边 */
--text-1:#1B1F2E; --text-2:#5A6076; --text-3:#9AA0B4; --text-inverse:#FFFFFF;
--border:#E6E8F2; --border-strong:#D6DAE8;

/* 语义 */
--success:#22C55E; --warning:#F59E0B; --error:#EF4444; --info:#3B82F6;

/* 局部深色工作区（代码预览 / 文件树 / reasoning） */
--dark-bg:#0E1120; --dark-bg-2:#161A2E; --dark-border:rgba(255,255,255,.08);
--dark-text-1:#E6E8F5; --dark-text-2:#9BA3BE;

/* 圆角 / 阴影 / 间距 / 字号 / 动效 */
--radius-xs:6px; --radius-sm:8px; --radius-md:10px; --radius-lg:14px; --radius-xl:20px; --radius-pill:999px;
--shadow-1:0 1px 2px rgba(20,26,48,.05), 0 2px 6px rgba(20,26,48,.06);
--shadow-2:0 8px 24px rgba(20,26,48,.10);
--shadow-glow:0 12px 32px rgba(79,107,255,.22);
--space-1:4px; --space-2:8px; --space-3:12px; --space-4:16px; --space-5:24px; --space-6:32px; --space-7:40px; --space-8:56px;
--font-xs:12px; --font-sm:13px; --font-base:14px; --font-lg:16px; --font-xl:18px; --font-2xl:22px; --font-3xl:30px;
--dur-fast:120ms; --dur:200ms; --dur-slow:320ms; --ease:cubic-bezier(.4,0,.2,1);

/* 布局常量（替掉散落各处的 64/50 魔法数字） */
--header-h:64px; --page-max:1200px; --content-radius:16px;
```

### 4.3 antd 主题（`src/config/theme.ts` + `App.vue` 挂 `a-config-provider`）

```ts
export const antdTheme: ThemeConfig = {
  token: {
    colorPrimary: '#4F6BFF',
    colorInfo: '#4F6BFF',
    colorSuccess: '#22C55E',
    colorWarning: '#F59E0B',
    colorError: '#EF4444',
    colorTextBase: '#1B1F2E',
    colorBgLayout: '#F5F6FB',
    colorBorderSecondary: '#E6E8F2',
    borderRadius: 10,
    borderRadiusLG: 16,
    controlHeight: 36,
    controlHeightLG: 44,
    fontFamily: "'Inter','PingFang SC','Microsoft YaHei',system-ui,sans-serif",
    fontSize: 14,
  },
  components: {
    Button:   { borderRadius: 10, controlHeightLG: 44, primaryShadow: 'none', fontWeight: 500 },
    Card:     { borderRadiusLG: 16, paddingLG: 24, boxShadowTertiary: 'none' },
    Menu:     { itemHeight: 40, itemBorderRadius: 10, horizontalItemBorderRadius: 10, itemMarginInline: 4 },
    Table:    { headerBg: '#F7F8FC', headerColor: '#5A6076', headerSplitColor: 'transparent', rowHoverBg: '#F6F8FF', borderColor: '#EEF0F7' },
    Modal:    { borderRadiusLG: 16 },
    Drawer:   { paddingLG: 24 },
    Tag:      { borderRadiusSM: 6 },
    Layout:   { headerBg: 'transparent', bodyBg: 'transparent', footerBg: 'transparent' },
  },
}
```

配套两处细节（容易踩）：

- `App.vue` 里 `<a-config-provider :theme="antdTheme" :locale="zhCN">` **包在 `<a-app>` 外面**，并把 `main.ts` 里的 `reset.css` 换成 `styles/index.css`（内含 reset + tokens + global）。
- 现在全站的 `message.xxx` 是静态调用，**不会继承 ConfigProvider 的主题**（`Modal.confirm` 同理）。建议统一迁移到 `App.useApp()` 拿到的 `message/modal` 实例；这项工作可以放在批次 5 慢慢替换，不阻塞前面批次。

### 4.4 全局样式 `src/styles/global.css`

- 字体栈 + `font-feature-settings`、`-webkit-font-smoothing: antialiased`
- 滚动条美化（细、圆、hover 加深；深色区一套）
- `:focus-visible` 统一的品牌色外发光环（可访问性硬要求）
- `::selection` 品牌色浅底
- 页面进入动画：`@keyframes page-in { from {opacity:0; transform:translateY(6px)} }`，200ms，`prefers-reduced-motion` 下关闭
- 背景光晕层 `.app-bg-glow`（两枚径向渐变，`position: fixed`，`pointer-events:none`，`z-index:0`）

---

## 五、整体视觉语言

四个手法，全部用 CSS 实现，不新增图片资源：

1. **光感背景**：页面底 `--bg-app` + 两枚径向渐变光晕（左上蓝 `rgba(91,124,255,.18)`、右上紫 `rgba(168,85,247,.14)`），固定不随滚动 → 卡片"悬浮在光上"。
2. **毛玻璃导航**：`position: sticky` + `background: var(--bg-glass)` + `backdrop-filter: blur(14px) saturate(150%)`；滚动超过 8px 时加 `--shadow-1` 和底部描边（用一段 scroll 监听或 `IntersectionObserver` 加 class）。移动端降级为不透明白底（避免 `backdrop-filter` 性能问题）。
3. **克制的动效**：卡片 hover `translateY(-2px)` + `--shadow-2`；按钮 `:active { transform: scale(.98) }`；主按钮 hover 有一道 200ms 斜向光泽扫过；AI 消息逐条淡入上滑；生成中顶部一条 2px 光带进度。
4. **局部深色**：代码预览、文件树、`reasoning` 折叠区、`style-panel` 用深色 token，和浅色主界面形成"工作区"对比，也让代码更耐看。

### 外壳线框（改造后）

```
┌───────────────────────────────────────────────────────────────────┐
│ ✦ 小玉零代码    首页  我的项目  模板市场  文档        [头像 ▾]     │ ← 毛玻璃 sticky, 高 64
├───────────────────────────────────────────────────────────────────┤
│  ░░░ 固定光晕层（左上蓝 / 右上紫）░░░                              │
│                                                                   │
│        ┌──────────── 页面容器 max-width 1200 · padding 24 ─────┐  │
│        │  页面头：标题 + 一句描述              [主操作按钮]     │  │
│        │  ────────────────────────────────────────────────    │  │
│        │  内容区（卡片 = 白底 + 浅描边 + --shadow-1 + 16 圆角） │  │
│        └──────────────────────────────────────────────────────┘  │
│                                                                   │
└───────────────────────────────────────────────────────────────────┘
```

### 生成页线框（改造后）

```
┌ 生成页 ───────────────────────────────────────────────────────────┐
│ ‹ 返回   应用名   [Vue3] [生成中]      [打开网站] [下载] [部署 ▾]  │
├──────────────────────────────┬────────────────────────────────────┤
│  对话区                       ┊  预览区（分隔条可拖拽）              │
│  ┌ AI 白卡 ────────────────┐  ┊  [ 代码 | 网页 ]   [编辑] [撤销]    │
│  │ 说明文字                 │  ┊  ┌─────────┬──────────────────┐   │
│  │ ▸ 思考过程（深色块）      │  ┊  │ 文件树   │ 代码 / 网页预览   │   │
│  │ 📄 index.html    12 KB   │  ┊  │ (深色)  │ (深色 / 白色)     │   │
│  └──────────────────────────┘  ┊  └─────────┴──────────────────┘   │
│                        [用户渐变气泡]┊                              │
├──────────────────────────────┴────────────────────────────────────┤
│ [ 快捷指令 chips：改成暗色主题 · 加一个登录页 · 换成卡片布局 ]        │
│ [ 描述你的需求…                              ] [ 发送  Ctrl+Enter ] │
└───────────────────────────────────────────────────────────────────┘
```

---

## 六、按键与操作区排版规范（本次改造的核心诉求）

### 6.1 按钮层级

| 层级 | 视觉 | 用途 | 硬规则 |
|---|---|---|---|
| **Primary** | 品牌渐变实心 | 当前页面/区域最重要的下一步 | 每屏最多 1 个；每个操作组最多 1 个 |
| **Secondary** | 白底 + 描边 | 常规并列操作（下载、编辑、打开网站） | 可多个，间距 12px |
| **Ghost / Text** | 无底或极浅底 | 行内操作、三级入口、关闭 | 表格行内优先用它 |
| **Danger** | 红字 / 红边（`ghost danger`） | 删除、下线、永久删除 | 永不与 Primary 相邻；批量/不可逆操作一律收进"更多 ▾"或独立分组 |

### 6.2 尺寸与热区

| 场景 | 高度 | 说明 |
|---|---|---|
| 页面主操作 | 40–44px（`large`） | 首页"创建应用"、生成页"部署" |
| 工具栏按钮 | 32–36px（`middle`） | 搜索、重置、筛选 |
| 表格行内 | 24–28px（`small` / `text`） | 且数量 ≤ 2，其余进"更多 ▾" |
| 纯图标按钮 | 视觉 18px，**热区 ≥ 32px** | 生成页折叠把手由 18px 提到 6px 视觉条 + 12px padding（总 ≥ 24px），并加 hover 提示 |
| 移动端 | ≥ 40px | 触屏下限 |

### 6.3 分组与排序（工具栏统一模板）

```
[ 左：筛选组 ]                                           [ 右：操作组 ]
输入框  查询  重置                        次操作  次操作 │ 更多 ▾  主操作
```

- 左右两组用 `justify-content: space-between` + `flex-wrap: wrap` + `gap: 12px`；小屏自动换行、不横向挤压。
- 破坏性操作永远在操作组最右、视觉最弱，或直接收进"更多 ▾"。
- 组内用小竖线分隔（`--border` 1px，高 20px）分隔"查询"与"重置"这类语义不同的按钮。

### 6.4 文案与图标

- 文案动词开头且自解释："新建应用"（不是"新建"）、"下载代码"（不是"下载"）、菜单里的"下线部署"（不是"下线"）。
- 主操作必须带图标（`@ant-design/icons-vue`，16px 内联 / 18px 主按钮）；纯图标按钮必须挂 `a-tooltip`。
- 快捷键写进 Tooltip 和按钮右侧："发送 `Ctrl + Enter`"。

### 6.5 危险操作确认

- 可逆小操作（删除到回收站、恢复）→ `a-popconfirm`，标题写明后果。
- 不可逆操作（永久删除、批量永久删除）→ `Modal.confirm`，正文列出影响条目数，确认按钮文案为动词"永久删除"且 `danger`。
- 批量操作按钮在未选中时 `disabled`，并在按钮旁显示已选数量（如"删除所选 (3)"）。

### 6.6 逐页 Before → After

| 页面 | 现在 | 改造后 |
|---|---|---|
| 首页创作区 | `[公开 ▾] [模式 ▾] [创建应用]` 三个控件右对齐平铺 | 输入框为视觉主角；下方一行：可见性 / 生成模式做成**分段控件**，右侧 `创建应用`（大号渐变 + 图标）；再加一行"示例提示词 chips"降低冷启动成本 |
| 生成页顶部 | 5 个按钮平铺，"部署/下线"混用同一按钮 | `‹ 返回 · 应用名 · 状态标签` 一组；右侧 `打开网站`(次) `下载代码`(次) `更多 ▾`（内含 下线部署、删除应用） `部署`(主，渐变)；生成中额外出现 `停止生成`（danger ghost，独立在最左，避免误点主按钮） |
| 生成页预览头 | 下拉选模式 + 编辑 + 撤销 + 状态文字挤一行 | 模式改**分段切换**（代码 / 网页）；工具按钮图标化 + Tooltip；状态文字改为右侧小徽标 |
| 生成页输入区 | textarea + 发送 | 上排快捷指令 chips（可点即填）；输入框带聚焦发光环；发送按钮 `Ctrl+Enter` 提示；生成中按钮变 `停止` |
| 我的项目 | 搜索/搜索/重置/新建/批量删除 一行 | 左：搜索 + 查询 + 重置；右：`更多 ▾`（批量删除、导出） + `新建应用`(主)。表格操作列：`进入对话`(primary text) `编辑` … `更多 ▾`(申请精选 / 删除到回收站) |
| 回收站 | 批量恢复 / 批量永久删除 并列 | `批量恢复`(次) + `更多 ▾`(批量永久删除)；未选中时禁用并显示已选数量 |
| 管理端三页 | 搜索 + 搜索 + 重置 平铺，表格默认样式 | 统一工具栏模板 + 统一表格样式；`AdminChatPage` 的抽屉头部把"导出 Markdown"从 `extra` 挪进工具栏 |
| 登录/注册 | 白卡 + 全宽蓝按钮 | 左右分栏：左侧品牌区（渐变光 + 产品一句话 + 三条卖点），右侧表单卡；输入框 `large`、按钮渐变全宽；两页共用一个 `AuthLayout.vue` |

---

## 七、分页面改造清单

### 7.1 外壳 `BasicLayout` / `GlobalHeader` / `GlobalFooter`

- 背景换 `--bg-app` + 光晕层；主内容区加 `<PageContainer>`（max-width 1200、居中、响应式 padding）。
- 删除空的固定页脚，改为**页内页脚**（一行小字：版本号 + 文档链接 + 备案位），不再固定占位 50px；`--header-h` 提为变量。
- 导航改毛玻璃 + 胶囊高亮（选中项渐变底白字）；菜单项加图标；右侧用户区：头像 + 下拉（个人中心 / 我的项目 / 退出登录）。
- **修复死链**：`/templates`、`/docs` 要么补路由（先放"敬请期待"占位页），要么从 `menu.ts` 移除。
- **修复高亮**：`selectedKeys` 改为 `computed(() => { if (route.path.startsWith('/app/')) return 'projects'; return String(route.name) })`。
- 移动端：折叠为汉堡 + 抽屉菜单。

### 7.2 首页 `HomeView`

- Hero：标题字号提到 40–44px 用品牌渐变；副标题一句话；增加一枚渐变光晕/badge（如"AI 驱动 · 一句话生成应用"）。
- 创作台卡片：输入框加大（minRows 3）、聚焦发光、`Ctrl+Enter` 提示；模式/可见性改分段控件；创建按钮大号渐变 + 图标；下方示例提示词 chips（点击填入）。
- 三个列表区块（我的应用 / 精选 / 公开）**从纵向堆叠改为分段 Tab**，减少滚动长度；搜索框统一进工具栏模板。
- 抽出 `AppCard.vue`：封面 16:9 + 悬停抬升 + 名称/描述两行截断 + 底部标签 chip + 右上角快捷操作（进入/编辑）；封面缺失时用品牌渐变 + 首字母占位（现有逻辑保留）。
- 表格视图保留，但套统一表格样式 + 骨架屏首屏加载。

### 7.3 生成页 `AppGeneratePage`（分三个子批次做，避免一次性大改）

- **3a 顶部与外壳**：`PageHeader` 化 + 按钮分组重排（见 6.6）；`--header-h` 变量替掉魔法数字；`panel-toggle` 热区放大、加 hover 提示与拖拽光标。
- **3b 对话区**：空态引导卡（3 条示例指令）；消息气泡重做（用户 = 品牌渐变气泡 + 右侧头像，AI = 白卡 + 细描边 + 头像带渐变底）；`reasoning` 折叠区改深色代码块；文件清单条目 hover 高亮 + 右侧"查看代码"提示；生成中加光带进度 + 工具调用时间线；token 元信息改右侧小徽标。
- **3c 预览区**：模式切换改分段控件；**分隔条可拖拽**，比例存 `localStorage`（新增 `useResizablePanel` composable）；文件树深色化 + 选中态品牌色；代码区深色主题 + 可选行号；`edit-card` 改玻璃拟态浮层（毛玻璃 + 强阴影）、样式面板控件全部 token 化。

### 7.4 我的项目 `MyProjectsPage`

- 工具栏按 6.3 模板重排；Tab 标题加数量徽标（我的应用 12 / 回收站 3）。
- 表格套统一样式：表头浅灰、行 hover 高亮、`row-selection` 选中行淡蓝底。
- 状态列统一为 chip 语义色（类型 / 可见性 / 生成状态 / 精选状态），色值走 token。
- 操作列瘦身到 ≤ 2 个直接按钮 + `更多 ▾`。
- 新建弹窗：`a-form` 加 `rules` 校验（现在是手写 if + message，能保留但视觉上补必填星号与错误态）。

### 7.5 管理端 `AdminUserPage` / `AdminAppPage` / `AdminChatPage`

- 统一 `PageContainer` + 工具栏模板 + 表格样式（可直接复用 7.4 的样式类，建议抽 `styles/table.css`）。
- `AdminChatPage` 抽屉：消息气泡复用生成页样式（抽 `ChatMessage.vue` 组件），头部 meta 改 chip 一行。
- 管理操作（审核通过/拒绝/置顶/取消推荐）用图标 + Tooltip + 语义色，危险操作二次确认。

### 7.6 登录 / 注册 / 个人中心

- 新增 `AuthLayout.vue`：左品牌区（渐变光晕 + 产品名 + 一句 slogan + 三条卖点），右表单卡；`≤ 900px` 退化为单列（隐藏左栏）。
- 登录/注册输入框 `large` + 图标前缀保留，按钮渐变全宽，底部次要链接改 `text` 按钮。
- 个人中心套 `PageContainer`，表单卡分组（基本资料 / 头像预览），保存按钮固定在卡片底部右侧。

---

## 八、文件与组件落地清单

**新增**

| 文件 | 作用 |
|---|---|
| `src/styles/tokens.css` | 设计 token（CSS 变量） |
| `src/styles/global.css` | 全局基础样式、滚动条、焦点环、页面进入动画、光晕层 |
| `src/styles/index.css` | 汇总入口（reset + tokens + global + antd 覆盖） |
| `src/config/theme.ts` | antd `ThemeConfig` |
| `src/components/PageContainer.vue` | 页面外壳：标题 + 描述 + 右侧操作槽 + 容器宽度 |
| `src/components/ToolBar.vue` | 统一工具栏（左筛选 / 右操作 / 更多菜单） |
| `src/components/AppCard.vue` | 首页与应用列表统一卡片 |
| `src/components/EmptyState.vue` | 统一空态（插画位 + 文案 + 主操作） |
| `src/components/ChatMessage.vue` | 对话气泡（生成页 + 管理端复用） |
| `src/components/AuthLayout.vue` | 登录/注册分栏外壳 |
| `src/composables/useResizablePanel.ts` | 生成页可拖拽分隔条 |
| （可选）`src/views/dev/StyleGuidePage.vue` | 仅开发环境路由 `/_styleguide`：展示 token、按钮矩阵、表格、卡片，用于肉眼验收与后续维护 |

**修改**：`main.ts`、`App.vue`、`layouts/BasicLayout.vue`、`layouts/GlobalHeader.vue`、`layouts/GlobalFooter.vue`、`config/menu.ts`、`router/index.ts`、`views/HomeView.vue`、`views/app/AppGeneratePage.vue`、`views/app/AppEditPage.vue`、`views/projects/MyProjectsPage.vue`、`views/admin/*.vue`、`views/user/*.vue`、`components/MarkdownView.vue`（代码块主题跟随 token，深色区一套高亮样式）。

**不改**：`src/api/**`、`src/utils/**`、`src/stores/**`、`request.ts`、SSE 逻辑——本次是纯表现层改造。

---

## 九、性能与可访问性约束

- 动效只用 `transform` / `opacity`；统一 `--dur`；`@media (prefers-reduced-motion: reduce)` 下关闭所有位移动画。
- `backdrop-filter` 只用于导航和浮层，移动端降级为不透明背景。
- 光晕/渐变全部 CSS 实现，不新增图片，不引入动画库（保持 bundle 体积不变）。
- 正文对比度 ≥ 4.5:1；所有交互元素有 `:focus-visible` 焦点环；纯图标按钮必须有 `aria-label` + Tooltip。
- 深色代码区的高亮样式单独引入一套（现在是 `highlight.js/styles/github.css`，深色区需要对应的 dark 主题）。

---

## 十、实施批次与验收

| 批次 | 内容 | 验收标准 | 相对工作量 |
|---|---|---|---|
| **0 设计底座** | tokens + global + antd theme + ConfigProvider + `PageContainer` + （可选）`/_styleguide` | 全站颜色/圆角/字体立刻统一；`npm run build` 通过；styleguide 能看按钮矩阵 | ★★ |
| **1 外壳与导航** | BasicLayout / Header / Footer / 移动端抽屉 / 死链与高亮修复 | 导航变毛玻璃胶囊高亮；页脚不再空占位；`/templates`、`/docs` 不再是空白页；小屏可折叠菜单 | ★★ |
| **2 首页 + 登录注册 + 个人中心** | Hero、创作台、AppCard、分段 Tab；AuthLayout | 首页首屏像"创作产品"；三个列表不再纵向堆到底；登录页有品牌感 | ★★★ |
| **3 生成页** | 3a 顶部按钮 → 3b 对话区 → 3c 预览区（拖拽 + 深色） | 顶部主次分明无歧义；对话空态/气泡/思考过程/文件清单成型；分隔条可拖拽且刷新后记忆比例 | ★★★★ |
| **4 列表与管理端** | 工具栏模板、表格样式、chip、操作列瘦身、抽屉复用 | 所有表格页视觉一致；行内按钮 ≤ 2 个；危险操作有二次确认 | ★★★ |
| **5 打磨与回归** | 微动效、骨架屏、空态、快捷键、`App.useApp()` 迁移、响应式回归 | 1440 / 1024 / 390 三档宽度无破版；无横向溢出；动效不卡顿 | ★★ |

每批独立可上线，建议 **0 + 1 合并成一次提交**（视觉收益最大、风险最低），2 → 3 → 4 顺序推进。

### 验证方式

每批完成后做一次三档宽度（1440 / 1024 / 390）截图对比，覆盖 6 个关键界面：登录、首页、生成页（生成中 / 生成后）、我的项目、管理端表格、个人中心。

---

## 十一、风险与对策

| 风险 | 说明 | 对策 |
|---|---|---|
| `AppGeneratePage.vue` 2195 行，模板与逻辑强耦合 | 结构大改容易碰坏生成/预览逻辑 | 分批做（3a/3b/3c）；只改 class 与模板片段，不动 `script` 逻辑；每批 `npm run build`（`vue-tsc`）必须过 |
| antd token 影响面大 | 改主题会波及全部 antd 组件（表格/分页/抽屉/弹窗） | 批次 0 完成后先把所有页面过一遍；分页、表格、抽屉是重点回归项 |
| `scoped` + `:deep()` 覆盖优先级混乱 | 覆盖 antd 样式容易失效或"飘" | 统一在 `styles/index.css` 里用 token 覆盖，页面内尽量只写布局；禁止再新增硬编码色值 |
| 静态 `message` 不继承主题 | 迁 `App.useApp()` 涉及大量调用点 | 排到批次 5，按文件逐步替换，不做一次性全量替换 |
| 移动端毛玻璃性能 | 低端机 `backdrop-filter` 掉帧 | `≤768px` 降级为不透明背景 |
| 视觉改动无自动化测试 | 只能靠肉眼 | 建立三档宽度截图基线；可选 `/_styleguide` 页面作为长期视觉基线 |

---

## 十二、工作量与排期

| 批次 | 预计人日 | 说明 |
|---|---|---|
| 0 设计底座 | 1–1.5 | token 表 + 主题 + 全局样式 + 容器组件 |
| 1 外壳与导航 | 1 | 含移动端抽屉、死链/高亮修复 |
| 2 首页 + 认证页 | 2 | 含 AppCard、分段 Tab、AuthLayout |
| 3 生成页 | 3–4 | 核心页，三个子批次 |
| 4 列表与管理端 | 2 | 表格样式与工具栏规范化 |
| 5 打磨回归 | 1–1.5 | 动效、骨架屏、响应式、message 迁移 |
| **合计** | **10–12 人日** | 纯前端，无后端依赖，可分批交付 |

如果只想先要"一眼变好看"的效果：**先做批次 0 + 1（约 2.5 人日）**，全站配色、导航、页脚、容器会立刻改观，且不碰任何业务页面。

---

## 十三、验收清单（Definition of Done）

- [x] 页面级零硬编码色值（`tokens.css` / `config/theme.ts` / `styles/antd.css` 是色值源头；`MarkdownView.vue` 的 markdown 与 hljs 语法配色属于"语法主题"palette，`AppGeneratePage.vue` 写进 iframe 内部的那行 outline 色值必须字面量，这三处为已记录的例外）
- [ ] 所有页面套用 `PageContainer`，最大宽度、padding、页面头一致
- [ ] 每个页面/操作组最多 1 个主按钮；危险操作不与主按钮相邻
- [ ] 表格行内直接操作 ≤ 2 个，其余进"更多 ▾"
- [ ] 所有图标按钮有 Tooltip 或 `aria-label`；点击热区 ≥ 32px（移动端 ≥ 40px）
- [ ] 不可逆操作有二次确认且确认按钮为危险语义动词
- [ ] 列表页有骨架屏，空态用统一 `EmptyState`，错误有明确提示与重试
- [ ] 1440 / 1024 / 390 三档宽度无破版、无横向溢出
- [ ] `prefers-reduced-motion` 下动效关闭；正文对比度 ≥ 4.5:1；`:focus-visible` 焦点可见
- [ ] `npm run build`（含 `vue-tsc`）通过；`src/api/**`、`src/utils/**` 无改动
- [ ] 菜单不再有死链；菜单高亮与当前路由一致

---

## 十四、进度记录

### 2026-10-10 · 批次 0（设计底座）+ 批次 1（外壳与导航）✅

**新增文件**

| 文件 | 内容 |
|---|---|
| `src/styles/tokens.css` | 全套 CSS 变量：品牌色 / 背景层级 / 文字描边 / 语义色 / 深色工作区 / 圆角 / 阴影 / 间距 / 字号 / 动效 / 布局常量 |
| `src/styles/global.css` | 字体与基础排版、滚动条、`:focus-visible` 焦点环、选区、路由页面进入动画、`prefers-reduced-motion` 降级 |
| `src/styles/antd.css` | antd 组件级统一：卡片 / 表格 / 分页 / 浮层 / 标签 / 主按钮渐变 / 输入聚焦环 |
| `src/styles/index.css` | 样式总入口（tokens → global → antd 覆盖） |
| `src/config/theme.ts` | antd 全局 token |
| `src/components/PageContainer.vue` | 统一页面外壳（标题 + 描述 + 右侧操作槽 + 窄版模式） |
| `src/views/ComingSoonPage.vue` | 模板市场 / 文档占位页 |

**修改文件**：`main.ts`（引入全局样式）、`App.vue`（`a-config-provider` + `zh_CN`）、`layouts/BasicLayout.vue`（`app-shell`：sticky 导航 + 统一容器 + 页内页脚 + 固定光晕层；生成页不显示页脚）、`layouts/GlobalHeader.vue`（毛玻璃吸顶 + 胶囊渐变高亮 + 导航图标 + 管理下拉 + 用户胶囊 + 移动端抽屉）、`layouts/GlobalFooter.vue`（空固定深色条 → 页内页脚）、`config/menu.ts`（菜单项补图标）、`router/index.ts`（补 `/templates`、`/docs` 路由修死链 + `meta.title` + 文档标题同步）、`public/logo.svg`（品牌渐变徽标）、`views/app/AppGeneratePage.vue`（`gen-page` 高度改用布局变量，去掉魔法数字）。

**实测踩到的坑**

- **antd-vue 4.2.x 的组件级 token 类型是空的**（`ComponentToken` 未实现），`components: { Button: {...} }` 会被 `vue-tsc` 拒绝。因此组件级视觉只能放在 `styles/antd.css`（用 `body` 前缀提高优先级压过运行时注入的 cssinjs 样式）。
- **删除 + 新增同名文件不能写在同一个 patch 里**，需要分两次提交补丁。

**验证结果**

- `npm run build`（`vue-tsc` + `vite build`）通过，无类型错误。
- 浏览器实测 1440×900 / 1024 / 390 三档宽度：导航胶囊高亮与滚动阴影正常、占位页正常、移动端抽屉（含关闭按钮）正常、登录页正常；`/` 未登录仍正确跳转登录页；控制台无报错与告警。
- 需要登录态的页面（首页、我的项目、生成页、管理端三页）尚未做视觉回归，留待批次 2/3/4 一并验收。

**已知遗留**

- 页面内仍有大量硬编码色值（`AppGeneratePage.vue` 73 处、`AdminChatPage.vue` 9 处等），随各页面改造逐步收敛到 token。
- 首页 Hero / 创作台 / 应用卡片、生成页顶部按钮分组与对话区/预览区、列表页工具栏结构，均未开始（批次 2/3/4）。

### 2026-10-10 · 品牌 logo + 批次 2（首页 / 认证页 / 个人中心）✅

**品牌**

- `public/logo.svg` 重做：渐变圆角磁贴 + 宝石（玉）四切面 + 右上星芒，替代原来的"渐变方块 + 文字"，16px 收藏夹图标下仍可辨识。
- `index.html`：favicon 从遗留的 `/vite.svg` 改为 `/logo.svg`，并补 `theme-color`。
- `GlobalFooter` 的小方块文字标也换成同一枚徽标。

**新增组件**：`AppCard.vue`（统一应用卡片：16:9 封面 + 悬停抬升 + 遮罩提示 + 标签行）、`AuthLayout.vue`（登录/注册左右分栏外壳 + 表单区通用排版）、`EmptyState.vue`（统一空态：图标 + 标题 + 描述 + 操作槽）。

**首页 `HomeView` 重构**

- Hero：徽标 + 42px 渐变标题 + 副标题。
- 创作台：输入框为视觉主角 + 示例提示词 chips（点一下填入）+ 可见性分段控件 + 生成模式下拉 + `Ctrl + Enter` 提示 + 渐变主按钮。
- 三个列表（我的应用 / 精选应用 / 公开应用）**由纵向堆叠改为分段 Tab**，并且**按需懒加载**（原来 `onMounted` 一次性打三个接口，现在切到哪个 Tab 才拉哪个）。
- 表格行内操作从 4 个平铺收敛为「进入对话 + 更多 ▾」；删除到回收站改用 `a-modal` 二次确认弹窗（不可逆操作不再用裸链接）。
- 精选/公开列表从 antd 默认卡片换成统一 `AppCard`；搜索工具栏统一为「左筛选 + 右主操作」；空态与骨架屏补齐。

**认证页**：登录/注册共用 `AuthLayout`（左侧渐变品牌区 + 右侧表单卡，≤900px 自动单列），输入框 `large`、主按钮全宽渐变。个人中心套 `PageContainer` + 头像预览。

**验证**

- `npm run build`（`vue-tsc` + `vite build`）通过。
- 1440 / 390 实测：登录页、注册页、文档占位页；首页做了一次冒烟（临时放开路由 + 临时关闭 401 跳转，验证后已还原，`git diff src/request.ts` 为空）。
- **带真实数据的回归仍需要登录态**：首页列表、生成页、我的项目、管理端三页要在登录后复验。

**新踩的坑**（已记入 `development-notes.md` #72）

- 路由守卫不是唯一的登录拦截点：`src/request.ts` 的响应拦截器在 `40100` 时会 `window.location.href` 硬跳登录页，即使把 `requiresAuth` 去掉，页面一挂载发请求也会被弹走。
- 无登录态做视觉验证的临时改动有两处（路由守卫 + 拦截器），验证完必须 `git diff` 复核还原。

### 2026-10-10 · 批次 3（生成页）✅

**3a 顶部操作区（本次按键排版的重点）**

- 由"5 个按钮平铺 + 部署/下线共用一个按钮"改成：左侧「返回 + 应用名 + 标签」一组，右侧按语义分层：
  - `停止生成`：生成中才出现，`danger ghost`，单独放最左，避免误点主按钮；
  - `打开网站`、`下载代码`：次要操作；
  - `更多 ▾`：**已部署时**才出现，里面放 `下线部署`（danger）——不再让"部署"按钮兼职下线；
  - `部署`：未部署时才是主按钮（渐变 + 云上传图标）。
- 部署状态文字改成 `--brand-50` 胶囊 + 转圈，不再是一行裸蓝色文字。
- 折叠把手从 18px 的窄条换成 20px 分隔条 + 20×40 的圆点按钮，热区翻倍且带 hover 提示。

**3b 对话区**

- 新增对话空态：机器人图标 + 说明 + 3 条示例指令（点击填入输入框，不直接发送）。
- 气泡重做：用户 = 品牌渐变气泡 + 右下小圆角 + 光晕；AI = 白卡 + 细描边 + 左下小圆角，AI 头像与用户头像区分底色。
- 文件清单条目改成带边框的小卡片，hover 变品牌色。
- 思考过程折叠区改成**局部深色块**（`--dark-bg-2` + 等宽字体）。
- 生成中进度条改成品牌渐隐光带。

**3c 预览区**

- 模式切换由 `a-select` 换成 `a-segmented`（代码预览 / 网页预览），工具按钮图标化 + Tooltip 位。
- **分隔条可拖拽**：宽度比例存 `localStorage`（`xy-gen-panel-ratio`），刷新后保持；圆点按钮负责收起/展开；窄屏自动变成上下分栏。
- 文件树选中态改为品牌色左边框 + 白底；代码预览接 `MarkdownView` 新增的 `dark` 模式（深色底 + 深色 hljs 配色）。

**顺手修的真问题**

- 已生成的代码原来只在"对话记录 ≥ 2 条"时才加载，导致只剩 1 条记录的已生成应用打开后右侧工作区是空的。改为「生成状态 = done 或记录 ≥ 2」即加载产物。
- `.preview-panel` 的 `flex-basis` 由 `auto` 改为 `0`：否则预览内容宽度会反过来挤压对话区，拖拽比例不生效（实测 45% 被压到 27%）。

**验证**

- `npm run build` 通过；1440 / 390 两档实测三个真实应用（单文件 / 多文件 / 多轮对话）。
- 按钮分组、对话气泡、文件清单、代码深色预览、网页预览、移动端上下分栏均正常；控制台无报错。
- 拖拽实测：45% → 62.3%，刷新后仍为 62.3%（持久化生效）。
- **未验证项**：思考过程深色块（历史消息不存 reasoning，只有实时生成时才会出现）、生成中的停止按钮与流式气泡。

### 2026-10-10 · 批次 4（列表页与管理端）✅

**我的项目 `MyProjectsPage`**

- 套 `PageContainer`（标题 + 描述）；Tab 加数量徽标（我的应用 / 回收站）。
- 工具栏统一成「左筛选 + 右操作」：右侧 `删除所选（n）`（danger，未选中禁用）+ `新建应用`（primary）。
- 表格行内从 4 个平铺按钮收敛为 `进入对话` + `更多 ▾`（编辑信息 / 申请精选 / 删除到回收站）。
- **删除全链路统一走二次确认弹窗**：可逆（删除到回收站）与不可逆（永久删除）共用一个弹窗，文案分别说明"可在回收站恢复"与"不可恢复、代码与对话一并清除"，批量时显示数量。
- 列宽固定 + 应用名称 `ellipsis`，避免长名字把表格撑变形。

**管理端三页**

- 用户管理：`PageContainer` + 工具栏模板（右侧显示总数）+ 角色 chip（管理员金色）+ 编辑 / 删除。
- 应用管理：**操作列从 6 个平铺按钮收敛成 `编辑` + `更多 ▾`**（通过推荐 / 拒绝推荐 / 取消精选 / 置顶 / 取消置顶 / 删除应用），删除走二次确认弹窗；同时修掉了"应用名称列被挤成竖排"（列宽和超出滚动宽度不匹配）。
- 对话管理：`PageContainer` + 工具栏 + 抽屉；抽屉头部元信息改成 tag 一行，导出按钮保留；消息气泡复用生成页样式（用户渐变 / AI 白卡）。

**其他**

- `AppEditPage` 套 `PageContainer`：403 错误态卡片化、管理员选项用分割线分区、操作按钮移到表单底部右对齐。
- 工具栏样式（`.pane-toolbar` / `.toolbar-filter` / `.toolbar-actions` / `.toolbar-input` / `.tab-count`）提升到 `styles/global.css` 共用，首页不再单独维护一份。

**验证**

- `npm run build` 通过；1440 / 390 两档实测：我的项目、用户管理、应用管理、对话管理（含抽屉打开）、编辑应用。
- 删除确认弹窗实测「打开 → 取消」，事后复核列表仍为 2 条，确认没有产生数据变更。
- **未验证**：管理端的审核 / 置顶 / 删除等写操作只验证了菜单结构与弹窗，没有真的执行，避免改动真实数据。

### 2026-10-10 · 批次 5（打磨与回归）✅

**色值收敛到源头**

- 页面级硬编码色值清零：`AppGeneratePage`（可视化编辑浮层、样式面板、代码框 35 处）与 `HomeView`（hero 渐变）全部换成 token。
- 保留的三处例外已记录在验收清单里：`MarkdownView` 的 markdown + hljs 语法配色（属于语法主题 palette）、写进 iframe 内部文档的 outline 色值（父页面 CSS 变量不跨 iframe，必须字面量）、`config/theme.ts` 的 antd token 定义。

**可视化编辑浮层**

- `edit-card` 改成毛玻璃浮层（`--bg-glass-strong` + backdrop-filter + `--shadow-3`），标签/选中文字/样式面板全部 token 化。
- 实测：进入可视化编辑 → 点 iframe 里的 `h1` → 浮层出现、样式面板可读、退出编辑正常，控制台无报错。

**表格首屏骨架屏**

- 新增 `components/TableSkeleton.vue`（纯 CSS shimmer，行数/列数可配），替换掉"首屏只有一个转圈"的观感。
- 接入 6 处：首页我的应用、我的项目（我的应用 + 回收站）、用户管理、应用管理、对话管理。
- 规则：只在「首次加载且还没有数据」时替换表格；刷新已有数据时仍用表格自身的 loading，避免整块闪烁。
- 顺手修掉加载中显示"共 0 个应用"的误导文案（总数改为加载完成后才显示）。

**toast 跟随主题**

- 新增 `utils/feedback.ts` 作为 message 适配层：`BasicLayout` 用 `App.useApp()` 注册实例，`request.ts` / `router` / `stores` 这类非组件模块通过它复用；注册前自动降级到 antd 静态 message，提示不会丢。
- 全站 12 个文件的静态 `import { message }` 统一换成 `appMessage`（调用点零改动）。
- 实测：退出登录后访问受保护页面，路由守卫弹出的"请先登录"toast 圆角为 **16px**（= 主题 `borderRadiusLG`，antd 默认 8px），确认已跟随主题。

**回归基线**

- 三档宽度（1440 / 1024 / 390）关键页面截图存到 `tmp/front-redesign-preview/baseline/`（`tmp/` 已 gitignore）。
- 固定流程：改完一批 → `npm run build` → 三档宽度各截一遍 → 与基线对比 → 控制台无 error/warn。

### 全部批次完成情况

| 批次 | 状态 |
|---|---|
| 0 设计底座 | ✅ |
| 1 外壳与导航 | ✅ |
| 2 首页 + 认证页 + 个人中心 | ✅ |
| 3 生成页（顶部按钮 / 对话区 / 预览区） | ✅ |
| 4 列表页 + 管理端 + 编辑页 | ✅ |
| 5 骨架屏 / toast 主题化 / 回归基线 | ✅ |

**唯一仍未实测的**：实时生成态（思考过程深色块、流式气泡、停止生成的按钮态）——需要跑一次真实生成才能看到；其余页面均已用真实数据在三档宽度下验证。

### 2026-10-10 · 提示词输入长度放宽

| 位置 | 原来 | 现在 |
|---|---|---|
| 首页创作台 | `:rows=3` + `maxlength=500` | 自适应 4–12 行 + `maxlength=2000` + 字数计数 |
| 生成页对话输入 | 自适应 2–6 行、无上限 | 自适应 3–12 行 + `maxlength=2000` + 字数计数 |
| 新建应用弹窗「初始提示词」 | `:rows=3` + `maxlength=300` | 自适应 3–10 行 + `maxlength=2000` + 字数计数 |
| 可视化编辑「AI 修改」输入 | 自适应 2–4 行 | 自适应 3–8 行 |

**取 2000 的依据**：后端 `app.initPrompt` / `chat_history.message` 都是 MySQL `text` 列，DTO 无长度校验；
唯一相关的是记忆服务 `AgentMemoryService.MAX_MESSAGE_CHARS = 2000`（单条记忆回灌上限），所以 2000 是与之对齐的合理上限。

**已知后端行为（本次未改）**：`FIRST_REQUIREMENT_MAX_CHARS = 500` —— 长对话回灌时，"最初的需求"提示只保留前 500 字并标注"…(已截断)"。
也就是说超长初始提示词在**首轮生成**会被完整使用，但**后续轮次的记忆回灌**只会带上前 500 字。如需放宽需要后端调整并评估 token 预算。

**踩坑**：`show-count` 会给 textarea 外包一层 `.ant-input-textarea`，在 flex 行（生成页输入区）里这层 wrapper 没有 `width:100%`，会把输入框挤成窄条；
已用 `.input-bar :deep(.ant-input-textarea) { flex: 1; min-width: 0 }` 修掉。块级布局（首页创作台、弹窗）不受影响。
