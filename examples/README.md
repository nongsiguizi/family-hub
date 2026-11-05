# 示例文件与真实数据

这个仓库是**私有仓库**，但为了安全，代码仓库里仍然**不包含任何真实家庭数据**。
下面这些文件被 `.gitignore` 排除，需要从你自己的 NAS 上复制回来，项目才能带数据运行：

| 文件 | 作用 | 从哪来 |
|---|---|---|
| `data/grades.json` | 成绩、花销、压岁钱、笔记本、日常账本增量（含 `rev`） | NAS `/vol1/1000/docker/grades-track/data/grades.json` |
| `data/attachments.json` + `data/attachments/` | 试卷 PDF / 图片、日常凭证 | 同上目录 |
| `data/auth.json` | 密码哈希与令牌（容器启动自动生成，可不备份） | 同上目录 |
| `public/daily-ledger.json` | 历史日常账本只读数据（10,641 笔） | NAS `public/daily-ledger.json` |
| `public/avatar.jpg` | 学生头像 320×320 | 放一张同名 JPG 即可 |
| `android/grades-track.keystore` | APK 签名密钥 | **自己保管**，换密钥会导致旧版无法覆盖安装 |
| `android/dist/*.apk` | 已签名安装包 | 用 `android/build_apk.py` 重新生成 |

不提供这些文件也能启动容器：页面会显示空档案，附上传、成绩等模块可用，只是没有历史数据；
`public/daily-ledger.json` 缺失时，日常记账模块会提示「账本文件不存在」。

## 示例文件

- `grades.example.json`：状态文件的**字段结构**示例，所有值都是占位内容，可以直接改名为
  `data/grades.json` 作为空档案起步（首次访问密码仍是 `meimima`）。
- `daily-ledger.example.json`：日常账本的**列式结构**示例（假数据 2 条），说明 `fields` 与 `rows` 的对应关系。
  真实账本是从原记账软件导出的 JSON 转换而来，转换脚本见 `tools/` 说明（本仓库不含真实导出文件）。

## 从 NAS 备份真实数据

```bash
scp -r Lyuan@192.168.5.132:/vol1/1000/docker/grades-track/data ./data
scp    Lyuan@192.168.5.132:/vol1/1000/docker/grades-track/public/daily-ledger.json ./public/
```

也可以直接在网页「设置与数据 → 导出 JSON 备份」拿到一份状态备份；注意试卷与凭证附件不在 JSON 里，
需要单独备份 `data/attachments/` 目录。
