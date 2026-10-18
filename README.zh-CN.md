<p align="center">
  <img src="docs/app-icon.svg" width="144" alt="Media Lab 图标">
</p>

<h1 align="center">Media Lab</h1>

[English Doc](README.md)

一款原生 Android 媒体工具箱

## ✨ 功能

### 图像

- 在多个工具间连续编辑同一张图片，支持撤销、重做和统一导出
- 内置 RMBG 模型，通过 LiteRT 在本地去背景，支持兼容 Qualcomm 设备的 NPU 加速及 GPU／CPU 回退
- 按任意尺寸或比例裁剪，支持对齐编码块的 JPEG 无损裁剪
- 按比例或自由扩展画布，使用纯色或透明填充新增区域
- 使用矩形、椭圆、任意圈选或笔刷遮盖、擦除图像区域
- 插值缩放、调整压缩质量，导出 PNG、JPEG 或 WebP
- 像素取色、复制 HEX 色值，从图中提取代表色
- 清理元数据、将动态照片转为静态图、固化小米可恢复编辑的结果

支持 JPEG、PNG、WebP、BMP 等静态图片，以及设备支持的 HEIF/AVIF。像素编辑使用 8 位 sRGB。编辑和格式转换默认保留拍摄时间、相机信息等仍有效的 EXIF。

### 音频

- 单文件音频编辑，支持播放试听、信息查看以及标签和封面编辑
- 支持 MP3、FLAC、M4A、Ogg/Vorbis、Opus、WAV 等 TagLib 支持的格式；字段和封面编辑能力依格式而定

#### NCM 解锁

- 多选文件或递归扫描文件夹，输出时保留子目录结构
- 解锁原始 MP3/FLAC 音频并恢复标签与封面，可同时复制普通音频
- 后台处理、停止任务、失败重试，同名文件可选择跳过、重命名或覆盖
- 校验输出后按需删除源文件，也可查找已解锁的原文件并确认清理

## 📥 下载

最新版本 APK 可从 [GitHub Releases](https://github.com/jasongzy/MediaLab/releases/latest) 下载。

APK 内置神经网络模型，首次使用 NPU 时在设备端编译，后续复用编译缓存。

## 🤝 致谢

- [RMBG 1.4](https://huggingface.co/briaai/RMBG-1.4)
- [DeNcm](https://github.com/Pacoshao/DeNcm)
