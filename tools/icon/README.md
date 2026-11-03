# EazyChat 图标生成

图标由 `generate_icons.py` 生成，单一真源：矢量 XML（自适应图标三层）与 PNG 回退图、预览图都来自同一组几何/配色常量。

## 设计

- 白色对话气泡 + 气泡内三节点连线（BLE Mesh）；强调节点为粉色 `#FD79A8`
- 背景斜向渐变 `#6C5CE7` → `#A29BFE`（左上到右下）
- 所有内容位于 108dp 画布的 66dp 安全区内，圆形遮罩不裁切

## 运行

```powershell
python -m pip install Pillow
python tools/icon/generate_icons.py
```

脚本会重写：

- `app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml`
- `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher{,_round}.png`（48/72/96/144/192 px）
- `docs/icon-preview.png`（512×512 预览）

## 改色 / 改形状

修改脚本顶部的 `BACKGROUND_START/END`、`BUBBLE_*`、`TAIL`、`NODES`、`NODE_RADIUS`、`LINE_WIDTH` 等常量后重新运行即可。注意保持所有前景内容在 `(54,54)` 为圆心、半径 33dp 的安全圆内。
