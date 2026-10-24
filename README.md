<p align="center">
  <img src="docs/app-icon.svg" width="144" alt="Media Lab icon">
</p>

<h1 align="center">Media Lab</h1>

English | [简体中文](README.zh-CN.md)

A native Android media toolbox

## ✨ Features

### Images

- Edit one image across multiple tools, with undo/redo and a shared export step
- Remove backgrounds locally with RMBG 1.4 (LiteRT with compatible Qualcomm NPUs and GPU/CPU fallback) or RMBG 2.0 (MNN OpenCL GPU)
- Refine background removal masks with hard or soft keep/remove brushes, and temporarily store masks for reuse across images
- Crop to any size or aspect ratio, including block-aligned lossless JPEG cropping
- Expand the canvas freely or to a preset aspect ratio, filling new areas with a solid color or transparency
- Rotate to any integer angle and flip horizontally or vertically, with automatic canvas expansion or a fixed canvas size
- Cover or erase areas with rectangles, ellipses, freehand selections, and brushes
- Resize with interpolation, adjust compression quality, and export PNG, JPEG, or WebP
- Pick pixel colors, copy HEX codes, and extract representative colors from images
- Edit or delete metadata
- Extract still images from Motion Photos; finalize Xiaomi recoverable edits

Supports importing JPEG, PNG, WebP, BMP, and other image formats, plus device-supported HEIF/AVIF; supports exporting JPEG, PNG, and WebP. Editing and export preserve supported color spaces (such as Display P3 and Adobe RGB) and relevant EXIF metadata, including capture dates and camera details, by default.

Supports importing phone DNG and LibRaw-supported camera RAW files, developed into full-resolution sRGB images for editing.

### Audio

- Edit one audio file at a time: preview playback, inspect file information, and edit tags and cover artwork
- Support for MP3, FLAC, M4A, Ogg/Vorbis, Opus, WAV, and other TagLib-supported formats; editable fields and artwork support vary by format

#### NCM unlocking

- Import multiple files or scan folders recursively, preserving subfolder structure in the output
- Unlock original MP3/FLAC audio with tags and artwork, with optional copying of ordinary audio files
- Process in the background, stop tasks, retry failures, and choose to skip, rename, or overwrite existing files
- Verify output before optional source deletion, or find and clean up already-unlocked originals after confirmation

## 📥 Download

Download the latest APK from [GitHub Releases](https://github.com/jasongzy/MediaLab/releases/latest).

The APK includes neural network models. First use prepares the selected model on device.

## 🤝 Acknowledgements

- [RMBG 1.4](https://huggingface.co/briaai/RMBG-1.4)
- [RMBG 2.0](https://huggingface.co/briaai/RMBG-2.0)
- [LiteRT](https://github.com/google-ai-edge/LiteRT)
- [MNN](https://github.com/alibaba/MNN)
- [LibRaw](https://www.libraw.org/)
- [DeNcm](https://github.com/Pacoshao/DeNcm)
