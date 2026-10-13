# LiuTrade

虚拟村民交易插件：读取**独立 YAML**（每个文件 = 一个商店/村民），用**村民式界面**放入物品、取出新物品。

- 交易输入复用 **SoulSpace 统一物品规则**（`material` / `pdc` / `name` / `lore` / `custom_model_data`，支持 `not_*` 否定）；
- 产出两种方式：**走 SoulSpace `convert/` 转换规则**（`convert: true`，默认），或**显式**写出（`library_id` 经 SoulSpace 解析 `ce:/mm:/ie:/mc:`，或原版 `material`）；
- 支持绑定 **Citizens** NPC：右键 NPC 直接打开对应商店；
- 未安装 SoulSpace 时自动退化为「仅原版材质」交易，不报错。

> 依赖：[SoulSpace](https://github.com/liuxingyu2001-sys/soulspace) **2.5.0+**（软依赖，用于规则匹配、物品库解析、转换产出）。

## 环境

- Paper 1.21+（Folia 兼容）
- Java 21
- 可选：SoulSpace 2.5.0+、Citizens

## 安装

1. 把 `Liu-LiuTrade-<版本>.jar` 放进 `plugins/`；
2. 启动一次，生成 `plugins/LiuTrade/config.yml` 与 `plugins/LiuTrade/merchants/_example.yml`；
3. 复制 `_example.yml` 去掉下划线（例如 `blacksmith.yml`）并改写；
4. `/liutrade reload` 或等待自动重载（默认 10 秒轮询）。

## 命令与权限

| 命令 | 说明 |
| --- | --- |
| `/liutrade` | 打开商店（只有一个时直接打开，多个时列出） |
| `/liutrade open <商店> [玩家]` | 打开指定商店；`[玩家]` = 为某玩家打开（控制台必须指定，玩家为他人打开需 `liutrade.admin`） |
| `/liutrade list` | 列出全部商店（控制台可用） |
| `/liutrade reload` | 重载全部商店配置（控制台可用） |
| `/liutrade item` | 把手持物品导出成 `bytes:` 配置行（控制台也会打印，便于复制） |

`<商店>` = 文件相对路径去掉 `.yml`（例如 `blacksmith`、`子目录/xxx`）。

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `liutrade.use` | true | 打开交易商店 |
| `liutrade.admin` | op | 为其他玩家打开商店 |
| `liutrade.admin.reload` | op | 重载配置 |

单笔交易还可在 YAML 里写 `permission:` 限制、`description:` 写界面说明。

## 商店配置

`plugins/LiuTrade/merchants/**.yml`（递归扫描，相对路径排序，`_` 前缀的文件不加载）：

```yaml
name: "&6铁匠"                 # 商店名
title: "&8&l铁匠的交易"         # GUI 标题
npc: ""                       # Citizens NPC 的 id（数字）或名字；留空 = 只能用 /liutrade open
permission: ""                # 打开本商店所需权限；留空不限制
icon:                         # 商店图标
  library_id: "mc:ANVIL"
trades:
  铁换金:                      # 交易键（仅作标识）
    name: "&f铁锭 &7→ &6金锭"     # 交易名（聊天提示 + 默认图标名）
    display_name: "&6金锭兑换"     # 可选：仅列表图标的显示名（不写则用 name）
    display:                    # 可选：仅列表图标的物品（不写则用输入[0]）
      library_id: "mc:GOLD_INGOT"
    description:               # 可选：界面 lore 里名字下方的自定义说明
      - "&7把铁锭换成金锭"
      - "&8每日限 1 次"
    inputs:                   # 1~6 个输入（原生村民界面每笔最多 2 个）
      - material: IRON_INGOT
        amount: 8
    convert: true             # 产出走 SoulSpace 转换规则（仅限单输入）
  钻换绿宝石:
    name: "&f钻石 &7→ &a绿宝石 x2"
    inputs:
      - material: DIAMOND
        amount: 1
    outputs:                  # 显式产出
      - material: EMERALD
        amount: 2
  双材料:
    name: "&f金锭+红石 &7→ &b时钟"
    inputs:
      - material: GOLD_INGOT
        amount: 4
      - material: REDSTONE
        amount: 16
    outputs:
      - material: CLOCK
        amount: 1
```

自定义界面布局（`gui-mode: custom`）：玻璃板做边框与分隔，每行 4 笔交易（每页 12 笔）；
顶部一行是「上一页 / 关闭 / 怎么用 / 下一页」；下方是输入槽 `[输入1] [输入2] …` 与 `→ [产出]`，
输入槽数量按本商店需要几个物品**动态显示**（最多 6 个）；左键交易可自动放入，也支持手动放。

### 物品规格

| 写法 | 例子 | 匹配/生成方式 |
| --- | --- | --- |
| 原版材质 | `material: DIAMOND`（= `diamond` = `minecraft:diamond`） | 按材质生成/匹配 |
| 物品库（CE/MM/IE/原版） | `library_id: "ce:ns:id"` | 经 SoulSpace `resolveItem` 生成，按 `isSimilar` 匹配 |
| 材质 + 组件 | `material: PAPER` + `components: {...}` | 用组件构建自定义物品，按 `isSimilar` 匹配 |
| 全保真 | `bytes: "<Base64>"` | `ItemStack.serializeAsBytes()`，任意物品/组件原样还原 |
| 规则字段 | `material: PAPER` + `pdc: jiu:jiu` | 经 SoulSpace `matchesRule` 匹配（支持 `not_pdc` 等否定） |
| 显示物品（仅界面） | `display: { library_id: "ce:ns:id" }` | 图标显示该物品，匹配仍按规则字段 |
| 显示名字（仅界面） | `display_name: "&6神秘票券"`（别名 `label`） | 界面/lore 显示的名字，不再显示 pdc/英文名 |
| 数量 | `amount: 8` | 一笔所需/产出数量，默认 1 |

`display` / `display_name` 在**物品规格**（输入/产出）与**交易本身**上都能用：
交易上的 `display` 只改列表图标物品，`display_name` 只改列表图标名字（`name` 仍用于聊天提示）。

界面显示的名字默认取 **SoulSpace 的统一展示名**（自定义名 → 材质中文名 → 英文名），需要
SoulSpace **2.5.1+**（`SoulSpaceApi.displayName`）；未装/旧版则退回物品自带显示名/英文材质名。
规则输入（`pdc`/`not_*` 等）想显示成 CE 物品时，用 `display: { library_id: "ce:..." }`，
再配 `display_name` 指定名字——**仅影响显示，不影响匹配**。

**英文 id 写法很宽松**（`material` 与 `library_id` 均适用）：`DIAMOND` = `diamond` =
`minecraft:diamond` = `mc:DIAMOND` = `vanilla:diamond`。（SoulSpace 侧自 2.5.2 起同样支持。）

**`components`** 键可带或不带 `minecraft:` 前缀：`custom_name` / `item_name` / `lore` /
`custom_model_data` / `enchantments`(或 `stored_enchantments`) / `unbreakable` / `rarity` /
`max_stack_size` / `fire_resistant` / `hide_tooltip` / `enchantment_glint_override` / `damage` /
`dyed_color`，以及 `custom_data`（嵌套 map 或 SNBT 字符串，写入 PDC，可被 SoulSpace 的 `pdc` 规则匹配）。

> 要产出 **CraftEngine / MythicMobs / ItemEdit** 物品，用 `library_id: "ce:..."`。
> 其它任意组件（无法用 `components` 表达的），主手持物品执行 `/liutrade item`，把输出的
> `bytes: "..."` 贴到产出入即可原样还原。

### 产出

- `convert: true`：产出由 SoulSpace 的 `convert/*.yml` 转换规则决定（**仅支持单输入**）。
- `outputs:`：显式列出产出，**支持多条**（`material` / `material`+`components` / `library_id` / `bytes`）。
  自定义界面会依次发放全部产出；原生村民界面每笔只能给一个结果，故第一个作为配方结果、
  其余在购买成功后补发。

## 界面

默认 `settings.gui-mode: villager`，即 **原生村民交易界面**（左列表、滚动、点击自动放入、音效均为客户端原生）：

- 交易列表就是客户端左侧列表，超过 7 笔自动出现滚动条；
- 点交易自动从背包填入输入槽，点产出即成交；
- 输入按**物品**匹配（取物品规格的示例：`library_id` 解析结果或 `material`）；
- 含 `pdc`/`name`/`not_*` 等规则字段、示例无法唯一代表的输入会被跳过（日志会提示）。

需要按规则（`pdc`/`not`）匹配任意物品、或想要分页时，改为 `settings.gui-mode: custom`（自定义箱子界面）：

```
左 5 列 × 5 行 = 交易列表（每页 25 笔，左键点击自动放入所需物品）
右侧  23 = 输入1   24 = 输入2   25 = 产出
底行  45 = 上一页  49 = 关闭    53 = 下一页
```

自定义界面里：左键产出槽成交 1 次，Shift+左键成交全部；关界面时输入槽里没取走的物品会退回背包（`settings.leftover-on-close: void` 可改为丢弃）。

## 主要配置（config.yml）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `settings.gui-mode` | villager | `villager` = 原生村民界面；`custom` = 自定义箱子界面（支持规则匹配/分页） |
| `settings.shared-config-path` | 空 | 共享配置目录；多服可指向同一目录，改一处全服生效 |
| `settings.auto-reload` | true | 自动检测配置变化并热重载 |
| `settings.poll-interval-ticks` | 200 | 轮询间隔（tick） |
| `settings.leftover-on-close` | return | 自定义界面关界面时输入槽物品处理：`return` / `void` |
| `settings.feedback` | true | 交易成功是否提示 |
| `settings.max-pages` | 20 | 自定义界面翻页上限 |

## 构建

```bash
mvn clean package
# 产物：target/Liu-LiuTrade-<版本>.jar
```
