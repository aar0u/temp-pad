"use strict";

const express = require("express");
const multer = require("multer");
const fs = require("fs"); // 引入文件系统模块，用于模拟持久化存储（实际上这里不会持久化到磁盘）
const path = require("path");
const app = express();

const host = "0.0.0.0"; // 监听所有接口
const port = 80; // 监听的端口

let history = []; // 创建一个数组来存储历史记录

let workingDir = process.argv[2];
if (!fs.existsSync(workingDir)) {
  workingDir = "/Volumes/RAMDisk";
}

// 加载历史记录（这里实际上是从一个模拟的持久化存储中加载，实际上应该是从数据库或文件系统中加载）
function loadHistory() {
  // 假设我们从文件系统中加载历史记录，这里用硬编码的数据代替
  history = [{ timestamp: Date.now(), text: "started" }];
}

// 保存历史记录（这里实际上是将数据写入一个模拟的持久化存储，实际上应该是写入数据库或文件系统）
function saveHistory() {
  // 在实际应用中，你会将history数组序列化并写入到文件或数据库中
}

// 中间件：验证JSON请求体中的字段不能为空，并限制字符长度
function validateFields(req, res, next) {
  const fieldsToValidate = ["text"]; // 根据需要添加或移除字段
  for (const field of fieldsToValidate) {
    if (!req.body.hasOwnProperty(field) || req.body[field].trim() === "") {
      return res
        .status(400)
        .json({ status: "failed", message: `${field} is required.` });
    }
    if (req.body[field].length > 8000) {
      return res
        .status(400)
        .json({ status: "failed", message: `${field} is too long.` });
    }
  }
  next();
}

function trimFromBeginning(str, tar) {
  if (str.startsWith(tar)) {
    // 如果字符串以指定的单词（后面跟一个空格）开始，则去除它
    return str.substring(tar.length); // +1 是为了去除单词后面的空格
  }
  return str; // 如果字符串不是以指定的单词开始，则返回原始字符串
}

function getIP() {
  const { networkInterfaces } = require("os");
  const nets = networkInterfaces();
  const ip = Object.create(null); // Or just '{}', an empty object

  for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
      // Skip over non-IPv4 and internal (i.e. 127.0.0.1) addresses
      // 'IPv4' is in Node <= 17, from 18 it's a number 4 or 6
      const familyV4Value = typeof net.family === "string" ? "IPv4" : 4;
      if (net.family === familyV4Value && !net.internal) {
        if (!ip[name]) {
          ip[name] = [];
        }
        ip[name].push(net.address);
      }
    }
  }
  return ip;
}

function formatFileSize(bytes) {
  const units = ["B", "KB", "MB", "GB", "TB"];
  let size = bytes;
  let unitIndex = 0;
  while (size >= 1024 && unitIndex < units.length - 1) {
    size /= 1024;
    unitIndex++;
  }
  return `${size.toFixed(2)} ${units[unitIndex]}`;
}

// 初始化时加载历史记录
loadHistory();

app.use(express.static(path.join(__dirname, "public")));
app.use(express.json());

// 配置multer，保持原文件名
const storage = multer.diskStorage({
  destination: function (req, file, cb) {
    console.log(`Upload started for file: ${file.originalname}`);
    // 确保上传目录存在
    if (!fs.existsSync(workingDir)) {
      fs.mkdirSync(workingDir);
    }
    cb(null, workingDir);
  },
  filename: function (req, file, cb) {
    console.log(`Processing file: ${file.originalname}`);
    cb(null, Buffer.from(file.originalname, "latin1").toString("utf8")); // 使用原文件名
  },
});

const upload = multer({
  storage: storage,
  limits: { fileSize: 1024 * 1024 * 1000 }, // 1000 MB limit
  fileFilter: function (req, file, cb) {
    console.log(`Checking file type for: ${file.originalname}`);
    // You can add file type restrictions here if needed
    cb(null, true);
  },
});

// 获取文件列表
app.get("/files", (req, res) => {
  const dirname = req.query.dirname || ""; // 如果没有提供dirname，默认为空字符串（当前目录）
  const fullPath = path.join(workingDir, dirname);
  // 确保请求的路径在当前上传目录或其子目录内
  if (!fullPath.startsWith(workingDir)) {
    return res.status(403).send("Forbidden: Invalid directory path.");
  }

  fs.readdir(fullPath, (err, files) => {
    if (err) {
      return res
        .status(500)
        .json({ status: "failed", message: "Error listing files." });
    }
    // 过滤
    const fileList = files.filter((file) => {
      return !file.startsWith(".");
    });
    // 读取每个文件的统计信息
    const fileInfos = fileList.map((file) => {
      const filePath = path.join(fullPath, file);
      const stats = fs.statSync(filePath);
      return {
        name: file,
        path: trimFromBeginning(filePath, workingDir),
        type: stats.isDirectory() ? "directory" : "file",
        size: stats.isDirectory() ? "" : stats.size,
        uploadTime: stats.mtime.toLocaleString(),
      };
    });

    if (fullPath != workingDir) {
      fileInfos.unshift({
        name: "..",
        path: `${trimFromBeginning(fullPath, workingDir)}/..`,
        type: "directory",
      });
    }

    res.json({
      status: "success",
      message: `${fullPath}`,
      files: fileInfos,
    });
  });
});

// 文件上传
app.post("/upload", upload.single("file"), (req, res) => {
  if (!req.file) {
    return res.status(400).send("No file was uploaded.");
  }

  const fileSizeBytes = req.file.size;
  const fileSizeFormatted = formatFileSize(fileSizeBytes);

  console.log(`File upload completed:
- Filename: ${req.file.originalname}
- Size: ${fileSizeFormatted} (${fileSizeBytes.toLocaleString()} bytes)
- MIME type: ${req.file.mimetype}
- Path: ${req.file.path}`);

  res.send("File uploaded!");
});

// 文件下载
app.get("/download/:filename", (req, res) => {
  const filename = req.params.filename;
  const filePath = path.join(workingDir, filename);

  fs.access(filePath, fs.constants.F_OK, (err) => {
    if (err) {
      return res.status(404).send("File not found.");
    }

    res.download(filePath, filename);
  });
});

// 处理GET请求，显示文本编辑器
app.get("/", (req, res) => {
  res.sendFile(__dirname + "/public/pad.html");
});

// 处理POST请求，保存文本到内存缓存
app.post("/save", validateFields, (req, res) => {
  const newText = req.body.text; // 获取要保存的文本

  console.log("saving:");
  console.group();
  console.log("\x1b[36m%s\x1b[0m", newText);
  console.groupEnd();
  // 将新的历史记录项添加到数组中（这里简单使用当前时间戳作为标识）
  history.push({ timestamp: Date.now(), text: newText });

  // 保存历史记录到模拟的持久化存储中
  saveHistory();

  res.json({ status: "success", message: "saved successfully." });
});

app.get("/load", (req, res) => {
  res.json(history); // 将历史记录作为JSON返回
});

app.listen(port, host, () => {
  const ip = getIP();
  console.log(Object.keys(ip)[0], Object.values(ip)[0]);
  console.log(`running on http://${host}:${port}`);
});
