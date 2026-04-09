# Security Policy

## 支持的版本

目前仅最新版本获得安全更新支持。

| 版本   | 安全更新 |
|-------|---------|
| 1.3.x | ✅      |
| < 1.3 | ❌      |

## 报告安全漏洞

如果你发现了安全漏洞，请按以下方式报告：

1. **不要**在公开 Issue 中描述漏洞细节
2. 通过 [GitHub Issues](https://github.com/yingzhudashu/openclaw-android-port/issues) 创建私密报告，标题注明 `[Security]`
3. 描述漏洞的：
   - 影响范围（哪个组件/接口）
   - 复现步骤
   - 潜在影响
   - 修复建议（如有）

我们承诺在收到报告后 **72 小时内** 做出初步响应。

## 安全设计

### API Key 保护

- API Key 存储在应用私有目录（`Context.MODE_PRIVATE`），其他 App 无法访问
- Gateway 配置 `openclaw.json` 同样存储在私有路径
- **绝不**在代码中硬编码任何密钥或 Token
- Git 仓库通过 `.gitignore` 排除配置文件

### 网络通信

- Gateway 服务仅监听 `127.0.0.1`（本地回环），不暴露到外部网络
- 设备控制 API（DeviceControlApi）同样仅绑定 `127.0.0.1`
- WebViewBridge 仅本地回环端口 `18790`
- 与 LLM 供应商的通信使用 HTTPS

### 权限最小化

- 仅请求必要运行时权限（位置、相机、通知等）
- 用户可在设置页查看各权限状态并前往系统设置管理
- 存储权限按 Android 版本自适应（Scoped Storage）

### 代码注入防护

- Gateway 的 `exec` 工具运行在沙箱环境中
- 用户输入经过参数化处理后传递给 LLM API
- `eval` 操作限制在 WebView 沙盒内

### 已知安全限制

- ⚠️ APK 可被反编译，API Key 在已 root 设备上可能被提取
- ⚠️ Node.js 以 ProcessBuilder 方式运行，理论上可被同一设备上的其他进程通过 localhost 端口调用
- ⚠️ Debug APK 包含未混淆的代码，Release 版本应启用 ProGuard/R8

## 最佳实践（用户侧）

1. **仅从可信来源安装 APK**（GitHub Release 或自行编译）
2. **妥善保管 API Key**，不要在公共场合泄露
3. **定期轮换** LLM 供应商的 API Key
4. **审查权限**：在系统设置中检查 App 权限，按需授权
5. **不要在已 root 的设备上使用**：root 设备上的 App 隔离被削弱

## 依赖安全

本项目依赖的关键组件：

| 组件         | 版本/来源                | 安全关注点          |
|-------------|-------------------------|-------------------|
| Android SDK  | compileSdk 34           | 使用最新安全补丁     |
| Node.js      | libnode.so (arm64-v8a)  | 定期更新到 LTS      |
| Kotlin       | 2.0.0+                  | 跟随 Android Studio |
| Material     | 1.12.0                  | 无已知安全问题       |

建议定期运行依赖检查：
```bash
./gradlew dependencies --configuration debugCompileClasspath
```
