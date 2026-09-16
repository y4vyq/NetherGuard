# NetherGuard

![Java](https://img.shields.io/badge/Java-17-orange)
![Spigot](https://img.shields.io/badge/Spigot-1.14%2B-blue)
![License](https://img.shields.io/badge/License-MIT-green)

> 在玩家划定的区域内阻止下界传送门生成  
> Prevent nether portal creation within player-defined regions.

一个轻量的 Minecraft 服务端插件，允许玩家用 `/ng pos1`、`/ng pos2` 划定区域，并阻止该区域内生成下界传送门。支持 SQLite / MySQL 存储、多语言（内置 zh_CN / zh_TW / en_US），采用异步单线程写入队列，保证数据一致性的同时不阻塞主线程。

---

## 目录

- [功能特性](#功能特性)
- [兼容性](#兼容性)
- [安装](#安装)
- [快速开始](#快速开始)
- [命令](#命令)
- [权限](#权限)
- [配置文件](#配置文件)
- [语言文件](#语言文件)
- [存储后端](#存储后端)
- [工作原理](#工作原理)
- [常见问题](#常见问题)
- [从源码构建](#从源码构建)
- [许可](#许可)

---

## 功能特性

- **区域保护**：玩家用两个对角点划定立方体区域，区域内禁止生成下界传送门。
- **拦截事件**：
  - 拦截 `PortalCreateEvent`（玩家点火、自然生成等）
  - 拦截 `PlayerPortalEvent`（玩家穿过传送门进入受保护区域）
- **2D / 3D 模式**：可配置忽略 Y 轴，整列高度都受保护。
- **多存储后端**：内置 SQLite，可选 MySQL。
- **异步写入**：所有 `INSERT` / `DELETE` 走单线程写入队列，避免 SQLite 锁冲突，不卡主线程。
- **失败保守策略**：数据库加载失败时默认"拒绝放行"（fail-closed），可配置。
- **i18n**：内置简体中文、繁体中文、英文；支持自定义语言文件。
- **每玩家区域上限**：可配置最大区域数，OP 可绕过。

---

## 兼容性

| 项目 | 要求 |
|------|------|
| 服务端 | Spigot / Paper 1.14+（`api-version: 1.14`） |
| Java | 17+ |
| 依赖 | 无（SQLite / MySQL 驱动已 shade 进 jar） |

> 已在 Purpur 1.21.8 上验证。

---

## 安装

1. 从 [Releases](../../releases) 下载最新 `NetherGuard-x.x.x.jar`。
2. 放入服务端 `plugins/` 目录。
3. 重启服务器。
4. 首次启动应会生成：
   ```
   plugins/NetherGuard/
   ├── config.yml
   ├── lang/
   │   ├── zh_CN.yml
   │   ├── zh_TW.yml
   │   └── en_US.yml
   └── regions.db        # SQLite 模式
   ```

---

## 快速开始

```
/ng pos1              # 站在第一个角，设置 pos1
/ng pos2              # 站在对角，设置 pos2
/ng create my_region  # 用当前选区创建名为 my_region 的区域
/ng list              # 查看自己的区域列表
/ng info my_region    # 查看区域详情
/ng delete my_region  # 删除区域
```

设置完成后，任何玩家在该区域内点燃下界传送门都会被阻止，穿过传送门进入该区域也会被拦截。

---

## 命令

主命令：`/netherguard`，别名 `/ng`。

| 命令 | 说明 | 权限 |
|------|------|------|
| `/ng pos1` | 设置第一个对角点 | `netherguard.use` |
| `/ng pos2` | 设置第二个对角点 | `netherguard.use` |
| `/ng create <名称>` | 用当前选区创建区域 | `netherguard.create` |
| `/ng delete <名称>` | 删除区域（自己 / 任意） | `netherguard.delete.own` / `netherguard.delete.any` |
| `/ng list [页码]` | 列出自己的区域 | `netherguard.use` |
| `/ng info <名称>` | 查看区域详情 | `netherguard.use` |
| `/ng cancel` | 清除当前选区 | `netherguard.use` |
| `/ng reload` | 从数据库重载缓存 | `netherguard.reload` |

不带子命令时显示帮助（帮助内容会根据权限过滤）。

---

## 权限

| 权限 | 说明 | 默认 |
|------|------|------|
| `netherguard.use` | 基础命令访问 | 所有人 |
| `netherguard.create` | 允许创建区域 | 所有人 |
| `netherguard.delete.own` | 允许删除自己的区域 | 所有人 |
| `netherguard.delete.any` | 允许删除任意区域 | OP |
| `netherguard.reload` | 允许重载缓存 | OP |
| `netherguard.limit.bypass` | 绕过每玩家区域上限 | OP |

---

## 配置文件

`plugins/NetherGuard/config.yml`：

```yaml
# 内置：zh_CN（简体中文）、zh_TW（繁體中文）、en_US（English）
language: zh_CN

storage:
  type: sqlite   # sqlite / mysql
  sqlite:
    file: regions.db
  mysql:
    host: localhost
    port: 3306
    database: portal_region
    username: root
    password: ""
    table-prefix: "pr_"

limits:
  max-regions-per-player: 5   # 每玩家最大区域数，0 或负数表示不限制
  allow-overlap: true         # 是否允许区域重叠
  use-2d: false               # true = 忽略 Y 轴，整列高度保护

event:
  cancel-portal-create: true  # 是否拦截传送门生成
  cancel-player-portal: true  # 是否拦截玩家穿过传送门
  notify-player: true         # 是否给玩家发提示消息
  # 可选：只拦截特定创建原因，留空表示全部拦截
  # 可选值：FIRE / NETHER_PORTAL / END_PORTAL / CUSTOM
  intercept-reasons: []

command:
  create-timeout-seconds: 5   # 创建/删除的等待超时
  list-page-size: 10          # list 每页条数

debug: false
```

修改后执行 `/ng reload` 即可热重载配置、语言和区域缓存。

---

## 语言文件

语言文件位于 `plugins/NetherGuard/lang/<code>.yml`。

- 内置 `zh_CN`、`zh_TW`、`en_US`，缺失时自动释放。
- 切换语言：修改 `config.yml` 中的 `language`，然后 `/ng reload`。
- 自定义语言：复制任一内置文件，改名为 `<你的代码>.yml`，翻译后把 `language` 设为该代码即可。
- 颜色代码使用 `&`（如 `&a`、`&c`）。

单个 key 缺失会在控制台记录一次 warning，并在游戏内显示 `[Missing i18n key: xxx]` 便于排查。

---

## 存储后端

### SQLite（默认）

零配置，数据存于 `plugins/NetherGuard/regions.db`。适合单机或小型服务器。

### MySQL

修改 `config.yml`：

```yaml
storage:
  type: mysql
  mysql:
    host: 127.0.0.1
    port: 3306
    database: portal_region
    username: root
    password: "your_password"
    table-prefix: "pr_"
```

表结构会在启动时自动创建（`CREATE TABLE IF NOT EXISTS`）。

> **切换后端不会自动迁移数据**，需要手动导出/导入。

---

## 工作原理

```
玩家操作 ──► PortalRegionCommand ──► RegionService
                                          │
                     ┌────────────────────┼────────────────────┐
                     ▼                    ▼                    ▼
              读：RegionCache       写：WriteExecutor     存储：Storage
              (AtomicReference)    (单线程串行)          (SQLite/MySQL)
```

- **RegionCache** 是不可变快照，包含：
  - `all`：全量列表
  - `byWorld`：按世界分组（用于命中判断）
  - `byOwner`：按所有者分组（用于查询）
  - `byOwnerSorted`：按所有者分组并按创建时间排序（用于 `list`）
- **命中判断** `isRestricted(world, x, y, z)` 走 `byWorld`，只遍历同世界的区域。
- **写入** 经 `WriteExecutor` 单线程串行执行，避免 SQLite 锁冲突。
- **缓存一致性**：写入成功后做增量更新（`withAdded` / `withRemoved`）；若写入结果不确定（超时/中断），触发全量重建并做代际校验，避免并发覆盖。
- **三态**：`LOADING`（启动中，放行）、`READY`（正常判断）、`FAILED`（默认保守拦截）。

---

## 常见问题

**Q：启动时区域还没加载完，玩家会不会误闯？**  
A：启动阶段缓存为 `LOADING` 状态，此时放行以免误伤；加载完成后切换到 `READY` 才会拦截。

**Q：数据库挂了怎么办？**  
A：缓存进入 `FAILED` 状态，默认"保守拦截"（fail-closed），即所有位置都被视为受限。可通过构造参数调整。

**Q：区域可以重叠吗？**  
A：可以。命中判断是"任一区域包含即受限"。

**Q：为什么创建/删除偶尔超时？**  
A：写入队列串行执行，若前面有慢查询会排队。默认超时 5 秒，可在 `command.create-timeout-seconds` 调整。

**Q：支持 Folia 吗？**  
A：当前版本未适配 Folia 的 region 调度，仅支持传统 Bukkit 调度。

---

## 从源码构建

```bash
git clone https://github.com/y4vyq/NetherGuard.git
cd NetherGuard
mvn clean package
```

产物：`target/NetherGuard-1.0.0.jar`（已 shade SQLite / MySQL 驱动）。

---

## 许可

本项目采用 [MIT License](LICENSE) 许可。

---

## 反馈与贡献

- 遇到 Bug 或有功能建议，欢迎提交 [Issue](../../issues)。
- 想提交代码，欢迎开 [Pull Request](../../pulls)。

> 如果这个插件帮到了你，欢迎点个 ⭐ Star 支持一下！
