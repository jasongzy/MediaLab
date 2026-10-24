<p align="center">
  <img src="docs/app-icon.svg" width="144" alt="Media Lab 图标">
</p>

<h1 align="center">Media Lab</h1>

[English](README.md) | 简体中文

一款原生 Android 媒体工具箱

## ✨ 功能

### 图像

- 编辑图片，多工具切换连续编辑，支持撤销、重做
- 按任意尺寸或比例裁剪，支持对齐编码块的 JPEG 无损裁剪
- 按比例或自由扩展画布，使用纯色或透明填充新增区域
- 任意角度旋转、上下或左右翻转，可自动扩充画幅或锁定画布大小
- 本地神经网络去背景，可选 RMBG 1.4（LiteRT，支持兼容 Qualcomm 设备的 NPU 加速及 GPU／CPU 回退）或 RMBG 2.0（MNN OpenCL GPU）模型；去背景后可用硬边或柔边笔刷保留、去除局部区域，支持暂存蒙版并跨图片复用
- 使用矩形、椭圆、任意圈选或笔刷遮盖、擦除图像区域
- 任意尺寸插值缩放
- 像素取色、复制 HEX 色值、从图中提取代表色
- 修改、删除元数据
- 从动态照片中提取静态图和内嵌视频；固化小米可恢复编辑的结果
- 可选择导出格式，并调整有损压缩质量

支持导入 JPEG、PNG、WebP、BMP 等图片格式，以及设备支持的 HEIF/AVIF；支持导出 JPEG、PNG 和 WebP；编辑与导出默认保留受支持的色彩空间（如 Display P3、Adobe RGB 等），以及拍摄时间、相机信息等有效的 EXIF。

支持导入手机 DNG 和 LibRaw 支持的相机 RAW 文件，显影为全分辨率 sRGB 图像后进行编辑。

### 视频

- 编辑视频或 GIF，多工具切换连续编辑，支持撤销、重做
- 时间裁剪、变速并保持音调、添加纯色片头/片尾或延长首尾帧
- 画面裁剪、扩展、旋转、镜像、缩放、指定时间段的局部区域遮盖，以及元数据的修改与删除
- 保存选定帧或音轨，或将其发送到图像/音频页进行编辑
- 可选择导出格式，调整帧率、码率及 GIF 颜色数量、循环播放，支持去除音轨

支持导入 MP4、MOV、MKV、WebM、AVI 等视频格式及 GIF；支持导出 MP4、MOV、MKV、WebM 和 GIF；实际兼容性取决于文件编码与设备能力。

### 音频

- 编辑音频，多工具切换连续编辑，支持撤销、重做
- 时间裁剪、变速并保持音调、添加静音片头/片尾
- 指定声道试听、删除、合并
- 编辑标签和封面，仅修改标签或封面时可保留原编码
- 导出格式及质量参数可调

支持导入 MP3、AAC、FLAC、M4A、Ogg/Vorbis、Opus、WAV 等音频格式；支持导出 MP3、AAC、M4A、FLAC 和 WAV；标签和封面编辑能力依格式而定。

支持解锁 NCM 文件，从中提取原始 MP3/FLAC 音频并恢复标签与封面；支持多选文件或递归扫描文件夹，输出时保留子目录结构。

## 📥 下载

最新版本 APK 可从 [GitHub Releases](https://github.com/jasongzy/MediaLab/releases/latest) 下载。

APK 内置神经网络模型，首次使用时会在设备端完成准备工作。

## 🤝 致谢

- [RMBG 1.4](https://huggingface.co/briaai/RMBG-1.4)
- [RMBG 2.0](https://huggingface.co/briaai/RMBG-2.0)
- [LiteRT](https://github.com/google-ai-edge/LiteRT)
- [MNN](https://github.com/alibaba/MNN)
- [LibRaw](https://www.libraw.org/)
- [FFmpeg](https://ffmpeg.org/), [FFmpegKit for Android](https://github.com/ffmpegkit-maintained/ffmpeg)
- [DeNcm](https://github.com/Pacoshao/DeNcm)
