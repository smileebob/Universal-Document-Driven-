# 提交前审核清单（Commit Review Checklist）

本清单定义每次 `git commit` / 推送 / 发布前**必须执行**的审核规程。任何一项不通过即**阻断提交**，修复后重新执行全部检查。

适用范围：本仓库（Document-Driven）所有分支的所有提交，尤其是首次开源推送与后续对外发布。

---

## 1. 黑名单路径检查

入库文件（`git ls-files`）或本次待提交清单中，**不得出现**以下任何路径/模式：

| 黑名单项 | 说明 |
| --- | --- |
| `_smoke_tmp` | 冒烟测试临时目录 |
| `logs/` | 运行日志目录 |
| `node_modules` | 前端依赖 |
| `target/` | Maven 构建产物 |
| `*.log` | 任意日志文件 |
| 根级 `*.png` | 本地截图（根目录，不递归） |
| `.qoder` | IDE 工具目录 |
| `_dc_inner.jar` | 本地临时 jar |
| `*.ps1` | PowerShell 脚本（全部为本地测试/运维用途，不上传） |
| `design/` | 设计文档（非代码，不发布） |
| `prd/` | 产品需求文档（非代码，不发布） |
| `springcloud` | 规范文档目录（含 `springcloud/` 与 `springcloud-scaff/`） |

### PowerShell 检查命令

```powershell
# 已入库文件黑名单过滤（有输出 = 命中，必须清除后重新提交）
git ls-files | Select-String -Pattern '_smoke_tmp|(^|/)logs/|node_modules|(^|/)target/|\.log$|^\.qoder|_dc_inner\.jar|\.ps1$|^design/|^prd/|^springcloud|^技术方案'

# 本次暂存（待提交）清单同样过滤一遍
git diff --cached --name-only | Select-String -Pattern '_smoke_tmp|(^|/)logs/|node_modules|(^|/)target/|\.log$|^\.qoder|_dc_inner\.jar|\.ps1$|^design/|^prd/|^springcloud|^技术方案'

# 根级 *.png 单独检查（只查根目录一层）
git ls-files | Select-String -Pattern '^[^/]+\.png$'
```

命中后的处置：`git rm --cached <file>` 移出索引（保留本地文件），确认根级 `.gitignore` 已覆盖该模式，再重新检查直至零输出。

---

## 2. 敏感串扫描（零容忍）

对入库内容执行全文扫描，**命中即阻断提交**，无例外：

| 敏感串 | 含义 |
| --- | --- |
| `aliyuncs` | 阿里云内网/公网接入点域名 |
| `r-bp18` | Redis 实例 ID 前缀 |
| `mse-08257` | MSE 实例 ID |
| `LTAI` | 阿里云 AccessKey ID 前缀 |
| `121.41.163.37` | 特定公网 IP |
| `122.247.199.63` | 特定公网 IP |
| `192.168.` | 内网 IP 段 |
| `password` 后跟明文赋值 | 如 `password: xxx`、`password=xxx`；`${ENV}` 占位符形式（如 `password: ${REDIS_PASSWORD:}`）为合规，不阻断 |
| `D:\DevEco` | 本机绝对路径 |
| `W:\` | 本机盘符路径 |

### PowerShell 检查命令

```powershell
# 基础敏感串扫描（git grep 只扫入库内容；有输出 = 命中）
git grep --cached -n -E 'aliyuncs|r-bp18|mse-08257|LTAI|121\.41\.163\.37|122\.247\.199\.63|192\.168\.|D:\\DevEco|W:\\' -- ':!doc-platform-parent/docs/commit-review-checklist.md'
# 扫描命令已排除本清单文件自身（其内容包含敏感串样本，属已知豁免）

# password 明文赋值扫描：排除 ${...} 占位符形式
# （命中行若为 ${ENV} 占位符则合规放行，其余一律阻断）
git grep --cached -n -i -E 'password\s*[:=]\s*\S+' | Select-String -NotMatch -Pattern '\$\{[A-Z_]+(:[^}]*)?\}'
```

命中后的处置：从代码/配置中移除敏感值，改为 `${ENV}` 环境变量占位符注入；若已进入历史提交，需按发布流程处理历史清洗（由发布负责人决策），严禁带病推送。

---

## 3. 体积抽查

入库文件总数应为**数百级**。若达到**数万级**，几乎必然是误收了 `node_modules/` 或 `target/` 等目录，必须阻断并回到第 1 步排查。

```powershell
# 入库文件总数（预期：数百级；数万级 = 误收依赖/构建产物）
(git ls-files | Measure-Object).Count

# 按顶层目录分布抽查，快速定位异常大目录
git ls-files | ForEach-Object { ($_ -split '/')[0] } | Group-Object | Sort-Object Count -Descending | Select-Object -First 10
```

---

## 4. 执行记录要求

- 每次发布前，执行人须确认上述三项检查全部通过（零黑名单命中、零敏感串命中、文件数在数百级）；
- 检查结果建议在提交说明或发布记录中留痕（如"已通过 commit-review-checklist 三项检查"）；
- 本清单本身的变更需经项目负责人评审。
