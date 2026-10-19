# Camera Style Pad

[中文](#中文) · [English](#english)

---

## 中文

**iPhone 18 Pro「摄影风格」二维调节板的安卓实现** —— 给小米 / HyperOS 相机加上同款能力：横轴冷暖、纵轴色调，再加一个色彩强度滑杆，**实时作用在预览上**。

### 亮点

- **对标 iPhone 18 Pro 摄影风格**：二维色温/色调板 + 色彩强度
- **二维板 + 强度**：拖动即时变色，不再只能选固定滤镜档位
- **叠在现有滤镜上**：沿相机自己的 `CandySDK` / Cube LUT 管线改色，不另开一套渲染
- **LSPosed 模块**：作用域 `com.android.camera`，无需改系统分区

### 环境

| 项目 | 说明 |
|------|------|
| 系统 | HyperOS 3（实测） |
| 相机 | `com.android.camera` **6.4.000530.0**（主目标；6.5 类名不同，未保证） |
| 框架 | LSPosed（作用域勾选相机） |

### 安装

1. 安装 Release / Debug APK  
2. LSPosed 启用 **Camera Style Pad**，作用域仅 `com.android.camera`  
3. 强制停止相机后重开  
4. **先选任意滤镜**，再点预览右侧 ◐，拖调节板  

日志：`adb logcat \| grep CamStylePad`

### 已知限制

- **必须先选滤镜**：无滤镜时相机不会走滤镜渲染器，调节板暂时无效  
- **成片（拍照输出）尚未接入**：6.4 成片滤镜多在硬件侧，当前只保证预览  
- 与 HyperCeiler 等同时 hook 相机时，如有异常可先关掉对方的相机相关项再试  

### 开发

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

需要本机 Android SDK；`local.properties` 自行配置 `sdk.dir`（勿提交）。

### 许可

[MIT](LICENSE)

---

## English

An **Android / LSPosed implementation of the iPhone 18 Pro Photographic Styles 2D pad** for **Xiaomi / HyperOS Camera**: X = warm↔cool, Y = tone, plus a color-intensity slider — applied **live on the viewfinder**.

### Highlights

- **iPhone 18 Pro Photographic Styles on Android** — 2D color/tone pad + intensity
- **2D pad + intensity** — continuous control instead of fixed filter presets  
- **Stacks on the stock filter pipeline** — piggybacks Xiaomi’s `CandySDK` / Cube LUT path  
- **Scope `com.android.camera` only** — no system partition edits  

### Requirements

| Item | Notes |
|------|--------|
| OS | HyperOS 3 (tested) |
| Camera | `com.android.camera` **6.4.000530.0** (primary; 6.5 not guaranteed) |
| Framework | LSPosed with camera in scope |

### Install

1. Install the APK  
2. Enable **Camera Style Pad** in LSPosed; scope = `com.android.camera`  
3. Force-stop Camera, reopen  
4. **Select any filter first**, tap ◐ on the right, drag the pad  

Logs: `adb logcat | grep CamStylePad`

### Limitations

- **A stock filter must be selected** — without one the filter renderer is inactive  
- **Capture / saved photos not wired yet** — on 6.4, capture filters often run in HAL; preview only for now  
- If other camera-hooking modules (e.g. HyperCeiler) conflict, disable their camera features and retry  

### Build

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Point `sdk.dir` in a local `local.properties` (do not commit it).

### License

[MIT](LICENSE)
