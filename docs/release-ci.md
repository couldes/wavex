# GitHub Actions 自动发布配置

## 工作原理

推送 `v*` 格式的 tag 时，GitHub 云端自动：构建签名 Release APK → 创建 Release → 上传 APK 附件。

## 一次性配置（已完成/需完成）

云端构建需要签名密钥，通过仓库 Secrets 注入（不入库）：

| Secret 名 | 内容 |
|---|---|
| `KEYSTORE_GPG` | `keystore/wavex-release.jks` 经 GPG 加密后的 base64 文本 |
| `KEYSTORE_PASSPHRASE` | GPG 加密时设的口令 |
| `KEYSTORE_PASSWORD` | jks 的 storePassword / keyPassword（与本地 keystore.properties 相同） |

### 生成这些 Secrets 的命令（本地执行一次）

```bash
cd D:/software/wavex
# 1. 加密 keystore（会提示设一个口令，即 KEYSTORE_PASSPHRASE）
gpg --symmetric --cipher-algo AES256 --output keystore.jks.gpg keystore/wavex-release.jks
# 2. base64 编码，输出内容存入 Secret: KEYSTORE_GPG
base64 -w0 keystore.jks.gpg
# 3. 清理中间文件
rm keystore.jks.gpg
```

Secrets 填写入口：仓库 → Settings → Secrets and variables → Actions → New repository secret

## 发布流程（配置完成后）

```bash
# 改版本号 versionName/versionCode 后：
git add -A && git commit -m "chore: bump version to 0.14.0"
git tag -a v0.14.0 -m "Wavex v0.14.0"
git push origin main --tags
# 完成！几分钟后 Release 页面自动出现 APK
```

## 手动触发 / 排查

- 运行记录：仓库 → Actions 标签页
- 失败时点进去看红叉步骤的日志
