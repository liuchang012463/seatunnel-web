# 湖文件大文件上传

## 原有链路

湖文件管理原先把完整文件通过 `multipart/form-data` 发给 Spring，再由 API 服务同步调用 S3 `PutObject` 写入 MinIO。它受 Spring 默认 200 MiB 单文件限制和中间代理请求时限影响；大文件也一直占用 API 连接。页面隐藏了 Ant Upload 列表，并且只上报固定的 10%，无法显示真实进度。

## 上传方式

MinIO JavaScript 客户端提供并发分片能力，但官方说明它主要面向 Node.js，浏览器上传建议由服务端签发预签名 URL。湖文件管理现在由 API 建立 MinIO Multipart Upload，会话记录归当前用户所有；浏览器取得短时、限定对象与 part number 的预签名 URL 后直接向对象存储并发 PUT。浏览器最多同时传 4 个分片，并通过 XHR 上传事件显示实时字节数和进度。API 只处理会话、签名和完成请求，不再转发文件内容。

分片默认 64 MiB；超大对象会自动增大分片以满足 S3 每次最多 10,000 个 part 的限制。完成时服务端校验分片编号和 ETag，再完成 MinIO 上传并登记湖文件元数据。失败时会尝试中止未完成的 Multipart Upload。空文件仍走原有轻量上传接口。

MinIO 凭据始终保留在服务端。前端拿不到 access key、secret key 或 MinIO 的原始 upload ID。

## 部署要求

- `SEATUNNEL_WEB_FILE_RESOURCE_STORAGE_UPLOAD_ENDPOINT` 必须是用户浏览器可访问的 MinIO S3 API 地址；未配置时使用现有 `SEATUNNEL_WEB_FILE_RESOURCE_STORAGE_RUNTIME_ENDPOINT`。该地址可与 SeaTunnel runtime endpoint 相同，但不能是浏览器无法解析的容器内部主机名。
- 浏览器到 MinIO 需要 CORS。MinIO 默认 S3 API CORS 允许所有来源；如果部署限制了来源，请将 `MINIO_API_CORS_ALLOW_ORIGIN` 设置为 Web UI 的精确 origin，并确认反向代理允许 `OPTIONS` 预检与 `PUT` 请求。浏览器需要读取 MinIO 返回的 `ETag` 才能完成分片。
- 浏览器关闭或设备断电时，客户端无法调用 abort。建议在对象存储上配置不完整 multipart upload 的生命周期清理，避免长期占用临时分片空间。

参考：

- [MinIO 浏览器预签名上传指南](https://github.com/minio/docs/blob/main/source/integrations/presigned-put-upload-via-browser.md)
- [MinIO S3 API CORS 处理](https://github.com/minio/minio/blob/master/cmd/api-router.go)
- [MinIO 对象存储限制（包括 multipart 分片数）](https://github.com/minio/minio/blob/master/docs/minio-limits.md)
