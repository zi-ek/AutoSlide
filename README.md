# 安卓自动滑屏器APP [AutoSlide]

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Version](https://img.shields.io/github/v/release/zi-ek/AutoSlide?label=Version)](https://github.com/zi-ek/AutoSlide/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/zi-ek/AutoSlide/total?cacheSeconds=86400)](https://github.com/zi-ek/AutoSlide/releases)
[![Latest Downloads](https://img.shields.io/github/downloads/zi-ek/AutoSlide/latest/total?cacheSeconds=86400)](https://github.com/zi-ek/AutoSlide/releases/latest)


一款适用于自动化测试与内容浏览的安卓自动滑动工具

## 功能特性

- **停顿模式**：提供⌈不停顿/固定时间/随机时间/关键词检测⌋四种模式
- **滑动速度**：提供多档速度并按平滑曲线控制手势时长
- **滑动方向**：支持上下左右四个方向
- **自定义轨迹**：长按方向按钮录制对应方向的滑动路径并在自动滑动时回放
- **悬浮窗控制**：通过悬浮球启停滑动并在运行时自动收缩面板
- **通知栏磁贴**：通过系统快捷设置一键开关悬浮窗服务
- **安全停止**：支持音量键强停与息屏自动停止
- **权限管理**：支持⌈手动开启/Shizuku/ADB⌋三种方式开启无障碍服务
- **关键词检测**：通过 OCR 识别屏幕文字，命中关键词后自动滑动（支持多关键词、忽略大小写、冷却与限次）

## 截图展示

![](assets/screenshot.png?v=20260808)

## 快速开始

### 前提条件

- Android 8.0 及以上版本

### 安装步骤

1. 从[发布页面](https://github.com/zi-ek/AutoSlide/releases/)下载最新的APK文件
2. 打开下载的APK文件并按照屏幕上的指示安装应用程序
3. 启动应用并授予必要的权限以确保其正常运行

## 关键词检测

在滑动设置中选择「关键词检测」模式，并在「关键词检测」卡片中填写关键词（每行一个）后，点击悬浮窗方向按钮即可启动：应用会定时对屏幕截图并识别文字，命中任意关键词时按所选方向滑动一次。

- 检测间隔：两次检测之间的间隔（默认 1000ms）
- 冷却时间：触发滑动后的等待时间（默认 1500ms）
- 同一画面最多触发次数：同一段识别文字最多连续滑动的次数（默认 3 次），文字变化后自动重置
- 忽略大小写：匹配英文关键词时不区分大小写

> 说明：OCR 使用 ML Kit 中文识别模型（随应用打包）。Android 11 及以上系统使用无障碍截图；Android 8-10 需要已授权的 Shizuku 才能截图。

## 贡献代码

欢迎提交 Issue 与 Pull Request

## 许可证

本项目采用 [Apache License 2.0](LICENSE)
