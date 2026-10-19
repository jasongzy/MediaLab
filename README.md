<p align="center">
  <img src="docs/app-icon.svg" width="144" alt="Media Lab icon">
</p>

<h1 align="center">Media Lab</h1>

[中文文档](README.zh-CN.md)

A native Android media toolbox

## ✨ Features

### Images

- Edit one image across multiple tools, with undo/redo and a shared export step
- Remove backgrounds locally with RMBG 1.4 (LiteRT with compatible Qualcomm NPUs and GPU/CPU fallback) or RMBG 2.0 (MNN OpenCL GPU)
- Crop to any size or aspect ratio, including block-aligned lossless JPEG cropping
- Expand the canvas freely or to a preset aspect ratio, filling new areas with a solid color or transparency
- Cover or erase areas with rectangles, ellipses, freehand selections, and brushes
- Resize with interpolation, adjust compression quality, and export PNG, JPEG, or WebP
- Pick pixel colors, copy HEX codes, and extract representative colors from images
- Clean metadata, extract still images from Motion Photos, and finalize Xiaomi recoverable edits

Supports static JPEG, PNG, WebP, BMP, and device-supported HEIF/AVIF images. Editing and export preserve supported SDR color spaces, including Display P3 and Adobe RGB. Pixel editing and format conversion reduce HDR to SDR; JPEG and WebP export use 8-bit channels. Editing and format conversion preserve relevant EXIF metadata, such as capture dates and camera details, by default.

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
- [DeNcm](https://github.com/Pacoshao/DeNcm)
