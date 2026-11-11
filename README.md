# 包子漫画 · Mihon/Tachiyomi 扩展

站点：<https://cn.bzmgcn.com>

## 编译

推到 GitHub 仓库后，Actions 自动编译，产物在 **Actions → 最新一次 run → Artifacts → bzmgcn-apk**。
下载后直接安装（debug 签名），然后在 Mihon 的「扩展」里能看到「包子漫画」。

本地不需要装 JDK/Android SDK。

## 说明

- 站点有 gatekeeper 反爬（SHA-256 前导零 PoW），扩展里用 OkHttp 拦截器自动过挑战。
- 阅读页在 `cn.cnbzmg.com`，和主站不是同一个域，cookie 分开存。
- VIP 锁定的章节只有占位图，属于站点限制。
- 支持：分类浏览（分页）、最新更新、搜索（分页）、详情、章节列表、图片。
